package com.noki.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.noki.vpn.data.NokiBackendConfig
import com.sun.jna.*
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal enum class DesktopUpdateStage { AVAILABLE, DOWNLOADING, READY, APPLYING, FAILED }
internal data class DesktopUpdateState(val version: String? = null, val stage: DesktopUpdateStage = DesktopUpdateStage.AVAILABLE,
    val percent: Int = 0, val error: String? = null)
internal data class DesktopUpdateUi(val state: DesktopUpdateState, val install: () -> Unit)
internal val LocalDesktopUpdate = staticCompositionLocalOf<DesktopUpdateUi?> { null }

/** Velopack's C ABI uses one-byte bool and 64-bit size_t on the supported Windows x64 target. */
internal class VelopackNative(path: Path) {
    private val library = NativeLibrary.getInstance(path.toAbsolutePath().toString(), mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
    private fun function(name: String) = library.getFunction("vpkc_$name")
    fun void(name: String, vararg args: Any?) { function(name).invokeVoid(args) }
    fun byte(name: String, vararg args: Any?): Int = (function(name).invoke(java.lang.Byte.TYPE, args) as Number).toInt()
    fun pointer(name: String, vararg args: Any?): Pointer? = function(name).invokePointer(args)
    fun error(): String = Memory(4096).use { memory ->
        memory.clear()
        function("get_last_error").invokeLong(arrayOf(memory, memory.size()))
        memory.setByte(memory.size() - 1, 0)
        memory.getString(0, "UTF-8").take(2048)
    }
    fun manager(feed: String, locator: Pointer? = null): Pointer {
        val source = Memory(24).use { options ->
            // This is a total transfer deadline; full native packages need time on slow links.
            options.clear(); options.setLong(16, 30 * 60_000L)
            checkNotNull(pointer("new_source_http_url_with_options", feed, options)) { error() }
        }
        try {
            return Memory(Native.POINTER_SIZE.toLong()).use { result ->
                result.clear()
                check(byte("new_update_manager_with_source", source, null, locator, result) != 0) { error() }
                checkNotNull(result.getPointer(0))
            }
        } finally { void("free_source", source) }
    }
    fun check(manager: Pointer): Pointer? = Memory(Native.POINTER_SIZE.toLong()).use { result ->
        result.clear()
        when (byte("check_for_updates", manager, result)) {
            0 -> checkNotNull(result.getPointer(0))
            1, 2 -> null
            else -> error(error())
        }
    }
    fun installedVersion(manager: Pointer): String = Memory(128).use { result ->
        result.clear()
        val length = function("get_current_version").invokeLong(arrayOf(manager, result, result.size()))
        check(length in 1..result.size()) { error() }
        result.getString(0, "UTF-8")
    }
    fun download(manager: Pointer, update: Pointer, progress: VelopackProgress) {
        try { check(byte("download_updates", manager, update, progress, null) != 0) { error() } }
        finally { java.lang.ref.Reference.reachabilityFence(progress) }
    }
    fun apply(manager: Pointer, update: Pointer) {
        check(byte("wait_exit_then_apply_updates", manager, update.getPointer(0), 0.toByte(), 1.toByte(), null, 0L) != 0) { error() }
    }
    companion object {
        private var installed: VelopackNative? = null
        fun startup(packaged: Boolean = WindowsShell.isPackagedProcess()) {
            if (packaged || System.getProperty("org.graalvm.nativeimage.imagecode") != "runtime") return
            val directory = Path.of(ProcessHandle.current().info().command().orElseThrow()).parent
            val sdk = VelopackNative(directory.resolve("velopack_libc_win_x64_msvc.dll"))
            // A pending VPN/kill switch must be stopped by the controller before any replacement.
            sdk.void("app_set_auto_apply_on_startup", 0.toByte())
            sdk.void("app_run", null)
            installed = sdk
            registerWindowsUninstaller(directory)
        }
        fun current(): VelopackNative? = installed
    }
}

internal fun verifyVelopackAbi(native: VelopackNative) {
    val root = Files.createTempDirectory("noki-velopack-abi")
    val current = Files.createDirectory(root.resolve("current"))
    val packages = Files.createDirectory(root.resolve("packages"))
    val manifest = Files.writeString(current.resolve("sq.version"), """
        <package><metadata><id>Noki.Desktop</id><version>0.0.1</version><title>Noki</title><authors>Noki</authors>
        <mainExe>Noki.exe</mainExe><channel>win</channel><os>win</os><rid>win-x64</rid><machineArchitecture>x64</machineArchitecture></metadata></package>
    """.trimIndent())
    val executable = Files.write(root.resolve("Update.exe"), byteArrayOf(77, 90))
    val strings = listOf(root, executable, packages, manifest, current).map { path ->
        val bytes = path.toString().toByteArray(Charsets.UTF_8)
        Memory(bytes.size + 1L).apply { clear(); write(0, bytes, 0, bytes.size) }
    }
    try {
        Memory(48).use { locator ->
            locator.clear(); strings.forEachIndexed { index, value -> locator.setPointer(index * 8L, value) }
            locator.setByte(40, 1)
            val manager = native.manager("https://example.invalid", locator)
            try {
                check(native.byte("is_portable", manager) == 1)
                var received = -1
                val callback = VelopackProgress { received = it }
                com.sun.jna.Function.getFunction(CallbackReference.getFunctionPointer(callback)).invokeVoid(arrayOf(null, 42L))
                java.lang.ref.Reference.reachabilityFence(callback)
                check(received == 42) { "Velopack progress callback ABI failed" }
            } finally { native.void("free_update_manager", manager) }
        }
    } finally {
        strings.forEach(Memory::close)
        for (path in listOf(manifest, executable, current, packages, root)) Files.deleteIfExists(path)
    }
}

internal class VelopackProgress(private val report: (Int) -> Unit) : Callback {
    @Suppress("unused") fun invoke(userData: Pointer?, progress: Long) { report(progress.coerceIn(0, 100).toInt()) }
}

/** The installed root survives Velopack updates; portable packages have no Inno uninstaller. */
internal fun windowsUninstallCommand(currentDirectory: Path): String? {
    val current = currentDirectory.toAbsolutePath().normalize()
    val root = current.parent ?: return null
    if (current.fileName.toString() != "current" || Files.exists(root.resolve(".portable")) ||
        !Files.isRegularFile(root.resolve("unins000.exe"), NOFOLLOW_LINKS) ||
        !Files.isRegularFile(root.resolve("Update.exe"), NOFOLLOW_LINKS) ||
        !Files.isRegularFile(current.resolve("sq.version"), NOFOLLOW_LINKS)) return null
    if (root.toRealPath() != root || !Files.readString(current.resolve("sq.version")).contains("<id>Noki.Desktop</id>")) return null
    return WindowsShell.quoteArgument(root.resolve("unins000.exe").toString())
}

internal fun registerWindowsUninstaller(currentDirectory: Path) {
    val command = windowsUninstallCommand(currentDirectory) ?: return
    val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Noki.Desktop"
    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) return
    val location = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "InstallLocation")
    if (Path.of(location).toAbsolutePath().normalize() != currentDirectory.toAbsolutePath().normalize().parent) return
    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "UninstallString", "$command /LOG")
    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, key, "QuietUninstallString", "$command /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG")
}

internal fun stopWindowsUninstallProcess(pid: Long, installedExecutables: Set<Path>): Boolean {
    if (pid !in 1..0xffffffffL || pid == ProcessHandle.current().pid()) return false
    val kernel = Kernel32.INSTANCE
    val process = kernel.OpenProcess(0x1000 or 0x100000, false, pid.toInt()) ?: return false
    try {
        if (kernel.WaitForSingleObject(process, 0) == 0) return false
        val path = CharArray(32768)
        val length = com.sun.jna.ptr.IntByReference(path.size)
        if (!kernel.QueryFullProcessImageName(process, 0, path, length)) return false
        if (Path.of(String(path, 0, length.value)).toAbsolutePath().normalize() !in installedExecutables) return false
        val terminate = kernel.OpenProcess(0x1000 or 0x100000 or 1, false, pid.toInt())
        if (terminate == null) {
            check(kernel.WaitForSingleObject(process, 0) == 0) { "Windows не разрешила завершить процесс Noki" }
            return false
        }
        try {
            check(SingBoxRuntime.creationTime(process) == SingBoxRuntime.creationTime(terminate)) { "Процесс Noki был заменён" }
            check(kernel.TerminateProcess(terminate, 1) || kernel.WaitForSingleObject(terminate, 0) == 0) { "Не удалось закрыть процесс Noki" }
            check(kernel.WaitForSingleObject(terminate, 5_000) == 0) { "Windows не подтвердила завершение процесса Noki" }
        } finally { kernel.CloseHandle(terminate) }
        return true
    } finally { kernel.CloseHandle(process) }
}

internal fun prepareWindowsUninstall() {
    check(WindowsShell.isAdministrator()) { "Для удаления Noki нужны права администратора" }
    val executable = Path.of(ProcessHandle.current().info().command().orElseThrow()).toAbsolutePath().normalize()
    check(windowsUninstallCommand(executable.parent) != null) { "Не найдена установленная копия Noki" }
    // Cleanup must also work when the account vault or saved runtime identity is unreadable.
    val root = executable.parent.parent
    val clients = setOf(executable, root.resolve("Noki Vpn.exe"), root.resolve("Update.exe"))
    ProcessHandle.allProcesses().use { processes -> processes.forEach { stopWindowsUninstallProcess(it.pid(), clients) } }
    acquireDesktopOwnership().use {
        // Stop watchdogs and acquire the client lock before stopping their core.
        ProcessHandle.allProcesses().use { processes -> processes.forEach {
            stopWindowsUninstallProcess(it.pid(), setOf(executable.resolveSibling("sing-box.exe")))
        } }
        WindowsNetworkGuard().use { it.disable() }
        for (name in listOf("runtime.dpapi", "guard.dpapi", "vpn.json"))
            Files.deleteIfExists(WindowsVault.defaultDirectory().resolve(name))
        WindowsShell.removeLaunchAtStartup()
    }
}

internal fun cleanupAppliedPackages(currentDirectory: Path, installedVersion: String) {
    if (!installedVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return
    val installed = installedVersion.split('.').map { it.toIntOrNull() ?: return }
    val current = currentDirectory.toRealPath()
    if (current.fileName.toString() != "current" || !Files.isRegularFile(current.resolve("sq.version"), NOFOLLOW_LINKS) ||
        !Files.isRegularFile(current.parent.resolve("Update.exe"), NOFOLLOW_LINKS)) return
    val packages = current.parent.resolve("packages")
    if (!Files.isDirectory(packages, NOFOLLOW_LINKS) || packages.toRealPath().parent != current.parent) return
    val lockFile = packages.resolve(".velopack_lock")
    if (Files.exists(lockFile, NOFOLLOW_LINKS) && !Files.isRegularFile(lockFile, NOFOLLOW_LINKS)) return
    // Match Velopack's share_mode(0), including exclusion against a separate Update.exe process.
    val handle = Kernel32.INSTANCE.CreateFile(lockFile.toString(), WinNT.GENERIC_READ or WinNT.GENERIC_WRITE,
        0, null, WinNT.OPEN_ALWAYS, 0, null)
    if (handle == WinBase.INVALID_HANDLE_VALUE) return
    try {
        val pattern = Regex("Noki\\.Desktop-([0-9]+)\\.([0-9]+)\\.([0-9]+)-full\\.nupkg")
        Files.newDirectoryStream(packages).use { files ->
            for (file in files) {
                val match = pattern.matchEntire(file.fileName.toString()) ?: continue
                val version = match.groupValues.drop(1).mapNotNull(String::toIntOrNull)
                if (version.size == 3 && compareValuesBy(version, installed, { it[0] }, { it[1] }, { it[2] }) <= 0 &&
                    Files.isRegularFile(file, NOFOLLOW_LINKS)) runCatching { Files.deleteIfExists(file) }
            }
        }
    } finally { Kernel32.INSTANCE.CloseHandle(handle) }
}

/** One manager owns the check/download/apply lifetime for installed and portable distributions. */
internal class WindowsUpdates(private val native: VelopackNative,
    private val pollIntervalMillis: Long = 10 * 60_000L,
    private val onFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val openManager: () -> Pointer = { native.manager("${NokiBackendConfig.apiBaseUrl}/v1/app/windows-releases") },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val mutable = MutableStateFlow(DesktopUpdateState())
    val state = mutable.asStateFlow()
    private var manager: Pointer? = null
    private var update: Pointer? = null
    private var downloadJob: Job? = null
    private var checkJob: Job? = null
    private var lastCheckAt: Long? = null
    init {
        require(pollIntervalMillis > 0)
        scope.launch {
            try {
                while (isActive) {
                    checkNow()
                    delay(pollIntervalMillis)
                }
            } finally {
                withContext(NonCancellable) { operation.withLock {
                    update?.let { native.void("free_update_info", it) }; update = null
                    manager?.let { native.void("free_update_manager", it) }; manager = null
                } }
            }
        }
    }
    @Synchronized fun checkNow() {
        if (checkJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (lastCheckAt?.let { now - it in 0 until minOf(30_000L, pollIntervalMillis) } == true) return
        lastCheckAt = now
        checkJob = scope.launch { operation.withLock {
            if (mutable.value.stage in setOf(DesktopUpdateStage.DOWNLOADING, DesktopUpdateStage.READY, DesktopUpdateStage.APPLYING)) return@withLock
            runCatching {
                val opened = manager ?: openManager().also { manager = it }
                runCatching {
                    cleanupAppliedPackages(Path.of(ProcessHandle.current().info().command().orElseThrow()).parent, native.installedVersion(opened))
                }
                val found = native.check(opened)
                update?.let { native.void("free_update_info", it) }
                update = found
                mutable.value = DesktopUpdateState(found?.getPointer(0)?.getPointer(8)?.getString(0, "UTF-8"))
            }.onFailure { if (it !is CancellationException) onFailure("update_check_failed", it) }
        } }
    }
    @Synchronized fun download() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { operation.withLock {
            if (mutable.value.stage != DesktopUpdateStage.AVAILABLE && mutable.value.stage != DesktopUpdateStage.FAILED) return@withLock
            val opened = manager ?: return@withLock
            val found = update ?: return@withLock
            mutable.value = mutable.value.copy(stage = DesktopUpdateStage.DOWNLOADING, percent = 0, error = null)
            try {
                val callback = VelopackProgress { mutable.value = mutable.value.copy(percent = it) }
                native.download(opened, found, callback)
                ensureActive()
                mutable.value = mutable.value.copy(stage = DesktopUpdateStage.READY, percent = 100)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                onFailure("update_download_failed", error)
                mutable.value = mutable.value.copy(stage = DesktopUpdateStage.FAILED, error = "download_failed")
            }
        } }
    }
    suspend fun applyAndRestart() = withContext(Dispatchers.IO) { operation.withLock {
        val opened = checkNotNull(manager)
        val found = checkNotNull(update)
        mutable.value = mutable.value.copy(stage = DesktopUpdateStage.APPLYING)
        try { native.apply(opened, found) }
        catch (error: Exception) {
            onFailure("update_apply_failed", error)
            mutable.value = mutable.value.copy(stage = DesktopUpdateStage.FAILED, error = "apply_failed")
            throw IllegalStateException("Не удалось запустить установку обновления. Повторите попытку.", error)
        }
    } }
    fun stopFailed() {
        if (mutable.value.stage == DesktopUpdateStage.READY)
            mutable.value = mutable.value.copy(stage = DesktopUpdateStage.FAILED, error = "vpn_stop_failed")
    }
    override fun close() { scope.cancel() }
}
