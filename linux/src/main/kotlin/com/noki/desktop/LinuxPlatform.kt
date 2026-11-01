package com.noki.desktop

import com.noki.vpn.data.EndpointRankingPolicy
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

/** Linux host integration; protocol, account and routing state remain in the existing PC owners. */
internal object LinuxPlatform {
    val uid: Int by lazy { (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Number).toInt() }
    val runtimeDirectory: Path by lazy { Path.of("/run/user/$uid/noki").also { privatePath(it, true) } }
    val dataDirectory: Path get() = Path.of(System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith('/') }
        ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "noki", "desktop")
    private var key: ByteArray? = null

    fun command(arguments: List<String>, input: ByteArray = byteArrayOf(), timeoutSeconds: Long = 30): Pair<Int, String> {
        val output = Files.createTempFile("noki-linux-", ".out")
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            process.outputStream.use { it.write(input) }
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "${Path.of(arguments.first()).fileName}: превышено время ожидания" }
            require(Files.size(output) <= 4 * 1024 * 1024) { "Слишком большой ответ системной команды" }
            return process.exitValue() to Files.readString(output)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
            Files.deleteIfExists(output)
        }
    }

    fun helper(action: String): JSONObject {
        val executable = Path.of("/usr/libexec/noki/core-helper")
        check(Files.isRegularFile(executable) && (Files.getAttribute(executable, "unix:uid") as Number).toInt() == 0 &&
            (Files.getAttribute(executable, "unix:mode") as Number).toInt() and 0x12 == 0) { "Установите системный пакет Noki для Linux" }
        val result = command(listOf("/usr/bin/pkexec", "--disable-internal-agent", executable.toString(), action, ProcessHandle.current().pid().toString()))
        val reply = runCatching { JSONObject(result.second.trim()) }.getOrNull()
        check(result.first == 0 && reply?.optBoolean("ok") == true) { reply?.optString("error")?.takeIf(String::isNotBlank)
            ?: "Нет разрешения polkit на управление Noki VPN" }
        return checkNotNull(reply)
    }

    fun coreMatches(pid: Int, startedAt: Long): Boolean = runCatching {
        val process = ProcessHandle.of(pid.toLong()).orElse(null) ?: return false
        process.isAlive && process.info().startInstant().map { it.toEpochMilli() }.orElse(-1L) == startedAt &&
            Files.readAllLines(Path.of("/proc/$pid/cgroup")).contains("0::/system.slice/noki-vpn-$uid.service")
    }.getOrDefault(false)

    fun privatePath(path: Path, directory: Boolean = false) {
        if (directory) Files.createDirectories(path)
        check(!Files.isSymbolicLink(path)) { "Папка или файл Noki не должны быть ссылкой" }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
    }

    @Synchronized private fun secretKey(create: Boolean): ByteArray {
        key?.let { return it }
        val lookup = command(listOf("secret-tool", "lookup", "application", "tech.ykino.noki", "purpose", "vault"))
        val raw = lookup.second.trim()
        if (lookup.first == 0 && raw.matches(Regex("[0-9a-f]{64}"))) {
            return raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { key = it }
        }
        check(create && lookup.first == 1 && raw.isEmpty()) { "Не удалось открыть ключ Noki в Secret Service. Разблокируйте системную связку ключей" }
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        val hex = generated.joinToString("") { "%02x".format(it) }
        val stored = command(listOf("secret-tool", "store", "--label=Noki VPN", "application", "tech.ykino.noki", "purpose", "vault"), hex.toByteArray())
        check(stored.first == 0) { "Не удалось сохранить ключ Noki в Secret Service" }
        key = generated
        return generated
    }

    internal fun crypt(bytes: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val prefix = byteArrayOf(78, 75, 86, 49)
        val nonce = if (encrypt) ByteArray(12).also(SecureRandom()::nextBytes) else {
            require(bytes.size >= 32 && bytes.copyOfRange(0, 4).contentEquals(prefix)) { "Неизвестный формат Linux-хранилища Noki" }
            bytes.copyOfRange(4, 16)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(prefix)
        return if (encrypt) prefix + nonce + cipher.doFinal(bytes) else cipher.doFinal(bytes.copyOfRange(16, bytes.size))
    }
    fun protect(bytes: ByteArray): ByteArray = crypt(bytes, secretKey(true), true)
    fun unprotect(bytes: ByteArray): ByteArray = crypt(bytes, secretKey(false), false)
    fun hardwareIdentity(): String? = helper("identity").getString("identity")

    internal fun tcpLatency(host: String, port: Int, timeoutMs: Int, interfaceIndex: Int,
        tunnelPresent: Boolean = java.net.NetworkInterface.getByName("Noki") != null): Int? {
        // shortcut: physical probes pause with TUN; replace when a constrained interface-bound probe is available.
        if (tunnelPresent) return null
        return com.noki.vpn.data.DeviceLatency.measureTcpConnectMs(host, port, timeoutMs, onDiagnostic = {},
            socketFactory = { java.net.Socket().apply {
                val address = java.net.NetworkInterface.getByIndex(interfaceIndex)?.inetAddresses?.toList()
                    ?.filterIsInstance<java.net.Inet4Address>()?.firstOrNull()
                bind(java.net.InetSocketAddress(checkNotNull(address) { "Физическая сеть недоступна" }, 0))
            } })
    }

    fun snapshot(): WindowsNetworkSnapshot {
        val rows = Files.readAllLines(Path.of("/proc/net/route")).drop(1).map { it.trim().split(Regex("\\s+")) }
        val row = rows.filter { it.size >= 8 && it[0] != "Noki" && it[1] == "00000000" && it[3].toIntOrNull(16)?.and(1) == 1 }
            .minByOrNull { it[6].toLongOrNull() ?: Long.MAX_VALUE }
        val network = row?.let { java.net.NetworkInterface.getByName(it[0]) }
        val addresses = network?.inetAddresses?.toList().orEmpty().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }.sorted()
        val kind = if (network != null && Files.isDirectory(Path.of("/sys/class/net", network.name, "wireless"))) EndpointRankingPolicy.NetworkKind.WIFI
            else EndpointRankingPolicy.NetworkKind.OTHER
        return WindowsNetworkSnapshot(if (network == null) "unavailable" else "${network.name}:${addresses.joinToString(",")}:${row[2]}",
            kind, network?.isUp == true && addresses.isNotEmpty(), network?.index)
    }

    fun dnsPolicy(): WindowsDnsPolicy {
        val network = snapshot()
        val default = network.interfaceIndex?.let(java.net.NetworkInterface::getByIndex)
        val servers = mutableListOf<WindowsDnsServer>()
        val rules = mutableListOf<WindowsNrptRule>()
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.name != "Noki" }
        for (item in interfaces) {
            val dns = runCatching { command(listOf("resolvectl", "dns", item.name), timeoutSeconds = 3) }.getOrNull()
            var addresses = if (dns?.first == 0) dns.second.substringAfter(":", "").split(Regex("\\s+")).mapNotNull(::numericAddress) else emptyList()
            val domains = if (addresses.isNotEmpty()) runCatching { command(listOf("resolvectl", "domain", item.name), timeoutSeconds = 3) }.getOrNull() else null
            var suffixes = domains?.takeIf { it.first == 0 }?.second?.substringAfter(":", "")?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            if (addresses.isEmpty()) {
                val nm = runCatching { command(listOf("nmcli", "--terse", "--fields", "IP4.DNS,IP6.DNS,IP4.DOMAIN,IP6.DOMAIN", "device", "show", item.name), timeoutSeconds = 3) }.getOrNull()
                if (nm?.first == 0) {
                    addresses = nm.second.lineSequence().filter { ".DNS[" in it }.mapNotNull { numericAddress(it.substringAfter(':').replace("\\:", ":")) }.toList()
                    suffixes = nm.second.lineSequence().filter { ".DOMAIN[" in it }.map { it.substringAfter(':') }.toList()
                }
            }
            addresses = addresses.map { value ->
                if (java.net.InetAddress.getByName(value).isLinkLocalAddress && ':' in value) value + "%" + item.name else value
            }.distinct()
            addresses.forEach { servers += WindowsDnsServer(it, item.name, item.index, if (item == default) 0 else 100, item != default) }
            for (domain in suffixes) {
                val suffix = domain.removePrefix("~").trimEnd('.')
                if (suffix.isNotBlank() && suffix.matches(Regex("[A-Za-z0-9.-]+"))) {
                    rules += WindowsNrptRule(".$suffix", addresses, false)
                    addresses.forEach { servers += WindowsDnsServer(it, item.name, item.index, 0, true) }
                }
            }
        }
        if (servers.none { !it.nrptOnly } && default != null) {
            for (resolver in listOf(Path.of("/run/systemd/resolve/resolv.conf"), Path.of("/run/NetworkManager/no-stub-resolv.conf"), Path.of("/etc/resolv.conf")).filter(Files::isRegularFile)) {
                val addresses = resolverAddresses(Files.readString(resolver))
                addresses.forEach { servers += WindowsDnsServer(it, default.name, default.index, 0) }
                if (addresses.isNotEmpty()) break
            }
        }
        return WindowsDnsPolicy(servers.distinct(), rules.distinct())
    }

    internal fun resolverAddresses(text: String): List<String> = text.lineSequence().map(String::trim)
        .filter { it.startsWith("nameserver ") || it.startsWith("nameserver\t") }
        .mapNotNull { numericAddress(it.split(Regex("\\s+")).getOrNull(1).orEmpty()) }
        .filter { it != "172.19.0.2" && !it.startsWith("127.") && it != "::1" }.distinct().toList()

    private fun numericAddress(raw: String): String? {
        val value = raw.substringBefore('#').substringBefore('%')
        if (!value.matches(Regex("[0-9a-fA-F:.]+"))) return null
        return runCatching {
            java.net.InetAddress.getByName(value).takeUnless {
                it.isLoopbackAddress || it.isAnyLocalAddress || it.hostAddress == "172.19.0.2" ||
                    it == java.net.InetAddress.getByName("fdfe:dcba:9876::2")
            }?.hostAddress
        }.getOrNull()
    }

    fun open(value: String) {
        // A newly opened browser/viewer must not inherit the GUI scope's Kill switch exemption.
        ProcessBuilder("systemd-run", "--user", "--scope", "--slice=app.slice", "--quiet", "--collect",
            "xdg-open", value).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }
    fun clipboard(text: String?) { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text.orEmpty()), null) }
    fun clipboardText(): String? = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String }.getOrNull()
    fun pickPath(title: String, folder: Boolean, saveName: String?): Path? {
        val arguments = mutableListOf("zenity", "--file-selection", "--title=$title")
        if (folder) arguments += "--directory"
        if (saveName != null) arguments += listOf("--save", "--confirm-overwrite", "--filename=$saveName")
        val result = command(arguments, timeoutSeconds = 600)
        if (result.first == 1) return null
        check(result.first == 0) { "Не удалось открыть системный выбор файла (zenity)" }
        return result.second.trimEnd('\r', '\n').takeIf(String::isNotEmpty)?.let(Path::of)
    }

    fun startup(enabled: Boolean) {
        val directory = Path.of(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith('/') } ?: Path.of(System.getProperty("user.home"), ".config").toString(), "autostart")
        Files.createDirectories(directory)
        val file = directory.resolve("noki.desktop")
        if (enabled) { Files.writeString(file, "[Desktop Entry]\nType=Application\nName=Noki VPN\nExec=noki --tray\nTerminal=false\n"); privatePath(file) }
        else Files.deleteIfExists(file)
    }

    fun applications(): List<WindowsApplication> {
        val roots = listOf(Path.of(System.getProperty("user.home"), ".local/share/applications"), Path.of("/usr/local/share/applications"), Path.of("/usr/share/applications"))
        val result = linkedMapOf<String, WindowsApplication>()
        for (root in roots.filter(Files::isDirectory)) Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".desktop") }.forEach { path ->
            if (Files.size(path) > 128 * 1024 || result.containsKey(path.fileName.toString())) return@forEach
            val rows = Files.readAllLines(path).dropWhile { it.trim() != "[Desktop Entry]" }.drop(1).takeWhile { !it.startsWith('[') }
                .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            if (rows["Hidden"] == "true" || rows["NoDisplay"] == "true") return@forEach
            val command = rows["Exec"]?.let { Regex("^\\s*(?:\"([^\"]+)\"|(\\S+))").find(it) }?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: return@forEach
            if (command == "env") return@forEach
            val executable = if (command.startsWith('/')) Path.of(command) else System.getenv("PATH").orEmpty().split(':').map { Path.of(it, command) }.firstOrNull(Files::isExecutable)
            if (executable != null && Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                result[path.fileName.toString()] = WindowsApplication(rows["Name"] ?: command, null, listOf(executable.toRealPath().toString()))
            }
        } }
        return result.values.sortedBy { it.name.lowercase() }
    }
}

internal interface DesktopTrayHandle : AutoCloseable {
    val lightTheme: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun update(connected: Boolean, language: com.noki.vpn.data.AppLanguage)
}

internal class LinuxDesktopTray(private val onOpen: () -> Unit, private val onToggle: () -> Unit,
    private val onExit: () -> Unit, private val currentState: () -> DesktopState) : DesktopTrayHandle {
    override val lightTheme = kotlinx.coroutines.flow.MutableStateFlow(false)
    val available: Boolean = listOf("org.kde.StatusNotifierWatcher", "org.freedesktop.StatusNotifierWatcher").any { name ->
        runCatching {
            val reply = LinuxPlatform.command(listOf("gdbus", "call", "--session", "--dest", "org.freedesktop.DBus",
                "--object-path", "/org/freedesktop/DBus", "--method", "org.freedesktop.DBus.NameHasOwner", name), timeoutSeconds = 2)
            reply.first == 0 && reply.second.trim() == "(true,)"
        }.getOrDefault(false)
    }
    private val tray = if (available) dev.nucleusframework.composenativetray.tray.api.NativeTray() else null
    private val icons = if (available) listOf(false, true).associateWith { connected ->
        LinuxPlatform.runtimeDirectory.resolve(if (connected) "tray-connected.png" else "tray-disconnected.png").also { path ->
            desktopTrayImage(connected, 64).use { image ->
                checkNotNull(image.encodeToData()).use { Files.write(path, it.bytes) }
            }
        }
    } else emptyMap()
    private var menuKey: Triple<com.noki.vpn.data.AppLanguage, Boolean, Boolean>? = null
    private var connected: Boolean? = null
    override fun update(connected: Boolean, language: com.noki.vpn.data.AppLanguage) {
        val native = tray ?: return
        val state = currentState()
        val active = state.running || state.connecting
        val enabled = state.user != null && (active || !state.loading)
        val next = Triple(language, active, enabled)
        if (this.connected == connected && menuKey == next) return
        this.connected = connected; menuKey = next
        native.update(iconPath = icons.getValue(connected).toString(),
            tooltip = tr(state, if (connected) "Подключено" else "Отключено", if (connected) "Connected" else "Disconnected"),
            primaryAction = onOpen, menuContent = {
                Item(tr(state, "Открыть Noki Vpn", "Open Noki Vpn"), onClick = onOpen)
                Item(tr(state, if (active) "Выключить VPN" else "Включить VPN", if (active) "Turn off VPN" else "Turn on VPN"),
                    isEnabled = enabled, onClick = onToggle)
                Divider()
                Item(tr(state, "Выйти", "Quit"), onClick = onExit)
            })
    }
    override fun close() {
        tray?.dispose()
        icons.values.forEach { Files.deleteIfExists(it) }
    }
}
