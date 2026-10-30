package com.noki.desktop

import com.noki.vpn.data.AppLanguage
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.WPARAM
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class WindowsTrayTest {
    @Test fun `taskbar icon waits for asynchronous HWND creation and restores it on disposal`() {
        var failure: Throwable? = null
        dev.nucleusframework.window.tao.TaoApplication.run { app ->
            val window = app.openWindow(title = "Noki deferred icon probe", visible = false)
            val observations = AtomicInteger()
            val icon = CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
                windowsTaskbarIcon({ if (observations.incrementAndGet() == 1) 0L else window.nativeHandle }, false)
            }
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withTimeout(5_000) { while (window.nativeHandle == 0L) delay(10) }
                    val hwnd = HWND(com.sun.jna.Pointer(window.nativeHandle))
                    withTimeout(5_000) {
                        while (User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong() == 0L) delay(10)
                    }
                    assertTrue(observations.get() >= 2)
                    icon.cancelAndJoin()
                    assertEquals(0L, User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong())
                } catch (error: Throwable) { failure = error }
                finally { icon.cancelAndJoin(); window.requestClose(); app.exit() }
            }
        }
        failure?.let { throw it }
    }

    @Test fun `taskbar icon supplies ICON_BIG without replacing Tao titlebar icon`() {
        var failure: Throwable? = null
        dev.nucleusframework.window.tao.TaoApplication.run { app ->
            val window = app.openWindow(title = "Noki icon probe", visible = false)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    withTimeout(5_000) { while (window.nativeHandle == 0L) delay(10) }
                    window.setIcon(32, 32, ByteArray(32 * 32 * 4) { -1 })
                    delay(100)
                    val hwnd = HWND(com.sun.jna.Pointer(window.nativeHandle))
                    val small = User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(0), LPARAM(0)).toLong()
                    val large = User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong()
                    println("Tao WM_GETICON: hwnd=${window.nativeHandle} small=$small big=$large")
                    assertNotEquals(0L, small)
                    for (light in listOf(false, true)) {
                        windowsTaskbarIcon(hwnd, light).use {
                            val taskbar = User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong()
                            assertNotEquals(0L, taskbar)
                            assertEquals(small, User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(0), LPARAM(0)).toLong())
                            if (light) {
                                window.setIcon(32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 3) -1 else 0 })
                                delay(100)
                                assertEquals("A later Tao icon update must preserve the taskbar icon", taskbar,
                                    User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong())
                            }
                            val info = com.sun.jna.platform.win32.WinGDI.ICONINFO()
                            assertTrue(User32.INSTANCE.GetIconInfo(com.sun.jna.platform.win32.WinDef.HICON(com.sun.jna.Pointer(taskbar)), info))
                            com.sun.jna.platform.win32.GDI32.INSTANCE.DeleteObject(info.hbmColor)
                            com.sun.jna.platform.win32.GDI32.INSTANCE.DeleteObject(info.hbmMask)
                        }
                        assertEquals(large, User32.INSTANCE.SendMessage(hwnd, 0x7F, WPARAM(1), LPARAM(0)).toLong())
                    }
                } catch (error: Throwable) { failure = error }
                finally { window.requestClose(); app.exit() }
            }
        }
        failure?.let { throw it }
    }

    @Test fun `explicit taskbar variants retain their silhouette and contrast`() {
        val dark = checkNotNull(javaClass.getResourceAsStream("/noki/desktop/taskbar.png")).use(javax.imageio.ImageIO::read)
        val light = checkNotNull(javaClass.getResourceAsStream("/noki/desktop/taskbar-light.png")).use(javax.imageio.ImageIO::read)
        var checked = 0
        try {
            for (y in 0 until dark.height) for (x in 0 until dark.width) {
                val original = dark.getRGB(x, y)
                val variant = light.getRGB(x, y)
                assertEquals(original ushr 24, variant ushr 24)
                if (original ushr 24 == 255 && original and 255 > 220) {
                    assertTrue(variant and 255 < 80)
                    checked++
                }
            }
            assertTrue("The supplied taskbar shape must remain visible", checked > 0)
        } finally { dark.flush(); light.flush() }
    }

    @Test fun `supplied tray and taskbar assets are packaged unchanged`() {
        for ((name, size) in listOf("taskbar" to 96, "tray" to 64)) {
            for (suffix in listOf("", "-light")) {
                val path = "noki/desktop/$name$suffix.png"
                val bytes = checkNotNull(javaClass.getResourceAsStream("/$path")).use { it.readAllBytes() }
                assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/resources/$path")), bytes)
                org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
                    assertEquals(size, image.width); assertEquals(size, image.height)
                }
            }
        }
    }

    @Test fun `application ICO preserves the universal artwork at every Windows size`() {
        val bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("build/generated/desktop-icons/app.ico"))
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.short.toInt())
        assertEquals(1, buffer.short.toInt())
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
        assertEquals(sizes.size, buffer.short.toInt())
        for ((index, size) in sizes.withIndex()) {
            val entry = 6 + index * 16
            val length = buffer.getInt(entry + 8)
            val offset = buffer.getInt(entry + 12)
            val frame = bytes.copyOfRange(offset, offset + length)
            org.jetbrains.skia.Image.makeFromEncoded(frame).use { image ->
                assertEquals(size, image.width); assertEquals(size, image.height)
                assertEquals(0, image.getRGB(0, size / 2) ushr 24)
                if (size != 32) assertEquals(0x131313, image.getRGB(size / 4, size / 2) and 0xffffff)
                if (size in listOf(32, 40, 48, 256)) {
                    val name = if (size == 256) "app.png" else "app-$size.png"
                    checkNotNull(javaClass.getResourceAsStream("/noki/desktop/$name")).use { source ->
                        assertArrayEquals("Supplied $size px artwork must be embedded without resampling", source.readAllBytes(), frame)
                    }
                }
                for (background in listOf(0x131313, 0xFFFFFF)) {
                    var visible = 0
                    for (y in 0 until size) for (x in 0 until size) {
                        val pixel = image.getRGB(x, y)
                        if (pixel ushr 24 > 240 && kotlin.math.abs((pixel and 255) - (background and 255)) > 100) visible++
                    }
                    assertTrue("The universal icon must remain visible against both backgrounds at $size px", visible > size * size / 10)
                }
            }
            if (size == 256) {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                java.nio.file.Files.write(java.nio.file.Path.of("build/screenshots/app-icon.png"), frame)
            }
        }
    }

    @Test fun `taskbar theme changes replace the icon without replacing the tray owner`() {
        val light = java.util.concurrent.atomic.AtomicBoolean(false)
        val pointer = java.util.concurrent.atomic.AtomicLong()
        DesktopTray({}, {}, { action, data ->
            if (action == 0 || action == 1) pointer.set(com.sun.jna.Pointer.nativeValue(data.getPointer(32)))
            1
        }, readLightTheme = light::get).use { tray ->
            val owner = tray.windowHandle
            val darkIcon = pointer.get()
            light.set(true)
            User32.INSTANCE.SendMessage(owner, 0x1A, WPARAM(0), LPARAM(0))
            assertTrue(tray.lightTheme.value)
            val lightIcon = pointer.get()
            assertNotEquals(darkIcon, lightIcon)
            tray.update(true, AppLanguage.RU)
            assertNotEquals(lightIcon, pointer.get())
            tray.update(false, AppLanguage.RU)
            assertEquals(lightIcon, pointer.get())
            light.set(false)
            User32.INSTANCE.SendMessage(owner, 0x31A, WPARAM(0), LPARAM(0))
            assertFalse(tray.lightTheme.value)
            assertEquals(darkIcon, pointer.get())
            assertEquals(owner, tray.windowHandle)
            assertTrue(tray.isRegistered)
        }
    }

    @Test fun `tray variants preserve status colors and contrast on both taskbar themes`() {
        for (size in listOf(16, 20, 24, 32, 48, 64)) for (light in listOf(false, true)) {
            for (connected in listOf(false, true)) desktopTrayImage(connected, size, light).use { image ->
                val face = image.getRGB(size / 2, size / 4) and 255
                assertTrue("The door must contrast with the taskbar", if (light) face < 80 else face > 220)
                val point = (size * .8).toInt()
                assertEquals(if (connected) 0x6BC4AA else 0xE5484D, image.getRGB(point, point) and 0xffffff)
            }
        }
    }

    @Test fun `tray tooltip contains only localized connection status`() {
        val tooltip = java.util.concurrent.atomic.AtomicReference<String>()
        DesktopTray({}, {}, { action, data ->
            if (action == 0 || action == 1) tooltip.set(data.getWideString(40))
            1
        }).use { tray ->
            assertEquals("Отключено", tooltip.get())
            tray.update(true, AppLanguage.RU)
            assertEquals("Подключено", tooltip.get())
            tray.update(false, AppLanguage.RU)
            assertEquals("Отключено", tooltip.get())
            tray.update(true, AppLanguage.EN)
            assertEquals("Connected", tooltip.get())
            tray.update(false, AppLanguage.EN)
            assertEquals("Disconnected", tooltip.get())
        }
    }

    @Test fun `platform preflight exercises Windows bindings without elevation or network changes`() {
        runWindowsPlatformPreflight()
    }

    @Test fun `tray survives unavailable Explorer and registers on a bounded retry`() {
        val adds = AtomicInteger()
        val added = CountDownLatch(1)
        val deleted = CountDownLatch(1)
        val tray = DesktopTray({}, {}, { action, _ ->
            when (action) {
                0 -> if (adds.incrementAndGet() >= 3) { added.countDown(); 1 } else 0
                1 -> if (adds.get() >= 3) 1 else 0
                else -> { deleted.countDown(); 1 }
            }
        })
        val owner = tray.windowHandle
        try {
            tray.update(true, AppLanguage.RU)
            assertTrue(added.await(3, TimeUnit.SECONDS))
            User32.INSTANCE.SendMessage(owner, 0, WPARAM(0), LPARAM(0))
            assertTrue(tray.isRegistered)
            assertEquals(3, adds.get())
            val recreated = User32.INSTANCE.RegisterWindowMessage("TaskbarCreated")
            User32.INSTANCE.SendMessage(owner, recreated, WPARAM(0), LPARAM(0))
            assertEquals(4, adds.get())
            assertTrue(tray.isRegistered)
        } finally { tray.close() }
        assertEquals(0L, deleted.count)
        assertFalse(User32.INSTANCE.IsWindow(owner))
        tray.update(false, AppLanguage.EN)
    }

    @Test fun `tray startup failure destroys the owner window before propagating the error`() {
        var owner: HWND? = null
        val failure = runCatching {
            DesktopTray({}, {}, { action, data ->
                if (action == 0) {
                    owner = HWND(data.getPointer(8))
                    error("simulated shell failure")
                }
                1
            })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertNotNull(owner)
        assertFalse(User32.INSTANCE.IsWindow(owner))
    }
}
