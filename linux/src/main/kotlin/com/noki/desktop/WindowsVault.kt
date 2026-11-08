package com.noki.desktop

import com.noki.vpn.data.*
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinError
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONException
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import org.json.JSONArray

data class DesktopRuleSet(val path: String, val name: String, val dnsPath: String? = null) {
    fun normalized(): DesktopRuleSet {
        val file = Path.of(path)
        require(file.isAbsolute && file.fileName.toString().matches(Regex("ruleset-[a-f0-9]{64}\\.json")) && name.length in 1..180) { "Некорректный список маршрутизации" }
        require(dnsPath == null || Path.of(dnsPath) == file.resolveSibling(file.fileName.toString().removeSuffix(".json") + ".dns.json"))
        return this
    }
    val tag: String get() = Path.of(path).fileName.toString().removeSuffix(".json")
}

data class DesktopRoutingRule(val id: String, val name: String, val bypass: Boolean,
    val domains: List<String> = emptyList(), val ips: List<String> = emptyList(), val applications: List<String> = emptyList(),
    val enabled: Boolean = true, val ruleSets: List<DesktopRuleSet> = emptyList()) {
    fun normalized(): DesktopRoutingRule {
        require(id.length in 1..80 && name.trim().length in 1..120) { "Укажите название правила" }
        require(domains.size + ips.size + applications.size + ruleSets.size in 1..500) { "Укажите домены, IP-адреса, приложения или список" }
        require(ruleSets.size <= 10) { "В одном правиле можно добавить до 10 списков" }
        ruleSets.forEach { it.normalized() }
        val paths = applications.map(String::trim)
        require(paths.all { PathValidation.isExecutablePath(it) || PathValidation.isFolderPath(it) }) { "Выберите полный путь к .exe или папке" }
        val normalizedDomains = domains.map { raw ->
            require(raw.length <= 2048)
            requireNotNull(DomainRulePolicy.normalize(raw)) { "Некорректный домен: $raw" }.also {
                if (it.startsWith("regexp:")) try {
                    com.google.re2j.Pattern.compile(it.removePrefix("regexp:"))
                } catch (error: com.google.re2j.PatternSyntaxException) {
                    throw IllegalArgumentException("Некорректное регулярное выражение RE2: $raw", error)
                }
            }
        }.distinct()
        return copy(name = name.trim(), domains = normalizedDomains, ips = ips.map { if (it.trim().startsWith("geoip:", true)) WindowsRuleSets.normalizeGeoIp(it.trim()) else SingBoxConfig.normalizeIpRule(it) }.distinct(),
            applications = paths.distinctBy { if (DesktopBuild.IS_LINUX) it else it.replace('/', '\\').lowercase(java.util.Locale.ROOT) })
    }

    fun serverDomains(): List<String> = domains.filterNot { domain -> ruleSets.any { it.name == domain } }

    companion object {
        val RUSSIAN_RESOURCES = DesktopRoutingRule("russian-resources", "Российские ресурсы", true,
            domains = listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE))
    }
}

/** PC persistence and signing; OS encryption changes without duplicating the stored account model. */
class WindowsVault(val directory: Path = defaultDirectory(), hardwareIdentity: () -> String? = { if (DesktopBuild.IS_LINUX) LinuxPlatform.hardwareIdentity() else windowsHardwareIdentity() }) {
    private fun protect(bytes: ByteArray) = if (DesktopBuild.IS_LINUX) LinuxPlatform.protect(bytes) else Crypt32Util.cryptProtectData(bytes)
    private fun unprotect(bytes: ByteArray) = if (DesktopBuild.IS_LINUX) LinuxPlatform.unprotect(bytes) else Crypt32Util.cryptUnprotectData(bytes)
    private val file = directory.resolve("session.dpapi")
    private val document: JSONObject
    private val challengeMutex = Mutex()
    private val cacheLock = Any()
    private val runtimeLock = runtimeLocks.computeIfAbsent(directory.toAbsolutePath().normalize()) { Any() }
    private val recoveredFiles = linkedMapOf<String, Exception>()
    // Keep the existing namespace so dual-boot Windows/Linux identifies the same physical PC.
    private val hardwareDeviceKey = runCatching(hardwareIdentity).getOrNull()?.let { "dev_" + sha256("noki-windows-hardware-v1:$it") }

    init {
        require(DesktopBuild.IS_LINUX || System.getProperty("os.name").startsWith("Windows")) { "Windows required" }
        Files.createDirectories(directory)
        Files.createDirectories(directory.resolve("geosite"))
        Files.createDirectories(directory.resolve("geoip"))
        restrict(directory, inherit = true)
        document = if (!Files.notExists(file)) {
            restrict(file)
            JSONObject(unprotect(Files.readAllBytes(file)).toString(Charsets.UTF_8))
        } else {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            JSONObject().put("public_key", encode(keys.public.encoded)).put("private_key", encode(keys.private.encoded))
                .also { hardwareDeviceKey?.let { key -> it.put("device_key", key) } }
                .also { saveDocument(it) }
        }
    }

    val publicKey: String get() = document.getString("public_key")
    val deviceKey: String get() = document.optString("device_key").ifBlank {
        "dev_" + sha256("noki-device-key-v1:public_key:${sha256(publicKey)}")
    }
    val registrationDeviceKey: String get() = hardwareDeviceKey ?: deviceKey
    val needsHardwareRegistration: Boolean get() = registrationDeviceKey != deviceKey
    fun acceptRegisteredDevice(device: BackendDevice) {
        require(device.deviceKey == registrationDeviceKey)
        document.put("device_id", device.id).put("device_key", device.deviceKey)
        saveDocument(document)
    }
    val deviceClaims: List<String> get() = listOf(deviceKey.removePrefix("dev_"))
    var deviceId: String
        get() = document.optString("device_id")
        set(value) { document.put("device_id", value); saveDocument(document) }

    suspend fun <T> withChallengeTransaction(block: suspend () -> T): T =
        challengeMutex.withLock { block() }

    fun signChallenge(nonce: String): String {
        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(document.getString("private_key"))),
        )
        return encode(Signature.getInstance("SHA256withRSA").apply {
            initSign(key); update(nonce.toByteArray(Charsets.UTF_8))
        }.sign())
    }

    fun loadTokens(): BackendAuthTokens? = document.optJSONObject("tokens")?.let {
        BackendAuthTokens(it.getString("access_token"), it.optString("refresh_token").ifBlank { null })
    }

    fun saveTokens(tokens: BackendAuthTokens?) {
        if (tokens == null) document.remove("tokens") else document.put("tokens", JSONObject()
            .put("access_token", tokens.accessToken).put("refresh_token", tokens.refreshToken ?: ""))
        document.remove("pending_refresh")
        saveDocument(document)
    }

    fun refreshRequestId(refreshToken: String): String {
        val fingerprint = sha256(refreshToken)
        document.optJSONObject("pending_refresh")?.takeIf { it.optString("token_hash") == fingerprint }
            ?.let { return it.getString("request_id") }
        val requestId = UUID.randomUUID().toString()
        document.put("pending_refresh", JSONObject().put("token_hash", fingerprint).put("request_id", requestId))
        saveDocument(document)
        return requestId
    }

    fun writePrivate(name: String, value: String): Path {
        require(name.matches(Regex("[a-zA-Z0-9.-]+")))
        val target = directory.resolve(name)
        val temporary = directory.resolve("$name.tmp")
        Files.writeString(temporary, value)
        restrict(temporary)
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    fun writeEncrypted(name: String, value: String) {
        require(name.matches(Regex("[a-zA-Z0-9.-]+")))
        if (name in CACHE_FILES) synchronized(cacheLock) { writeEncryptedFile(name, value) }
        else if (name == "runtime.dpapi") synchronized(runtimeLock) { writeEncryptedFile(name, value) }
        else writeEncryptedFile(name, value)
    }

    private fun writeEncryptedFile(name: String, value: String) {
        val target = directory.resolve(name)
        val temporary = Files.createTempFile(directory, "$name.", ".tmp")
        try {
            Files.write(temporary, protect(value.toByteArray(Charsets.UTF_8)))
            restrict(temporary)
            var retries = 0
            while (true) try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                break
            } catch (error: AccessDeniedException) {
                // Windows can briefly deny replacement while another reader or replacement closes its handle.
                if (DesktopBuild.IS_LINUX || retries++ == 3) throw error
                Thread.sleep(25L * retries)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    fun readEncrypted(name: String): String? {
        require(name.matches(Regex("[a-zA-Z0-9.-]+")))
        return if (name == "runtime.dpapi") synchronized(runtimeLock) { readEncryptedFile(name) }
        else readEncryptedFile(name)
    }

    private fun readEncryptedFile(name: String): String? {
        val target = directory.resolve(name)
        val bytes = try { Files.readAllBytes(target) } catch (_: NoSuchFileException) { return null }
        return unprotect(bytes).toString(Charsets.UTF_8)
    }

    internal fun <T> loadCache(name: String, decode: (String?) -> T): T = synchronized(cacheLock) {
        require(name in CACHE_FILES)
        val target = directory.resolve(name)
        val bytes = try {
            check(Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) { "Invalid cache file" }
            Files.newInputStream(target, NOFOLLOW_LINKS).use { it.readNBytes(MAX_CACHE_BYTES + 1) }
        } catch (_: NoSuchFileException) { return@synchronized decode(null) }
        try {
            if (bytes.isEmpty() || bytes.size > MAX_CACHE_BYTES) throw EOFException("Invalid cache size")
            decode(unprotect(bytes).toString(Charsets.UTF_8))
        } catch (error: Exception) {
            if (error !is EOFException && !invalidStoredData(error)) throw error
            // Only disposable caches may be rebuilt; keep the encrypted original before allowing new writes.
            preserveUnreadableFile(name, error)
            decode(null)
        }
    }

    internal fun <T> loadRuntimeRecord(canRecover: () -> Boolean, decode: (String?) -> T): T = synchronized(runtimeLock) {
        try { decode(readEncrypted("runtime.dpapi")) }
        catch (error: Exception) {
            // A lost process identity is recoverable only after the runtime proves no core is running.
            if (!invalidStoredData(error) || !canRecover()) throw error
            synchronized(cacheLock) { preserveUnreadableFile("runtime.dpapi", error) }
            decode(null)
        }
    }

    private fun preserveUnreadableFile(name: String, error: Exception) {
        val target = directory.resolve(name)
        check(Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) { "Invalid stored file" }
        restrict(target)
        Files.move(target, target.resolveSibling("$name.unreadable-${UUID.randomUUID()}.bak"), StandardCopyOption.ATOMIC_MOVE)
        recoveredFiles[name] = error
    }

    internal fun takeRecoveredFiles(): Map<String, Exception> = synchronized(cacheLock) {
        recoveredFiles.toMap().also { recoveredFiles.clear() }
    }

    private val settingsCodec = StoredSettingsCodec {
        DefaultStoredSettingsFactory.create().copy(filterMode = AppFilterMode.ALL_APPS, selectedPackages = emptySet(),
            advancedSettings = AdvancedSettings(), securitySettings = SecuritySettings(biometricEnabled = false))
    }

    fun loadSettings(): StoredSettings {
        var raw = readEncrypted("settings.dpapi")
        raw?.let(::JSONObject)?.takeIf { it.optInt("desktop_ru_relay_reset_version") < 1 }?.let { legacy ->
            legacy.put("ruRelayEnabled", false).put("desktop_ru_relay_reset_version", 1)
            raw = legacy.toString()
            writeEncrypted("settings.dpapi", checkNotNull(raw))
        }
        val decoded = settingsCodec.decode(raw)
        val json = raw?.let(::JSONObject)
        // The shared codec encodes countries but only restores explicit servers; PC resolves countries at connection time.
        val hops = json?.optJSONObject("multiHopSettings")
        fun country(key: String): HopSelection? = hops?.optJSONObject(key)?.takeIf { it.optString("kind") == "COUNTRY" }
            ?.optString("countryCode")?.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.let { HopSelection(ServerSelectionMode.COUNTRY, it) }
        val settings = decoded.copy(advancedSettings = decoded.advancedSettings.copy(multiHop = decoded.advancedSettings.multiHop.let {
            it.copy(entry = country("entry") ?: it.entry, exit = country("exit") ?: it.exit)
        }))
        if (json != null && !json.has("desktop_routing_rules") &&
            (settings.advancedSettings.alwaysOnDomains + settings.advancedSettings.bypassDomains).isNotEmpty()) {
            val rules = listOf(false to settings.advancedSettings.alwaysOnDomains, true to settings.advancedSettings.bypassDomains)
                .flatMap { (bypass, domains) -> domains.map {
                    if (bypass && DomainRulePolicy.normalize(it) == DomainRulePolicy.RUSSIAN_RESOURCES_RULE) DesktopRoutingRule.RUSSIAN_RESOURCES
                    else DesktopRoutingRule(UUID.randomUUID().toString(), it.take(120), bypass, listOf(it)).normalized()
                } }
            val migrated = settings.copy(advancedSettings = settings.advancedSettings.copy(alwaysOnDomains = emptyList(), bypassDomains = emptyList()))
            writeEncrypted("settings.dpapi", JSONObject(settingsCodec.encode(migrated))
                .put("desktop_auto_recovery", json.optBoolean("desktop_auto_recovery", true))
                .put("desktop_reduced_motion", json.optBoolean("desktop_reduced_motion"))
                .put("desktop_earth_disabled", json.optBoolean("desktop_earth_disabled"))
                .put("desktop_routing_rules", encodeRoutingRules(rules)).toString())
            return loadSettings()
        }
        if (settings.filterMode != AppFilterMode.ALL_APPS) {
            val rules = loadRoutingRules().toMutableList()
            if (settings.selectedPackages.isNotEmpty()) rules += DesktopRoutingRule(UUID.randomUUID().toString(), "Приложения",
                settings.filterMode == AppFilterMode.ALL_EXCEPT_SELECTED, applications = settings.selectedPackages.toList()).normalized()
            if (settings.filterMode == AppFilterMode.ONLY_SELECTED && settings.selectedPackages.isNotEmpty()) rules += DesktopRoutingRule(UUID.randomUUID().toString(), "Остальной трафик", true,
                ips = listOf("0.0.0.0/0", "::/0"))
            require(rules.size <= 203)
            val migrated = settings.copy(filterMode = AppFilterMode.ALL_APPS, selectedPackages = emptySet())
            val document = JSONObject(checkNotNull(readEncrypted("settings.dpapi")))
            val encoded = JSONObject(settingsCodec.encode(migrated))
            document.put("filterMode", encoded.get("filterMode")).put("selectedPackages", encoded.get("selectedPackages"))
                .put("desktop_routing_rules_version", 1).put("desktop_routing_rules", encodeRoutingRules(rules))
            writeEncrypted("settings.dpapi", document.toString())
            return migrated
        }
        return settings
    }
    fun saveSettings(settings: StoredSettings) = writeEncrypted("settings.dpapi", JSONObject(settingsCodec.encode(settings))
        .put("desktop_auto_recovery", loadAutoRecovery())
        .put("desktop_reduced_motion", loadReducedMotion())
        .put("desktop_earth_disabled", loadEarthDisabled())
        .put("desktop_routing_rules_version", 1)
        .put("desktop_routing_rules", encodeRoutingRules(loadRoutingRules())).toString())
    internal fun encodeSettings(settings: StoredSettings): String = settingsCodec.encode(settings)
    internal fun decodeSettings(raw: String): StoredSettings = settingsCodec.decode(raw)
    fun loadRoutingRules(): List<DesktopRoutingRule> {
        val json = readEncrypted("settings.dpapi")?.let(::JSONObject) ?: JSONObject(settingsCodec.encode(loadSettings()))
        val rows = json.optJSONArray("desktop_routing_rules") ?: JSONArray()
        val rules = decodeRoutingRules(rows)
        if (json.optInt("desktop_routing_rules_version") >= 1) return rules
        // Older PC versions bypassed Russian resources without a visible saved rule.
        val migrated = if (rules.any { it.bypass && DomainRulePolicy.RUSSIAN_RESOURCES_RULE in it.domains }) rules
            else listOf(DesktopRoutingRule.RUSSIAN_RESOURCES) + rules
        json.put("desktop_routing_rules", encodeRoutingRules(migrated)).put("desktop_routing_rules_version", 1)
        writeEncrypted("settings.dpapi", json.toString())
        return migrated
    }
    fun saveRoutingRules(rules: List<DesktopRoutingRule>) {
        require(rules.size <= 203 && rules.map { it.id }.distinct().size == rules.size)
        for (source in rules.flatMap { it.ruleSets }) {
            source.normalized()
            for (file in listOfNotNull(source.path, source.dnsPath).map { Path.of(it) })
                require(file.parent == directory.toAbsolutePath().normalize() && Files.isRegularFile(file)) { "Файл списка не найден. Добавьте его заново" }
        }
        writeEncrypted("settings.dpapi", JSONObject(settingsCodec.encode(loadSettings()))
            .put("desktop_auto_recovery", loadAutoRecovery())
            .put("desktop_reduced_motion", loadReducedMotion())
            .put("desktop_earth_disabled", loadEarthDisabled())
            .put("desktop_routing_rules_version", 1)
            .put("desktop_routing_rules", encodeRoutingRules(rules.map { it.normalized() })).toString())
    }
    private fun decodeRoutingRules(rows: JSONArray): List<DesktopRoutingRule> {
        require(rows.length() <= 203)
        fun strings(row: JSONObject, key: String): List<String> = row.getJSONArray(key).let { values -> (0 until values.length()).map(values::getString) }
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            DesktopRoutingRule(it.getString("id"), it.getString("name"), it.getBoolean("bypass"), strings(it, "domains"), strings(it, "ips"),
                if (it.has("applications")) strings(it, "applications") else emptyList(), it.optBoolean("enabled", true),
                it.optJSONArray("rule_sets")?.let { sources -> (0 until sources.length()).map { index -> sources.getJSONObject(index).let { source ->
                    DesktopRuleSet(source.getString("path"), source.getString("name"), source.optString("dns_path").ifBlank { null })
                } } }.orEmpty()).normalized()
        } }.also { require(it.map { rule -> rule.id }.distinct().size == it.size) }
    }
    private fun encodeRoutingRules(rules: List<DesktopRoutingRule>): JSONArray = JSONArray(rules.map {
        JSONObject().put("id", it.id).put("name", it.name).put("bypass", it.bypass).put("domains", JSONArray(it.domains)).put("ips", JSONArray(it.ips))
            .put("applications", JSONArray(it.applications)).put("enabled", it.enabled)
            .put("rule_sets", JSONArray(it.ruleSets.map { source -> JSONObject().put("path", source.path).put("name", source.name).put("dns_path", source.dnsPath ?: "") }))
    })
    fun loadReducedMotion(): Boolean = readEncrypted("settings.dpapi")?.let(::JSONObject)?.optBoolean("desktop_reduced_motion") ?: false
    fun loadAutoRecovery(): Boolean = readEncrypted("settings.dpapi")?.let(::JSONObject)?.optBoolean("desktop_auto_recovery", true) ?: true
    fun saveAutoRecovery(enabled: Boolean) = saveDesktopPreference("desktop_auto_recovery", enabled)
    fun saveReducedMotion(enabled: Boolean) = saveDesktopPreference("desktop_reduced_motion", enabled)
    fun loadEarthDisabled(): Boolean = readEncrypted("settings.dpapi")?.let(::JSONObject)?.optBoolean("desktop_earth_disabled") ?: false
    fun saveEarthDisabled(enabled: Boolean) = saveDesktopPreference("desktop_earth_disabled", enabled)
    private fun saveDesktopPreference(key: String, enabled: Boolean) = writeEncrypted("settings.dpapi",
        JSONObject(readEncrypted("settings.dpapi") ?: settingsCodec.encode(loadSettings())).put(key, enabled)
            .also { it.remove("desktop_transparent_window") }.toString())
    fun loadStats(): List<DailyStats> = loadCache("stats.dpapi") { DailyStatsStore.decode(it?.let(::JSONObject)?.optJSONArray("days")?.toString()) }
    fun loadStatsCursor(): JSONObject? = loadCache("stats.dpapi") { it?.let(::JSONObject)?.optJSONObject("cursor") }
    fun saveStats(stats: List<DailyStats>, cursor: JSONObject? = null) = writeEncrypted("stats.dpapi", JSONObject()
        .put("days", org.json.JSONArray(DailyStatsStore.encode(DailyStatsStore.prune(stats)))).put("cursor", cursor).toString())
    fun loadAccountLocal(): JSONObject = loadCache("account-local.dpapi") { it?.let(::JSONObject) ?: JSONObject() }
    fun saveAccountLocal(value: JSONObject) = writeEncrypted("account-local.dpapi", value.toString())

    fun wireGuardKeyPair(): Pair<ByteArray, String> {
        readEncrypted("wireguard-key.dpapi")?.let { raw ->
            val value = JSONObject(raw)
            return Base64.getDecoder().decode(value.getString("private")) to value.getString("public")
        }
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val privateKey = (pair.private as java.security.interfaces.XECPrivateKey).scalar.orElseThrow()
        val coordinate = (pair.public as java.security.interfaces.XECPublicKey).u.toByteArray().reversedArray()
        val publicKey = encode(coordinate.copyOf(32))
        require(privateKey.size == 32)
        writeEncrypted("wireguard-key.dpapi", JSONObject().put("private", encode(privateKey)).put("public", publicKey).toString())
        return privateKey to publicKey
    }

    private fun saveDocument(value: JSONObject) {
        val temporary = directory.resolve("session.dpapi.tmp")
        Files.write(temporary, protect(value.toString().toByteArray(Charsets.UTF_8)))
        restrict(temporary)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrict(path: Path, inherit: Boolean = false) {
        if (DesktopBuild.IS_LINUX) { LinuxPlatform.privatePath(path, Files.isDirectory(path)); return }
        val security = Native.load("advapi32", WindowsNetworkGuard.Security::class.java)
        val descriptor = PointerByReference()
        val sid = WindowsNetworkGuard.currentUserSid()
        val flags = if (inherit) "OICI" else ""
        if (!security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            WString("D:P(A;$flags;FA;;;$sid)"), 1, descriptor, IntByReference())) throw Win32Exception(Native.getLastError())
        try {
            if (!security.SetFileSecurityW(WString(path.toAbsolutePath().toString()),
                WinNT.DACL_SECURITY_INFORMATION or WinNT.PROTECTED_DACL_SECURITY_INFORMATION, checkNotNull(descriptor.value)))
                throw Win32Exception(Native.getLastError())
        } finally { Kernel32.INSTANCE.LocalFree(descriptor.value) }
    }

    companion object {
        private val runtimeLocks = java.util.concurrent.ConcurrentHashMap<Path, Any>()
        private val CACHE_FILES = setOf("stats.dpapi", "account-local.dpapi", "endpoint-health.dpapi", "window.dpapi")
        private const val MAX_CACHE_BYTES = 16 * 1024 * 1024
        private fun invalidStoredData(error: Exception): Boolean = error is JSONException || error is javax.crypto.BadPaddingException ||
            error is Win32Exception && error.errorCode in setOf(WinError.ERROR_INVALID_DATA, WinError.ERROR_INVALID_PARAMETER)

        internal fun windowsHardwareIdentity(): String? {
            val firmware = com.sun.jna.Function.getFunction("kernel32", "GetSystemFirmwareTable", com.sun.jna.Function.ALT_CONVENTION)
            val size = firmware.invokeInt(arrayOf(0x52534D42, 0, null, 0)) // 'RSMB'.
            if (size !in 8..1_048_576) return null
            return com.sun.jna.Memory(size.toLong()).use { buffer ->
                if (firmware.invokeInt(arrayOf(0x52534D42, 0, buffer, size)) != size) return null
                hardwareIdentityFromSmbios(buffer.getByteArray(0, size))
            }
        }

        internal fun hardwareIdentityFromSmbios(raw: ByteArray): String? {
            if (raw.size < 8) return null
            val length = java.nio.ByteBuffer.wrap(raw, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (length < 4 || length > raw.size - 8) return null
            val end = 8 + length
            var offset = 8
            while (offset + 4 <= end) {
                val type = raw[offset].toInt() and 255
                val size = raw[offset + 1].toInt() and 255
                if (size < 4 || offset + size > end) return null
                if (type == 1 && size >= 24) {
                    val uuid = raw.copyOfRange(offset + 8, offset + 24)
                    if (uuid.all { it == 0.toByte() } || uuid.all { it == 255.toByte() }) return null
                    return "smbios:" + uuid.joinToString("") { "%02x".format(it) }
                }
                if (type == 127) return null
                offset += size
                while (offset + 1 < end && (raw[offset] != 0.toByte() || raw[offset + 1] != 0.toByte())) offset++
                offset += 2
            }
            return null
        }

        fun defaultDirectory(): Path = if (DesktopBuild.IS_LINUX) LinuxPlatform.dataDirectory else Path.of(checkNotNull(System.getenv("LOCALAPPDATA")), "Noki", "Desktop")
        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
