package com.noki.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path

internal fun windowsBuildNumber(): Int = Memory(284).use { version ->
    version.clear(); version.setInt(0, 284) // RTL_OSVERSIONINFOEXW.
    check(Function.getFunction("ntdll", "RtlGetVersion", Function.ALT_CONVENTION).invokeInt(arrayOf(version)) == 0)
    version.getInt(12)
}

internal fun callWindowsCom(instance: Pointer, slot: Int, vararg args: Any?): Int =
    Function.getFunction(instance.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        .invokeInt(arrayOf(instance, *args))

internal data class WindowsApplication(val name: String, val folder: String?, val executables: List<String>) {
    val id: String get() = folder ?: executables.first()
    val selectionPaths: Set<String> get() = folder?.let { root ->
        setOf(root.trimEnd('\\', '/') + if (DesktopBuild.IS_LINUX) "/" else "\\") + executables.filterNot { Path.of(it).startsWith(Path.of(root)) }
    } ?: executables.toSet()
}

/** Windows shell owns process startup, elevation, file dialogs and desktop app discovery. */
internal object WindowsShell {
    fun isPackagedProcess(): Boolean {
        val status = Function.getFunction("kernel32", "GetCurrentPackageFullName", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(IntByReference(), null))
        return when (status) {
            122 -> true // ERROR_INSUFFICIENT_BUFFER: the process has a package full name.
            15700 -> false // APPMODEL_ERROR_NO_PACKAGE.
            else -> throw Win32Exception(status)
        }
    }

    fun optimizeProcessHeaps(): Boolean = Memory(8).use { info ->
        info.clear(); info.setInt(0, 1) // HEAP_OPTIMIZE_RESOURCES_INFORMATION version 1.
        Function.getFunction("kernel32", "HeapSetInformation", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(null, 3, info, 8L)) != 0
    }

    fun isAdministrator(): Boolean {
        val token = WinNT.HANDLEByReference()
        check(Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token))
        try {
            return Memory(4).use { elevation ->
                check(Function.getFunction("advapi32", "GetTokenInformation", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenElevation, elevation, 4, IntByReference())) != 0)
                elevation.getInt(0) != 0
            }
        } finally { Kernel32.INSTANCE.CloseHandle(token.value) }
    }

    internal fun ensureAdministrator(args: Array<String>, elevated: Boolean = isAdministrator(),
        relaunch: (List<String>) -> Unit = { arguments -> Kernel32.INSTANCE.CloseHandle(launchApplication(arguments, administrator = true)) }): Boolean {
        val forwarded = args.filter { it == "--tray" || it == "--prepare-uninstall" }
        val expectedUser = args.singleOrNull { it.startsWith("--elevated-user=") }?.substringAfter('=')
        require(forwarded.size <= 1 && args.size == forwarded.size + if (expectedUser == null) 0 else 1) { "Неизвестный параметр запуска Noki" }
        expectedUser?.let(WindowsNetworkGuard::requireCurrentUser)
        if (elevated) return true
        check(expectedUser == null) { "Windows не предоставила права администратора. Noki не запущен" }
        try { relaunch(forwarded + "--elevated-user=${WindowsNetworkGuard.currentUserSid()}") }
        catch (error: Win32Exception) { if (error.errorCode != 1223) throw error }
        return false
    }

    fun launchApplication(arguments: List<String>, administrator: Boolean = false, job: WinNT.HANDLE? = null): WinNT.HANDLE {
        val current = Path.of(ProcessHandle.current().info().command().orElseThrow())
        val packaged = current.fileName.toString().equals("Noki.exe", true)
        val executable = if (packaged) current else Path.of(System.getProperty("java.home"), "bin", "javaw.exe")
        val prefix = if (packaged) emptyList() else listOf("-cp", System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator).joinToString(java.io.File.pathSeparator) { Path.of(it).toAbsolutePath().normalize().toString() },
            "com.noki.desktop.MainKt")
        return launchProcess(executable, prefix + arguments, administrator, job)
    }

    fun launchProcess(executable: Path, arguments: List<String>, administrator: Boolean = false, job: WinNT.HANDLE? = null): WinNT.HANDLE {
        require(!administrator || job == null) { "UAC-запуск не поддерживает startup job" }
        if (!administrator) {
            // CreateProcess keeps the helper's child in the same job; a shell broker need not.
            val kernel = Kernel32.INSTANCE
            val started = WinBase.PROCESS_INFORMATION()
            val startup = WinBase.STARTUPINFO().apply { dwFlags = 1; wShowWindow = com.sun.jna.platform.win32.WinDef.WORD(0) }
            val command = (listOf(executable.toString()) + arguments).joinToString(" ", transform = ::quoteArgument) + '\u0000'
            val flags = 0x08000000 or if (job == null) 0 else 0x00000004 // CREATE_NO_WINDOW | CREATE_SUSPENDED.
            if (!kernel.CreateProcessW(executable.toString(), command.toCharArray(), null, null, false,
                    DWORD(flags.toLong()), null, executable.parent.toString(), startup, started))
                throw Win32Exception(kernel.GetLastError())
            try {
                if (job != null) {
                    if (Function.getFunction("kernel32", "AssignProcessToJobObject", Function.ALT_CONVENTION)
                            .invokeInt(arrayOf(job, started.hProcess)) == 0) throw Win32Exception(kernel.GetLastError())
                    if (Function.getFunction("kernel32", "ResumeThread", Function.ALT_CONVENTION)
                            .invokeInt(arrayOf(started.hThread)) == -1) throw Win32Exception(kernel.GetLastError())
                }
                return started.hProcess
            } catch (failure: Throwable) {
                try {
                    check(kernel.TerminateProcess(started.hProcess, 1) || kernel.WaitForSingleObject(started.hProcess, 0) == 0)
                    check(kernel.WaitForSingleObject(started.hProcess, 2_000) == 0) { "Windows не завершила запуск helper" }
                } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                kernel.CloseHandle(started.hProcess)
                throw failure
            } finally { kernel.CloseHandle(started.hThread) }
        }
        val info = ShellAPI.SHELLEXECUTEINFO().apply {
            cbSize = size(); fMask = 0x00000040; lpVerb = "runas"; lpFile = executable.toString()
            lpParameters = arguments.joinToString(" ", transform = ::quoteArgument)
            lpDirectory = executable.parent.toString(); nShow = 1
        }
        if (!Shell32.INSTANCE.ShellExecuteEx(info)) throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        return checkNotNull(info.hProcess) { "Windows не вернула запущенный процесс" }
    }

    internal fun createStartupJob(): WinNT.HANDLE {
        check(Native.POINTER_SIZE == 8) { "Windows x64 required" }
        val pointer = Function.getFunction("kernel32", "CreateJobObjectW", Function.ALT_CONVENTION)
            .invokePointer(arrayOf(null, null)) ?: throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        val job = WinNT.HANDLE(pointer)
        try { setStartupJobKillOnClose(job, true); return job }
        catch (failure: Throwable) { Kernel32.INSTANCE.CloseHandle(job); throw failure }
    }

    internal fun setStartupJobKillOnClose(job: WinNT.HANDLE, enabled: Boolean) = Memory(144).use { limits ->
        // Windows x64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION; BasicLimitInformation.LimitFlags at 16.
        limits.clear(); limits.setInt(16, if (enabled) 0x2000 else 0)
        if (Function.getFunction("kernel32", "SetInformationJobObject", Function.ALT_CONVENTION)
                .invokeInt(arrayOf(job, 9, limits, 144)) == 0) throw Win32Exception(Kernel32.INSTANCE.GetLastError())
    }

    internal fun terminateStartupJob(job: WinNT.HANDLE) {
        val kernel = Kernel32.INSTANCE
        val processes = mutableListOf<WinNT.HANDLE>()
        try {
            Memory(8 + 64 * Native.POINTER_SIZE.toLong()).use { members ->
                members.clear()
                if (Function.getFunction("kernel32", "QueryInformationJobObject", Function.ALT_CONVENTION)
                        .invokeInt(arrayOf(job, 3, members, members.size().toInt(), null)) == 0)
                    throw Win32Exception(kernel.GetLastError())
                val count = members.getInt(4)
                check(count in 0..64) { "Windows вернула некорректный список startup-процессов" }
                for (index in 0 until count) {
                    val pid = members.getLong(8 + index * Native.POINTER_SIZE.toLong()).toInt()
                    val process = kernel.OpenProcess(0x100000, false, pid)
                    if (process != null) processes += process
                    else if (kernel.GetLastError() != 87) throw Win32Exception(kernel.GetLastError())
                }
            }
            if (Function.getFunction("kernel32", "TerminateJobObject", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(job, 1)) == 0) throw Win32Exception(kernel.GetLastError())
            // ActiveProcesses reaches zero before process handles become signalled on Windows.
            val deadline = System.nanoTime() + 2_000_000_000L
            for (process in processes) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(0, 2_000).toInt()
                check(kernel.WaitForSingleObject(process, remaining) == 0) { "Windows не завершила startup-процесс" }
            }
        } finally { processes.forEach(kernel::CloseHandle) }
    }

    internal fun activeStartupProcesses(job: WinNT.HANDLE): Int = Memory(48).use { accounting ->
        // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.ActiveProcesses, including inherited children.
        accounting.clear()
        if (Function.getFunction("kernel32", "QueryInformationJobObject", Function.ALT_CONVENTION)
                .invokeInt(arrayOf(job, 1, accounting, 48, null)) == 0) throw Win32Exception(Kernel32.INSTANCE.GetLastError())
        accounting.getInt(40)
    }

    internal fun quoteArgument(value: String): String = "\"" + value
        .replace(Regex("(\\\\*)\"")) { it.groupValues[1].repeat(2) + "\\\"" }
        .replace(Regex("\\\\+$")) { it.value.repeat(2) } + "\""

    fun setLaunchAtStartup(enabled: Boolean) {
        if (DesktopBuild.IS_LINUX) { LinuxPlatform.startup(enabled); return }
        val executable = Path.of(ProcessHandle.current().info().command().orElseThrow()).toAbsolutePath()
        check(executable.fileName.toString().equals("Noki.exe", true)) { "Автозапуск доступен в собранном приложении" }
        val sid = WindowsNetworkGuard.currentUserSid()
        val task = Files.createTempFile("noki-startup-", ".xml")
        try {
            Files.write(task, startupTaskXml(executable, sid, enabled))
            val scheduler = Path.of(System.getenv("SystemRoot"), "System32", "schtasks.exe")
            val process = launchProcess(scheduler, listOf("/Create", "/TN", "Noki Vpn $sid", "/XML", task.toString(), "/F"))
            try {
                if (Kernel32.INSTANCE.WaitForSingleObject(process, 30_000) != 0) {
                    Kernel32.INSTANCE.TerminateProcess(process, 1)
                    error("Windows не завершила настройку автозапуска")
                }
                val exit = IntByReference()
                check(Kernel32.INSTANCE.GetExitCodeProcess(process, exit) && exit.value == 0) { "Не удалось изменить автозапуск в планировщике Windows" }
            } finally { Kernel32.INSTANCE.CloseHandle(process) }
        } finally { Files.deleteIfExists(task) }
    }

    fun removeLaunchAtStartup() {
        val task = "Noki Vpn ${WindowsNetworkGuard.currentUserSid()}"
        val key = "Software\\Microsoft\\Windows NT\\CurrentVersion\\Schedule\\TaskCache\\Tree\\$task"
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, key)) return
        val process = launchProcess(Path.of(System.getenv("SystemRoot"), "System32", "schtasks.exe"), listOf("/Delete", "/TN", task, "/F"))
        try {
            check(Kernel32.INSTANCE.WaitForSingleObject(process, 15_000) == 0) { "Windows не завершила удаление автозапуска" }
            val exit = IntByReference()
            check(Kernel32.INSTANCE.GetExitCodeProcess(process, exit) && exit.value == 0) { "Не удалось удалить автозапуск Noki" }
        } finally { Kernel32.INSTANCE.CloseHandle(process) }
    }

    internal fun startupTaskXml(executable: Path, sid: String, enabled: Boolean): ByteArray {
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        // schtasks rejects Java's default UTF-16 BE; Windows needs LE with a BOM.
        return ("\uFEFF" + """<?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <Triggers><LogonTrigger><Enabled>$enabled</Enabled><UserId>${xml(sid)}</UserId><Delay>PT15S</Delay></LogonTrigger></Triggers>
              <Principals><Principal id="User"><UserId>${xml(sid)}</UserId><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
              <Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries><StopIfGoingOnBatteries>false</StopIfGoingOnBatteries><StartWhenAvailable>true</StartWhenAvailable><Enabled>$enabled</Enabled><ExecutionTimeLimit>PT0S</ExecutionTimeLimit></Settings>
              <Actions Context="User"><Exec><Command>${xml(executable.toString())}</Command><Arguments>--tray</Arguments><WorkingDirectory>${xml(executable.parent.toString())}</WorkingDirectory></Exec></Actions>
            </Task>""".trimIndent()).toByteArray(Charsets.UTF_16LE)
    }

    fun openUrl(url: String) {
        if (DesktopBuild.IS_LINUX) { LinuxPlatform.open(url); return }
        val result = Function.getFunction("shell32", "ShellExecuteW", Function.ALT_CONVENTION)
            .invokePointer(arrayOf(null, WString("open"), WString(url), null, null, 1))
        check(Pointer.nativeValue(result) > 32) { "Не удалось открыть браузер" }
    }

    fun copyText(owner: Pointer?, text: String?) {
        if (DesktopBuild.IS_LINUX) { LinuxPlatform.clipboard(text); return }
        fun function(library: String, name: String) = Function.getFunction(library, name, Function.ALT_CONVENTION)
        val allocation = text?.let { checkNotNull(function("kernel32", "GlobalAlloc").invokePointer(arrayOf(2, (it.length + 1L) * 2))) }
        var transferred = false
        try {
            if (allocation != null) {
                val buffer = checkNotNull(function("kernel32", "GlobalLock").invokePointer(arrayOf(allocation)))
                try { buffer.setWideString(0, checkNotNull(text)) } finally { function("kernel32", "GlobalUnlock").invokeInt(arrayOf(allocation)) }
            }
            check(function("user32", "OpenClipboard").invokeInt(arrayOf(checkNotNull(owner))) != 0) { "Буфер обмена занят" }
            try {
                check(function("user32", "EmptyClipboard").invokeInt(emptyArray()) != 0)
                if (allocation != null) {
                    check(function("user32", "SetClipboardData").invokePointer(arrayOf(13, allocation)) != null)
                    transferred = true
                }
            } finally { function("user32", "CloseClipboard").invokeInt(emptyArray()) }
        } finally { if (allocation != null && !transferred) function("kernel32", "GlobalFree").invokePointer(arrayOf(allocation)) }
    }

    fun clipboardText(owner: Pointer): String? {
        if (DesktopBuild.IS_LINUX) return LinuxPlatform.clipboardText()
        fun function(name: String) = Function.getFunction("user32", name, Function.ALT_CONVENTION)
        if (function("IsClipboardFormatAvailable").invokeInt(arrayOf(13)) == 0 || function("OpenClipboard").invokeInt(arrayOf(owner)) == 0) return null
        try {
            val handle = function("GetClipboardData").invokePointer(arrayOf(13)) ?: return null
            val lock = Function.getFunction("kernel32", "GlobalLock", Function.ALT_CONVENTION)
                .invokePointer(arrayOf(handle)) ?: return null
            try { return lock.getWideString(0) }
            finally { Function.getFunction("kernel32", "GlobalUnlock", Function.ALT_CONVENTION).invokeInt(arrayOf(handle)) }
        } finally { function("CloseClipboard").invokeInt(emptyArray()) }
    }

    private var applicationSnapshot: Pair<Long, List<WindowsApplication>>? = null
    private val executableSnapshots = mutableMapOf<String, List<String>>()
    private fun checkHr(value: Int) { check(value >= 0) { "Windows Shell: 0x${Integer.toHexString(value)}" } }
    private fun create(clsid: String, iid: String): Pointer {
        val output = PointerByReference()
        checkHr(Ole32.INSTANCE.CoCreateInstance(GUID(clsid), null, 1, GUID(iid), output).toInt())
        return checkNotNull(output.value)
    }
    private fun <T> apartment(block: () -> T): T {
        checkHr(Ole32.INSTANCE.CoInitializeEx(null, 2).toInt())
        try { return block() } finally { Ole32.INSTANCE.CoUninitialize() }
    }

    fun pickPath(owner: Pointer?, title: String, extensions: String?, folder: Boolean, saveName: String? = null): Path? = if (DesktopBuild.IS_LINUX) LinuxPlatform.pickPath(title, folder, saveName) else apartment {
        val dialog = if (saveName == null) create("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7", "D57C7288-D4AD-4768-BE02-9D969532D960")
            else create("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B", "84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB")
        try {
            checkHr(callWindowsCom(dialog, 9, 0x40 or 0x800 or 0x02000000 or if (folder) 0x20 else if (saveName != null) 0x2 else 0x1000))
            checkHr(callWindowsCom(dialog, 17, WString(title)))
            if (saveName != null) checkHr(callWindowsCom(dialog, 15, WString(saveName)))
            val result: Int = if (extensions == null || folder) callWindowsCom(dialog, 3, owner) else {
                Memory((title.length + 1L) * Native.WCHAR_SIZE).use { name ->
                    Memory((extensions.length + 1L) * Native.WCHAR_SIZE).use { pattern ->
                        Memory(Native.POINTER_SIZE * 2L).use { filter ->
                            name.setWideString(0, title); pattern.setWideString(0, extensions)
                            filter.setPointer(0, name); filter.setPointer(Native.POINTER_SIZE.toLong(), pattern)
                            checkHr(callWindowsCom(dialog, 4, 1, filter))
                            callWindowsCom(dialog, 3, owner)
                        }
                    }
                }
            }
            if (result == 0x800704C7.toInt()) return@apartment null
            checkHr(result)
            val output = PointerByReference()
            checkHr(callWindowsCom(dialog, 20, output))
            val item = checkNotNull(output.value)
            try {
                val text = PointerByReference()
                checkHr(callWindowsCom(item, 5, 0x80058000.toInt(), text))
                try { Path.of(checkNotNull(text.value).getWideString(0)) } finally { Ole32.INSTANCE.CoTaskMemFree(text.value) }
            } finally { callWindowsCom(item, 2) }
        } finally { callWindowsCom(dialog, 2) }
    }

    internal fun verifyDialogAvailable() = apartment {
        val dialog = create("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7", "D57C7288-D4AD-4768-BE02-9D969532D960")
        try { checkHr(callWindowsCom(dialog, 9, 0x40 or 0x800 or 0x20)) } finally { callWindowsCom(dialog, 2) }
    }

    @Synchronized fun installedApplications(): List<WindowsApplication> = if (DesktopBuild.IS_LINUX) LinuxPlatform.applications() else apartment {
        applicationSnapshot?.takeIf { System.nanoTime() - it.first < 300_000_000_000L }?.let { return@apartment it.second }
        data class Install(val name: String, val root: Path?, val executable: Path?)
        val installs = mutableListOf<Install>()
        fun path(value: String?): Path? = value?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let {
            runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
        }
        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            for (key in listOf("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall")) {
                if (!Advapi32Util.registryKeyExists(hive, key)) continue
                for (child in Advapi32Util.registryGetKeys(hive, key)) runCatching {
                    val values = Advapi32Util.registryGetValues(hive, "$key\\$child")
                    val name = (values["DisplayName"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@runCatching
                    val icon = (values["DisplayIcon"] as? String)?.trim().orEmpty()
                    val executable = path(if (icon.startsWith('"')) icon.substringAfter('"').substringBefore('"') else icon.substringBefore(','))
                        ?.takeIf { Files.isRegularFile(it) && it.toString().endsWith(".exe", true) }
                    val root = path(values["InstallLocation"] as? String)?.takeIf { Files.isDirectory(it) && it.parent != null }
                    if (root != null || executable != null) installs += Install(name, root, executable)
                }
            }
        }
        for (base in listOfNotNull(System.getenv("APPDATA"), System.getenv("PROGRAMDATA"))) {
            val programs = Path.of(base, "Microsoft", "Windows", "Start Menu", "Programs")
            if (!Files.isDirectory(programs)) continue
            Files.walk(programs).use { files -> files.filter { Files.isRegularFile(it) && it.toString().endsWith(".lnk", true) }.forEach { shortcut ->
                runCatching {
                    val target = shortcutTarget(shortcut)?.takeIf { Files.isRegularFile(it) && it.toString().endsWith(".exe", true) } ?: return@runCatching
                    if (installs.none { it.executable == target || it.root?.let(target::startsWith) == true })
                        installs += Install(shortcut.fileName.toString().removeSuffix(".lnk"), null, target)
                }
            } }
        }
        val packageKey = "Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\Repository\\Packages"
        if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, packageKey)) {
            for (child in Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, packageKey)) runCatching {
                val values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER, "$packageKey\\$child")
                val root = path(values["PackageRootFolder"] as? String)?.takeIf { Files.isDirectory(it) } ?: return@runCatching
                val name = (values["DisplayName"] as? String)?.takeUnless { it.startsWith("ms-resource:") || it.startsWith('@') }
                    ?: child.substringBefore('_')
                if (installs.none { it.root == root }) installs += Install(name, root, null)
            }
        }
        val sharedRoots = listOfNotNull(System.getenv("WINDIR"), System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
            .mapNotNull(::path).toSet()
        val windowsRoot = path(System.getenv("WINDIR"))
        val output = installs.distinctBy { it.root?.toString()?.lowercase() ?: it.executable?.toString()?.lowercase() }.mapNotNull { install ->
            val exes = linkedSetOf<Path>()
            install.executable?.let(exes::add)
            val folder = install.root?.takeUnless { it in sharedRoots || windowsRoot?.let(it::startsWith) == true }
            if (exes.isEmpty() && folder == null) null else WindowsApplication(install.name, folder?.toString(), exes.map(Path::toString).sorted())
        }
        output.sortedBy { it.name.lowercase() }.also {
            executableSnapshots.clear()
            applicationSnapshot = System.nanoTime() to it
        }
    }

    @Synchronized fun applicationExecutables(application: WindowsApplication): List<String> = executableSnapshots.getOrPut(application.id) {
        val output = application.executables.toMutableSet()
        application.folder?.let(Path::of)?.let { root ->
            val nested = applicationSnapshot?.second.orEmpty().mapNotNull { it.folder?.let(Path::of) }
                .filter { it != root && it.startsWith(root) }
            // Enumerate helpers only for the expanded application; folder routing already includes them.
            runCatching { Files.walk(root, 6).use { files ->
                val iterator = files.iterator()
                var visited = 0
                while (iterator.hasNext() && visited++ < 20_000) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Application scan cancelled")
                    val path = iterator.next()
                    if (path.toString().endsWith(".exe", true) && Files.isRegularFile(path) && nested.none(path::startsWith)) output += path.toString()
                }
            } }.getOrElse { error ->
                if (Thread.currentThread().isInterrupted) throw error
            }
        }
        output.sorted()
    }

    private fun shortcutTarget(path: Path): Path? {
        val link = create("00021401-0000-0000-C000-000000000046", "000214F9-0000-0000-C000-000000000046")
        try {
            val output = PointerByReference()
            checkHr(callWindowsCom(link, 0, GUID("0000010B-0000-0000-C000-000000000046"), output))
            val persisted = checkNotNull(output.value)
            try { checkHr(callWindowsCom(persisted, 5, WString(path.toString()), 0)) } finally { callWindowsCom(persisted, 2) }
            Memory(32768L * Native.WCHAR_SIZE).use { buffer ->
                checkHr(callWindowsCom(link, 3, buffer, 32768, null, 4))
                return buffer.getWideString(0).takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
            }
        } finally { callWindowsCom(link, 2) }
    }
}

/** Text fields use the same native clipboard as the application's copy actions. No Toolkit/EDT. */
@Suppress("DEPRECATION")
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class WindowsClipboard(private val owner: Pointer) : androidx.compose.ui.platform.Clipboard, androidx.compose.ui.platform.ClipboardManager {
    override val nativeClipboard: Any get() = this
    override fun getText() = WindowsShell.clipboardText(owner)?.let { androidx.compose.ui.text.AnnotatedString(it) }
    override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) = WindowsShell.copyText(owner, annotatedString.text)
    override fun getClip() = WindowsShell.clipboardText(owner)?.let { androidx.compose.ui.platform.ClipEntry(java.awt.datatransfer.StringSelection(it)) }
    override fun setClip(clipEntry: androidx.compose.ui.platform.ClipEntry?) {
        val text = (clipEntry?.nativeClipEntry as? java.awt.datatransfer.Transferable)?.let {
            if (it.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) it.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String else null
        }
        if (clipEntry != null && text == null) return
        WindowsShell.copyText(owner, text)
    }
    override suspend fun getClipEntry() = getClip()
    override suspend fun setClipEntry(clipEntry: androidx.compose.ui.platform.ClipEntry?) = setClip(clipEntry)
}
