package com.noki.desktop

import androidx.compose.ui.input.pointer.PointerIcon
import com.noki.vpn.data.AppLanguage
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.ShellAPI
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.graalvm.GraalVmInitializer
import dev.nucleusframework.window.tao.TaoApplication
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent

/** Exercises Native Image's Win32 bindings without elevation, network traffic or persistent state. */
internal fun runWindowsPlatformPreflight() {
    GraalVmInitializer.initialize()
    val sampleDate = java.time.LocalDate.of(2026, 10, 15)
    val russian = java.util.Locale.forLanguageTag("ru")
    check(sampleDate.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", russian)) == "15 окт.")
    check(formatDesktopDate("2026-10-15T12:00:00Z", includeTime = false, language = com.noki.vpn.data.AppLanguage.RU).contains("октября"))
    check(sampleDate.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)) == "15 Oct")
    println("platform-preflight: date-locales=ok")
    check(windowsBuildNumber() >= 19041) { "Noki requires Windows 10 build 19041 or newer" }
    val kernel = Kernel32.INSTANCE
    check(WindowsShell.optimizeProcessHeaps())
    val getProcAddress = Function.getFunction("kernel32", "GetProcAddress", Function.ALT_CONVENTION)
    val jni = "Java_dev_nucleusframework_window_tao_ffi_"
    // Probe the existing loader and exports without allocating an ANGLE context or window.
    for ((library, symbol) in listOf(
        "libGLESv2" to "glGetString",
        "libEGL" to "eglGetProcAddress",
        "nucleus_tao" to "${jni}NativeTaoBridge_nativeIsAvailable",
        "nucleus_tao_gl" to "${jni}NativeTaoGlBridge_nativeEglGetProcFn",
        "nucleus_tao_dnd" to "${jni}NativeTaoWindowsDndBridge_nativeRegister",
        "nucleus_tao_windows_deco" to "${jni}NativeTaoWindowsDecoBridge_nativeApplyTopmost",
        "nucleus_tao_windows_native_view" to "${jni}NativeTaoWindowsNativeViewBridge_nativeAttach",
    )) {
        check(NativeLibraryLoader.load(library, TaoApplication::class.java,
            sidecarFiles = if (library == "libEGL") listOf("libGLESv2.dll") else emptyList())) {
            "Cannot load packaged native library: $library"
        }
        val module = checkNotNull(kernel.GetModuleHandle("$library.dll")) { "Native module not loaded: $library" }
        check(getProcAddress.invokePointer(arrayOf(module, symbol)) != null) { "Missing native export: $symbol" }
    }
    println("platform-preflight: tao-angle-libraries=ok")
    EarthRenderer().use { earth ->
        check(earth.countries.size == 247 && earth.outline("RU").isNotEmpty())
        org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).use { surface ->
            earth.draw(surface.canvas, 1, 1, earth.overview)
        }
    }
    org.jetbrains.skia.Graphics.purgeResourceCache()
    println("platform-preflight: earth-map-resources=ok")
    VelopackNative.current()?.let { verifyVelopackAbi(it); println("platform-preflight: velopack=ok") }
    // Tao uses AWT event data and Compose cursor tokens even though it owns native windows.
    check(setOf(PointerIcon.Default, PointerIcon.Text, PointerIcon.Hand, PointerIcon.Crosshair).size == 4)
    val source = object : Component() {}
    val wheel = MouseWheelEvent(source, MouseWheelEvent.MOUSE_WHEEL, 0, 0, 0, 0, 0, 0, 0, false,
        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1, 1.0)
    check(wheel.scrollAmount == 3 && wheel.preciseWheelRotation == 1.0)
    val key = KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'a')
    check(key.keyChar == 'a' && key.id == KeyEvent.KEY_TYPED)
    println("platform-preflight: compose-cursors-wheel-keyboard=ok")

    val user = User32.INSTANCE
    val point = WinDef.POINT()
    val rectangle = WinDef.RECT().apply { left = point.x; top = point.y; right = left + 1; bottom = top + 1 }
    val monitor = checkNotNull(user.MonitorFromRect(rectangle, 2))
    val info = WinUser.MONITORINFO()
    check(user.GetMonitorInfo(monitor, info).booleanValue())
    desktopTrayMenuPosition(DesktopPoint(point.x, point.y), 200, 300)
    println("platform-preflight: monitors=ok")

    val process = checkNotNull(kernel.OpenProcess(0x1000 or 0x100000, false, ProcessHandle.current().pid().toInt()))
    try {
        val creation = WinBase.FILETIME()
        check(kernel.GetProcessTimes(process, creation, WinBase.FILETIME(), WinBase.FILETIME(), WinBase.FILETIME()))
        check(creation.toDate().time > 0)
        check(kernel.GetProcessId(process) == ProcessHandle.current().pid().toInt())
        check(kernel.WaitForSingleObject(process, 0) == 258)
        val path = CharArray(32768)
        val length = IntByReference(path.size)
        check(kernel.QueryFullProcessImageName(process, 0, path, length) && length.value > 0)
    } finally { check(kernel.CloseHandle(process)) }
    check(ShellAPI.SHELLEXECUTEINFO().size() == 112)
    println("platform-preflight: process-admin=${WindowsShell.isAdministrator()}")
    println("platform-preflight: process=ok")

    val startupJob = WindowsShell.createStartupJob()
    var child: com.sun.jna.platform.win32.WinNT.HANDLE? = null
    try {
        child = WindowsShell.launchProcess(java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "whoami.exe"),
            emptyList(), job = startupJob)
        check(kernel.WaitForSingleObject(child, 5_000) == 0) { "Startup job child did not exit" }
        val exit = IntByReference()
        check(kernel.GetExitCodeProcess(child, exit) && exit.value == 0) { "Startup job child failed" }
        kernel.CloseHandle(child)
        child = null
        val deadline = System.nanoTime() + 2_000_000_000L
        while (WindowsShell.activeStartupProcesses(startupJob) != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        check(WindowsShell.activeStartupProcesses(startupJob) == 0) { "Windows did not finish startup job accounting" }
        WindowsShell.setStartupJobKillOnClose(startupJob, false)
    } finally {
        try {
            WindowsShell.terminateStartupJob(startupJob)
            child?.let { check(kernel.WaitForSingleObject(it, 2_000) == 0) }
        } finally {
            child?.let { kernel.CloseHandle(it) }
            kernel.CloseHandle(startupJob)
        }
    }
    println("platform-preflight: startup-job=ok")

    IPHlpAPI.INSTANCE.GetIfEntry(IPHlpAPI.MIB_IFROW())
    windowsNetworkSnapshot()
    check(windowsSystemDnsServers().all { it.interfaceName.isNotBlank() && it.interfaceIndex > 0 })
    val dnsStarted = System.nanoTime()
    val dnsPolicy = windowsDnsPolicy()
    check(dnsPolicy.diagnostics.none { it.contains("route-lookup-unavailable") || it.startsWith("Route capture unavailable") }) {
        "Native Windows route snapshot unavailable"
    }
    val dnsMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsStarted)
    println("platform-preflight: native-policy=ok dns=${dnsPolicy.servers.size} nrpt=${dnsPolicy.nrpt.size} routes=${dnsPolicy.captureRoutes.size} elapsed-ms=$dnsMillis")
    println("platform-preflight: system-dns-read=ok")
    println("platform-preflight: network-read=ok")
    val plaintext = "Noki platform preflight".toByteArray()
    check(Crypt32Util.cryptUnprotectData(Crypt32Util.cryptProtectData(plaintext)).contentEquals(plaintext))
    check(WindowsNetworkGuard.currentUserSid().startsWith("S-1-"))
    check(WindowsNetworkGuard.Filter().size() == 200)
    check(WindowsNetworkGuard.Condition().size() == 40)
    check(WindowsNetworkGuard.SubLayer().size() == 72)
    WindowsShell.verifyDialogAvailable()
    println("platform-preflight: identity-dpapi-structures-com=ok")

    if (user.FindWindow("Shell_TrayWnd", null) == null) {
        println("platform-preflight: tray=skipped (Explorer unavailable)")
    } else {
        val tray = DesktopTray({}, {})
        val owner = tray.windowHandle
        try {
            check(tray.isRegistered) { "Explorer rejected the tray icon" }
            tray.update(true, AppLanguage.RU)
            check(tray.isRegistered) { "Explorer rejected the tray icon update" }
            Memory(40).use { identifier ->
                identifier.clear(); identifier.setInt(0, 40)
                identifier.setPointer(8, owner.pointer); identifier.setInt(16, 1)
                check(Function.getFunction("shell32", "Shell_NotifyIconGetRect", Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(identifier, WinDef.RECT())) == 0)
            }
        } finally { tray.close() }
        check(!user.IsWindow(owner)) { "Tray owner did not terminate" }
        println("platform-preflight: tray=ok")
    }
    println("platform-preflight: ok")
}
