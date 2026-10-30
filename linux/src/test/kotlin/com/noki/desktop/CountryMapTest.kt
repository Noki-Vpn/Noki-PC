package com.noki.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.Image
import org.junit.Assert.*
import org.junit.Test
import javax.swing.SwingUtilities
import kotlin.math.PI

class CountryMapTest {
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `map repaint profile`() {
        val vault = WindowsVault(java.nio.file.Files.createTempDirectory("noki-map-profile"))
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val state = DesktopState(earthDisabled = true, user = com.noki.vpn.data.BackendUser("preview", "Preview", "preview@example.invalid", null, true, false))
        val timings = linkedMapOf<String, List<Double>>()
        try {
            for (glass in listOf(false, true)) {
                val scene = ImageComposeScene(1000, 650) {
                    DesktopTheme(null) {
                        if (glass) DesktopWorkspace(state, app, DesktopPage.HOME)
                        else CountryMapViewport(Modifier.fillMaxSize(), "PL", null, "Map")
                    }
                }
                try {
                    repeat(30) { scene.render().close(); Thread.sleep(20) }
                    val samples = (0 until 30).map {
                        val started = System.nanoTime()
                        scene.render().close()
                        (System.nanoTime() - started) / 1e6
                    }.sorted()
                    timings[if (glass) "workspace" else "map"] = listOf(samples[15], samples[28])
                } finally { scene.close() }
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/map-repaint-profile.json"), org.json.JSONObject(timings).toString())
            println("Offscreen map repaint [median_ms, p95_ms]: $timings")
        } finally { app.close() }
    }

    @Test fun `map shares all country labels and closed rings without loading a relief atlas`() {
        val data = CountryGeography()
        assertEquals(247, data.countries.size)
        for (code in listOf("LV", "RO", "NL", "US", "RU", "FJ")) {
            assertNotNull(data.countries[code])
            val rings = data.outlines.getValue(code)
            assertTrue(rings.isNotEmpty())
            rings.forEach { assertEquals(it.first(), it.last()) }
        }
        assertEquals(-179f, Math.toDegrees(mapLongitude(Math.toRadians(181.0).toFloat()).toDouble()).toFloat(), .001f)
        assertTrue(mapMercator((PI / 2).toFloat()).isFinite())
        assertEquals(0f, mapMercator(0f), .00001f)
    }

    @Test fun `packed geography preserves every source coordinate without retaining authoring resources in production`() {
        val data = CountryGeography()
        val source = checkNotNull(javaClass.getResourceAsStream("/noki/desktop/earth/countries.json"))
            .use { org.json.JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        fun point(row: org.json.JSONArray) = EarthCountryPoint(Math.toRadians(row.getDouble(0)).toFloat(),
            Math.toRadians(row.getDouble(1)).toFloat(), row.getDouble(2).toFloat())
        val labels = source.getJSONObject("countries")
        assertEquals(labels.keySet(), data.countries.keys)
        labels.keySet().forEach { assertEquals(point(labels.getJSONArray(it)), data.countries[it]) }
        val borders = source.getJSONObject("outlines")
        assertEquals(borders.keySet(), data.outlines.keys)
        borders.keySet().forEach { code ->
            val rings = borders.getJSONArray(code)
            assertEquals(List(rings.length()) { index -> rings.getJSONArray(index).let { ring ->
                List(ring.length()) { point(ring.getJSONArray(it)) }
            } }, data.outlines.getValue(code))
        }
        val resources = java.nio.file.Path.of("build/resources/main/noki/desktop/earth")
        assertTrue(java.nio.file.Files.isRegularFile(resources.resolve("countries.bin")))
        assertFalse(java.nio.file.Files.exists(resources.resolve("countries.json")))
        assertFalse(java.nio.file.Files.exists(resources.resolve("relief.png")))
    }

    @Test fun `map raster has a bounded allocation even on high DPI displays`() = kotlinx.coroutines.runBlocking {
        for ((width, height) in listOf(1 to 1, 1000 to 650, 7680 to 4320, 8192 to 450)) {
            val size = mapRasterSize(width, height)
            assertTrue(size.width in 1..2048 && size.height in 1..2048)
            assertTrue(size.width.toLong() * size.height * 4 <= 8L * 1024 * 1024)
        }
        renderCountryMap(1000, 650, 1f, "PL", null, DesktopUiKit.Accent).use { image ->
            assertEquals(1000, image.width)
            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/screenshots"))
                java.nio.file.Files.write(java.nio.file.Path.of("build/screenshots/map-raster.png"), data.bytes)
            }
        }
        val job = kotlinx.coroutines.Job().apply { cancel() }
        try {
            kotlinx.coroutines.withContext(job) { renderCountryMap(7680, 4320, 2f, "RU", "NL", DesktopUiKit.Accent).close() }
            fail("Cancelled rendering must not publish an image")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `disabling Earth closes native atlas and clears the published renderer without restarting`() {
        val needed = mutableStateOf(true)
        var published: EarthRenderer? = null
        val scene = ImageComposeScene(200, 200) { published = rememberEarthRenderer(needed.value).value?.getOrThrow() }
        fun settle(until: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000
            do {
                SwingUtilities.invokeAndWait { scene.render().close() }
                Thread.sleep(20)
            } while (!until() && System.nanoTime() < deadline)
            assertTrue("Renderer lifecycle timed out", until())
        }
        try {
            settle { published != null }
            val first = checkNotNull(published)
            val imageField = EarthRenderer::class.java.getDeclaredField("image").apply { isAccessible = true }
            val image = imageField.get(first) as Image
            assertFalse(image.isClosed)
            SwingUtilities.invokeAndWait { needed.value = false }
            settle { published == null && image.isClosed }
            assertTrue(first.countries.isEmpty())
            SwingUtilities.invokeAndWait { needed.value = true }
            settle { published != null }
            assertNotSame(first, published)
            val secondImage = imageField.get(published) as Image
            SwingUtilities.invokeAndWait { needed.value = false }
            settle { published == null && secondImage.isClosed }
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
}
