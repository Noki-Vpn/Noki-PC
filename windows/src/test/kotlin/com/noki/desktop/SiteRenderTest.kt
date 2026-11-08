package com.noki.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
class SiteRenderTest {
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    private fun renderBytes() = checkNotNull(javaClass.getResourceAsStream("/noki/desktop/scene/site-render.webp")).use { it.readBytes() }

    @Test fun `small windows decode only the displayed pixels and high DPI keeps the 4K source`() {
        assertEquals(1280, siteRenderDecodeWidth(1050f, 674f))
        assertEquals(3840, siteRenderDecodeWidth(3840f, 2160f))
        decodeSiteRender(1280).use { image -> assertEquals(1280, image.width); assertEquals(720, image.height) }
    }

    @Test fun `site render preserves the original 4K asset without recompression`() {
        val bytes = renderBytes()
        assertEquals("ecedb3b6b238f393bf7347ba6a98f422c27322227b553b0140b8293496ac9512",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        Image.makeFromEncoded(bytes).use { image ->
            assertEquals(3840, image.width)
            assertEquals(2160, image.height)
        }
    }

    @Test fun `site lighting and centered cover crop match the source at a different aspect ratio`() {
        val scene = ImageComposeScene(1000, 610, density = Density(1f)) {
            SiteRenderViewport(Modifier.fillMaxSize(), visible = true, description = "Site render")
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val sample = IntArray(1)
            var loaded = false
            while (!loaded && System.nanoTime() < deadline) {
                scene.render().use { image ->
                    image.toComposeImageBitmap().readPixels(sample, startX = 550, startY = 240, width = 1, height = 1)
                    loaded = sample[0] ushr 24 == 255
                }
                if (!loaded) Thread.sleep(25)
            }
            assertTrue("The asynchronous site render must be painted before checking lighting and crop", loaded)
            scene.render().use { output ->
                val actual = IntArray(1000 * 610)
                output.toComposeImageBitmap().readPixels(actual)
                Image.makeFromEncoded(renderBytes()).use { source ->
                    val pixels = IntArray(source.width * source.height)
                    source.toComposeImageBitmap().readPixels(pixels)
                    // The 16:9 source fills the taller viewport by height and loses equal side strips.
                    val scale = 610.0 / source.height
                    val croppedWidth = 1000 / scale
                    for (x in listOf(350, 550, 700, 800, 950)) for (y in listOf(120, 240, 360, 480)) {
                        val sx = ((source.width - croppedWidth) / 2 + (x + .5) / scale).toInt()
                        val sy = ((y + .5) / scale).toInt()
                        val expected = pixels[sy * source.width + sx]
                        val painted = actual[y * 1000 + x]
                        for (shift in listOf(0, 8, 16)) assertTrue("Lighting/crop mismatch at $x,$y",
                            abs(((expected ushr shift) and 255) * .8 - ((painted ushr shift) and 255)) <= 8)
                    }
                }
            }
        } finally { scene.close() }
    }

    @Test fun `disabled Earth displays a country map below the caption behind home controls`() {
        val directory = Files.createTempDirectory("noki-render-home")
        val vault = WindowsVault(directory)
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val state = DesktopState(earthDisabled = true, user = com.noki.vpn.data.BackendUser("preview", "Preview", "preview@example.invalid", null, true, false)).let {
            it.copy(settings = it.settings.copy(userProfile = it.settings.userProfile.copy(
                serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY, selectedCountryCode = "LV")))
        }
        val scene = ImageComposeScene(1000, 650, density = Density(1f)) {
            DesktopTheme(null) { DesktopWorkspace(state, app, DesktopPage.HOME) }
        }
        try {
            repeat(30) { scene.render(it * 100_000_000L).close(); Thread.sleep(30) }
            scene.render(1_000_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    Files.createDirectories(Path.of("build/screenshots"))
                    Files.write(Path.of("build/screenshots/map-home-1000x650.png"), data.bytes)
                    val painted = javax.imageio.ImageIO.read(data.bytes.inputStream())
                    assertTrue("The selected country must have a mint outline and marker", (500..990).sumOf { x -> (80..600).count { y ->
                        val rgb = painted.getRGB(x, y)
                        ((rgb ushr 8) and 255) > 110 && ((rgb ushr 8) and 255) - ((rgb ushr 16) and 255) > 20
                    } } > 100)
                    assertTrue("The render must not paint over the caption", (painted.getRGB(740, 20) and 255) < 40)
                }
            }
        } finally { scene.close(); app.close() }
    }

    @Test fun `native tray updates state without replacing its registered icon`() {
        DesktopTray({}, {}).use { tray ->
            val owner = tray.windowHandle
            repeat(3) { index ->
                tray.update(index % 2 == 0, com.noki.vpn.data.AppLanguage.RU)
                assertEquals(owner, tray.windowHandle)
                assertTrue(com.sun.jna.platform.win32.User32.INSTANCE.IsWindow(owner))
            }
        }
        for (size in intArrayOf(16, 20, 24, 32, 40, 48, 64)) {
            val on = desktopTrayImage(true, size)
            val off = desktopTrayImage(false, size)
            val x = (size * .8).toInt()
            assertEquals(0x6BC4AA, on.getRGB(x, x) and 0xffffff)
            assertEquals(0xE5484D, off.getRGB(x, x) and 0xffffff)
            on.close(); off.close()
        }
    }

    @Test fun `tray stays connected with the established home session during an unverified probe`() {
        var state by mutableStateOf(DesktopState())
        val tooltip = java.util.concurrent.atomic.AtomicReference<String>()
        val tray = DesktopTray({}, {}, { action, data ->
            if (action == 0 || action == 1) tooltip.set(data.getWideString(40))
            1
        }, readLightTheme = { false })
        val owner = tray.windowHandle
        val scene = ImageComposeScene(1, 1, density = Density(1f)) { DesktopTrayEffect(tray, state) }
        fun expect(next: DesktopState, label: String) {
            state = next
            repeat(4) { scene.render().close() }
            assertEquals(label, tooltip.get())
            assertEquals(owner, tray.windowHandle)
        }
        try {
            expect(DesktopState(running = true, connecting = true), "Отключено")
            val connected = DesktopState(running = true, verified = true, connectedAtMillis = 1_000)
            expect(connected, "Подключено")
            expect(connected.copy(verified = false), "Подключено")
            expect(connected.copy(verified = false, networkRebinding = true), "Подключено")
            expect(connected.copy(verified = false, connecting = true), "Отключено")
            expect(DesktopState(), "Отключено")
        } finally { scene.close(); tray.close() }
        assertFalse(com.sun.jna.platform.win32.User32.INSTANCE.IsWindow(owner))
    }
}
