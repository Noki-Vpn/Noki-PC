package com.noki.desktop

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import com.noki.vpn.data.AppLanguage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.skia.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

internal data class DesktopPoint(val x: Int, val y: Int)

/** A hidden Windows owner receives Explorer broadcasts while Compose windows are disposed. */
internal class DesktopTray(onOpen: () -> Unit, onMenu: (DesktopPoint) -> Unit,
    private val notifyIcon: (Int, Memory) -> Int = { action, data ->
        Function.getFunction("shell32", "Shell_NotifyIconW", Function.ALT_CONVENTION).invokeInt(arrayOf(action, data))
    }, private val readLightTheme: () -> Boolean = ::windowsTaskbarIsLight) : DesktopTrayHandle {
    private val user = User32.INSTANCE
    private val ready = CompletableFuture<HWND>()
    private val icons = mutableMapOf<Pair<Boolean, Boolean>, HICON>()
    private val mutableLightTheme = MutableStateFlow(readLightTheme())
    override val lightTheme = mutableLightTheme.asStateFlow()
    private val data = Memory(976).apply { clear(); setInt(0, 976); setInt(16, 1); setInt(20, 7); setInt(24, 0x8001) }
    private var connected = false
    private val closed = AtomicBoolean()
    internal val windowHandle: HWND get() = ready.get(10, TimeUnit.SECONDS)
    internal val isRegistered: Boolean get() = synchronized(data) { registered }
    private var registered = false
    private var retry = 0
    private var language: AppLanguage? = null
    private var taskbarCreated = -1
    private val callback = WinUser.WindowProc { hwnd, message, wp, lp ->
        when (message) {
            taskbarCreated, 0x8002 -> { retry = 0; registerIcon(hwnd) }
            0x113 -> if (wp.toLong() == 1L) registerIcon(hwnd)
            0x1A, 0x31A -> synchronized(data) {
                if (!closed.get()) {
                    val light = readLightTheme()
                    if (light != mutableLightTheme.value) { mutableLightTheme.value = light; modifyIcon() }
                }
            }
            0x8001 -> when (lp.toInt()) {
                0x202, 0x203 -> onOpen()
                0x205 -> { val p = POINT(); user.GetCursorPos(p); onMenu(DesktopPoint(p.x, p.y)) }
            }
            0x10 -> user.DestroyWindow(hwnd)
            0x2 -> user.PostQuitMessage(0)
            else -> return@WindowProc user.DefWindowProc(hwnd, message, wp, lp)
        }
        LRESULT(0)
    }
    private val thread = Thread({
        val name = "Noki.Tray.${ProcessHandle.current().pid()}"
        var module: HMODULE? = null
        var hwnd: HWND? = null
        try {
            taskbarCreated = user.RegisterWindowMessage("TaskbarCreated")
            check(taskbarCreated != 0) { "Не удалось зарегистрировать сообщение Explorer" }
            module = Kernel32.INSTANCE.GetModuleHandle(null)
            val windowClass = WinUser.WNDCLASSEX().apply { hInstance = module; lpszClassName = name; lpfnWndProc = callback }
            for (connected in listOf(false, true)) for (light in listOf(false, true))
                icons[connected to light] = desktopTrayImage(connected, 32, light).use(::windowsIcon)
            check(user.RegisterClassEx(windowClass).toInt() != 0) { "Не удалось создать класс трея" }
            hwnd = checkNotNull(user.CreateWindowEx(0, name, "Noki tray", 0, 0, 0, 0, 0, null, null, module, null))
            synchronized(data) {
                data.setPointer(8, hwnd.pointer); data.setPointer(32, icons.getValue(false to mutableLightTheme.value).pointer)
                data.setWideString(40, "Отключено")
            }
            if (!closed.get()) registerIcon(hwnd)
            ready.complete(hwnd)
            if (closed.get()) return@Thread
            val message = WinUser.MSG()
            while (true) {
                val result = user.GetMessage(message, null, 0, 0)
                check(result != -1) { "Ошибка очереди сообщений трея" }
                if (result == 0) break
                user.TranslateMessage(message); user.DispatchMessage(message)
            }
        } catch (error: Throwable) { ready.completeExceptionally(error) }
        finally {
            closed.set(true)
            synchronized(data) {
                if (hwnd != null) {
                    runCatching { killRetry(hwnd) }
                    runCatching { notifyIcon(2, data) }
                    if (user.IsWindow(hwnd)) user.DestroyWindow(hwnd)
                }
                registered = false
                if (module != null) user.UnregisterClass(name, module)
                icons.values.forEach(user::DestroyIcon)
                data.close()
            }
        }
    }, "Noki tray").apply { isDaemon = true; start() }

    init { try { ready.get(10, TimeUnit.SECONDS) } catch (error: Throwable) { close(); throw error } }

    private fun killRetry(hwnd: HWND) {
        Function.getFunction("user32", "KillTimer", Function.ALT_CONVENTION).invokeInt(arrayOf(hwnd, 1L))
    }

    private fun registerIcon(hwnd: HWND) = synchronized(data) {
        killRetry(hwnd)
        if (closed.get()) return@synchronized
        mutableLightTheme.value = readLightTheme()
        data.setPointer(32, icons.getValue(connected to mutableLightTheme.value).pointer)
        registered = notifyIcon(0, data) != 0 || notifyIcon(1, data) != 0
        if (!registered && retry < 7) {
            val delay = 250 shl retry++
            check(Function.getFunction("user32", "SetTimer", Function.ALT_CONVENTION)
                .invokeLong(arrayOf(hwnd, 1L, delay, null)) != 0L) { "Не удалось повторить регистрацию трея" }
        }
    }

    override fun update(connected: Boolean, language: AppLanguage) = synchronized(data) {
        if (closed.get()) return@synchronized
        if (this.connected == connected && this.language == language) return@synchronized
        this.connected = connected; this.language = language
        data.setWideString(40, if (language == AppLanguage.RU) {
            if (connected) "Подключено" else "Отключено"
        } else if (connected) "Connected" else "Disconnected")
        modifyIcon()
    }

    private fun modifyIcon() {
        data.setPointer(32, icons.getValue(connected to mutableLightTheme.value).pointer)
        if (registered && notifyIcon(1, data) == 0) {
            registered = false
            user.PostMessage(windowHandle, 0x8002, WPARAM(0), LPARAM(0))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true))
            runCatching { ready.getNow(null) }.getOrNull()?.let { user.PostMessage(it, 0x10, WPARAM(0), LPARAM(0)) }
        if (Thread.currentThread() != thread) thread.join(10_000)
    }
}

internal fun windowsTaskbarIsLight(): Boolean = try {
    Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") != 0
} catch (_: com.sun.jna.platform.win32.Win32Exception) { false }

internal fun desktopTrayImage(connected: Boolean, size: Int, light: Boolean = false): Image = Surface.makeRasterN32Premul(size, size).use { surface ->
    val resource = if (light) "/noki/desktop/tray-light.png" else "/noki/desktop/tray.png"
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream(resource)).use { it.readAllBytes() }
    Image.makeFromEncoded(bytes).use { source ->
        surface.canvas.drawImageRect(source, Rect.makeWH(size.toFloat(), size.toFloat()))
    }
    val diameter = size * .375f
    Paint().use { paint ->
        paint.isAntiAlias = true; paint.color = 0xff272727.toInt()
        val center = size - diameter / 2f - .5f
        surface.canvas.drawCircle(center, center, diameter / 2f, paint)
        paint.color = if (connected) 0xff6bc4aa.toInt() else 0xffe5484d.toInt()
        surface.canvas.drawCircle(center, center, diameter / 2f - size / 16f, paint)
    }
    surface.makeImageSnapshot()
}

internal suspend fun windowsTaskbarIcon(nativeHandle: () -> Long, light: Boolean): Nothing {
    // Tao returns before its OS window exists; HWND 0 cannot receive WM_SETICON.
    var handle = nativeHandle()
    while (handle == 0L) { delay(10); handle = nativeHandle() }
    windowsTaskbarIcon(HWND(com.sun.jna.Pointer(handle)), light).use { awaitCancellation() }
}

internal fun windowsTaskbarIcon(hwnd: HWND, light: Boolean): AutoCloseable {
    val resource = if (light) "/noki/desktop/taskbar-light.png" else "/noki/desktop/taskbar.png"
    val bytes = checkNotNull(DesktopTray::class.java.getResourceAsStream(resource)).use { it.readAllBytes() }
    val icon = Image.makeFromEncoded(bytes).use(::windowsIcon)
    val user = User32.INSTANCE
    // Tao sets ICON_SMALL only; Windows 10's taskbar needs ICON_BIG separately.
    val previous = user.SendMessage(hwnd, 0x80, WPARAM(1), LPARAM(com.sun.jna.Pointer.nativeValue(icon.pointer)))
    return AutoCloseable {
        if (user.IsWindow(hwnd)) user.SendMessage(hwnd, 0x80, WPARAM(1), LPARAM(previous.toLong()))
        user.DestroyIcon(icon)
    }
}

private fun windowsIcon(image: Image): HICON {
    val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes }
    return Memory(bytes.size.toLong()).use { memory ->
        memory.write(0, bytes, 0, bytes.size)
        HICON(checkNotNull(Function.getFunction("user32", "CreateIconFromResourceEx", Function.ALT_CONVENTION)
            .invokePointer(arrayOf(memory, bytes.size, 1, 0x30000, image.width, image.height, 0))))
    }
}

internal fun desktopTrayMenuPosition(point: DesktopPoint, width: Int, height: Int): DesktopPoint {
    val user = User32.INSTANCE
    val monitor = user.MonitorFromPoint(POINT.ByValue().apply { x = point.x; y = point.y }, 2)
    val info = WinUser.MONITORINFO(); check(user.GetMonitorInfo(monitor, info).booleanValue())
    val bounds = info.rcWork
    return DesktopPoint((point.x - width + 8).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width)),
        (point.y - height - 8).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - height)))
}
