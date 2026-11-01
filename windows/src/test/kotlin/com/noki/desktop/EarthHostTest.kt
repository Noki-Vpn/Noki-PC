package com.noki.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.jetbrains.skia.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Production Earth renderer, capture and native host contracts. */
class EarthHostTest {
    @get:org.junit.Rule val mainDispatcher: org.junit.rules.TestRule = DesktopMainDispatcherRule()

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/noki/desktop/earth/$name")) { name }.use { it.readBytes() }
    private val manifest get() = JSONObject(resource("manifest.json").toString(Charsets.UTF_8))

    @Test fun `last Earth window releases ANGLE display and restores without breaking sibling or headless contexts`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val log = Files.createTempFile("noki-earth-lifecycle-", ".log")
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString(),
            "-cp", checkNotNull(System.getProperty("noki.test.classpath")), EarthGpuLifecycleProbe::class.java.name)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue("Isolated Earth lifecycle timed out: $log", child.waitFor(90, java.util.concurrent.TimeUnit.SECONDS))
            val output = Files.readString(log)
            assertEquals(output, 0, child.exitValue())
            assertTrue(output, "earth-gpu-lifecycle: ok" in output)
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }

    @OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `space animates at ten ticks per second without regenerating terrain and stops when reduced or hidden`() {
        EarthRenderer(4096).use { renderer ->
            var visible by mutableStateOf(true)
            var reducedMotion by mutableStateOf(false)
            val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
            val scene = ImageComposeScene(600, 400, coroutineContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(scheduler)) {
                DesktopTheme(null) { EarthViewport(Modifier.fillMaxSize(), "Earth", "Failed", renderer,
                    visible = visible, reducedMotion = reducedMotion) }
            }
            try {
                repeat(4) { scene.render(it * 10_000_000L).close() }
                fun pixels() = scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                }
                var previous = pixels()
                var changed = 0
                repeat(100) { frame ->
                    scheduler.advanceTimeBy(10); scheduler.runCurrent()
                    val current = pixels()
                    val updated = !previous.contentEquals(current)
                    assertEquals("Space must update only at each 100 ms boundary", (frame + 1) % 10 == 0, updated)
                    if (updated) changed++
                    previous = current
                }
                assertEquals("A hundred requested frames must produce only ten space updates", 10, changed)
                assertEquals("Space motion must keep the same relief and atmosphere filter", 1, renderer.generation)
                val output = Path.of("build/screenshots/earth-live-space"); Files.createDirectories(output)
                Files.write(output.resolve("moving.png"), previous)
                scheduler.advanceTimeBy(1_024_000); scheduler.runCurrent()
                assertFalse("Half-speed motion must not repeat after only 1024 seconds", previous.contentEquals(pixels()))
                scheduler.advanceTimeBy(1_024_000); scheduler.runCurrent()
                assertArrayEquals("A complete motion cycle must return to exactly the same frame", previous, pixels())
                assertEquals("Looping the background must not regenerate terrain", 1, renderer.generation)
                reducedMotion = true
                repeat(4) { pixels() }
                val still = pixels()
                scheduler.advanceTimeBy(2_000); scheduler.runCurrent()
                assertFalse("Reduced motion must leave no animation invalidations", scene.hasInvalidations())
                assertArrayEquals(still, pixels())
                reducedMotion = false
                repeat(4) { pixels() }
                scheduler.advanceTimeBy(100); scheduler.runCurrent()
                assertFalse("Normal motion must resume", still.contentEquals(pixels()))
                visible = false
                repeat(4) { pixels() }
                scheduler.advanceTimeBy(2_000); scheduler.runCurrent()
                assertFalse("Hidden space must leave no animation invalidations", scene.hasInvalidations())
                val hidden = ImageIO.read(pixels().inputStream())
                assertTrue("Hidden Earth must also stop drawing its space backdrop",
                    (0 until hidden.height).all { y -> (0 until hidden.width).all { x -> hidden.getRGB(x, y) ushr 24 == 0 } })
                assertEquals(1, renderer.generation)
                println("Live space: 10 visual updates / 100 requested frames / 1 virtual second; 2048-second cycle; terrain generation=1; reduced and hidden idle")
            } finally { scene.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `resizing the viewport fits the camera immediately instead of starting a location flight`() {
        EarthRenderer(4096).use { renderer ->
            val scene = ImageComposeScene(1050, 674) {
                DesktopTheme(null) { EarthViewport(Modifier.fillMaxSize(), "Earth", "Failed", renderer, selectedCountryCode = "PL") }
            }
            val field = EarthRenderer::class.java.getDeclaredField("key").apply { isAccessible = true }
            try {
                repeat(4) { scene.render(it * 10_000_000L).close() }
                scene.constraints = androidx.compose.ui.unit.Constraints.fixed(900, 600)
                repeat(4) { scene.render(100_000_000L + it * 10_000_000L).close() }
                val expected = renderer.viewportCamera(900f, 600f, renderer.countries.getValue("PL"), emptyList())
                assertEquals(expected, (field.get(renderer) as Triple<*, *, *>).third)
                val generation = renderer.generation
                scene.render(500_000_000L).close()
                assertEquals("There must be no 1.5-second resize animation left running", generation, renderer.generation)
            } finally { scene.close() }
        }
    }

    @Test fun `window atlas averages numeric heights and retains all sixteen elevation bits`() {
        val source = ImageIO.read(resource("relief.png").inputStream())
        val window = ImageIO.read(resource("relief-4k.png").inputStream())
        assertEquals(4096, window.width); assertEquals(2048, window.height)
        for (y in 0 until 2048 step 31) for (x in 0 until 4096 step 37) {
            val samples = (0..1).flatMap { dy -> (0..1).map { dx -> source.getRGB(x * 2 + dx, y * 2 + dy) } }
            assertEquals((samples.sumOf { it ushr 8 and 65535 } + 2) / 4, window.getRGB(x, y) ushr 8 and 65535)
            assertEquals((samples.sumOf { it and 255 } + 2) / 4, window.getRGB(x, y) and 255)
        }
        source.flush(); window.flush()
        EarthRenderer(4096).use { renderer ->
            val image = EarthRenderer::class.java.getDeclaredField("image").apply { isAccessible = true }.get(renderer) as Image
            assertEquals(4096, image.width)
            requireNotNull(image.peekPixels()).use { assertEquals(32L * 1024 * 1024, image.imageInfo.computeMinByteSize().toLong()) }
        }
    }

    @Test fun `window relief preserves the master framing and lighting`() {
        val output = Path.of("build/screenshots/earth-window"); Files.createDirectories(output)
        val images = listOf(4096, 8192).map { resolution ->
            EarthRenderer(resolution).use { renderer ->
                Surface.makeRasterN32Premul(1050, 674).use { surface ->
                    surface.canvas.clear(0xff131313.toInt())
                    renderer.draw(surface.canvas, 1050, 674, renderer.viewportCamera(1050f, 674f, renderer.countries.getValue("PL"), emptyList()))
                    surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        Files.write(output.resolve("$resolution.png"), data.bytes); ImageIO.read(data.bytes.inputStream())
                    } }
                }
            }
        }
        var difference = 0L
        for (y in 40 until 674) for (x in 476 until 1050) {
            difference += kotlin.math.abs((images[0].getRGB(x, y) and 255) - (images[1].getRGB(x, y) and 255))
        }
        val mean = difference.toDouble() / (634 * 574)
        println("Window/master relief mean channel difference: $mean / 255")
        assertTrue("Window atlas must retain the same framing and light", mean < 12)
    }

    @Test fun `whole globe stays centred in the right body and close view has a bounded scale`() {
        EarthRenderer().use { renderer ->
            val overview = renderer.viewportCamera(1050f, 674f, null, emptyList())
            val center = EarthCountryPoint(overview.longitude, overview.latitude, 1f).project(overview, 1050f, 674f, renderer.tanFov)!!
            assertEquals(763f, center.x, .01f)
            assertEquals(357f, center.y, .01f)
            val route = renderer.countries.getValue("HK").routeTo(renderer.countries.getValue("US"))
            for ((width, height) in listOf(1050f to 674f, 800f to 450f)) {
                val far = renderer.viewportCamera(width, height, renderer.countries.getValue("US"), route)
                val sphere = EarthCountryPoint(far.longitude, far.latitude, 1f).project(far, width, height, renderer.tanFov)!!
                val radius = height * 1.016f / (2f * renderer.tanFov * kotlin.math.sqrt(far.distance * far.distance - 1.016f * 1.016f))
                assertEquals((476f + width) / 2f, sphere.x, .01f)
                assertEquals((40f + height) / 2f, sphere.y, .01f)
                assertTrue(sphere.x - radius >= 476f + 23f && sphere.x + radius <= width - 23f)
                assertTrue(sphere.y - radius >= 40f + 23f && sphere.y + radius <= height - 23f)
            }
            val near = renderer.viewportCamera(1050f, 674f, renderer.countries.getValue("PL"), emptyList())
            val radius = 674f * 1.016f / (2f * renderer.tanFov * kotlin.math.sqrt(near.distance * near.distance - 1.016f * 1.016f))
            assertTrue("Close views must not enlarge the globe beyond the supplied reference", radius <= 674f * .9f)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `restored country starts at selected camera without a startup flight`() {
        EarthRenderer().use { renderer ->
            val scene = ImageComposeScene(width = 1000, height = 610) {
                DesktopTheme(null) { EarthViewport(Modifier.fillMaxSize(), "3D Earth", "Earth failed",
                    renderer = renderer, selectedCountryCode = "PL") }
            }
            try {
                for (frame in 0..10) {
                    scene.render(frame * 10_000_000L).close()
                    if (renderer.generation > 0) break
                }
                assertEquals(1, renderer.generation)
                val field = EarthRenderer::class.java.getDeclaredField("key").apply { isAccessible = true }
                val initial = field.get(renderer) as Triple<*, *, *>
                assertEquals(EarthCamera.CLOSE_DISTANCE, (initial.third as EarthCamera).distance)
                scene.render(2_000_000_000L).close()
                assertEquals("The restored selection must not animate from an overview", initial, field.get(renderer))
                assertEquals(1, renderer.generation)
            } finally { scene.close() }
        }
    }

    @Test fun `atlas pixels are ready before the first frame without changing relief data`() {
        EarthRenderer(8192).use { renderer ->
            val field = EarthRenderer::class.java.getDeclaredField("image").apply { isAccessible = true }
            val image = field.get(renderer) as Image
            val pixels = requireNotNull(image.peekPixels()) { "The UI frame must not decode the 8K PNG" }
            pixels.use {
                assertEquals(8192, image.width); assertEquals(4096, image.height)
                Image.makeFromEncoded(resource("relief.png")).use { original ->
                    Bitmap().use { expected ->
                        assertTrue(expected.allocPixels(original.imageInfo))
                        assertTrue(original.readPixels(expected))
                        Bitmap().use { actual ->
                            assertTrue(actual.allocPixels(image.imageInfo))
                            assertTrue(image.readPixels(actual))
                            assertArrayEquals(expected.readPixels(), actual.readPixels())
                        }
                    }
                }
            }
        }
    }

    @Test fun `country flight lasts 1500 ms follows the short path and rests at its target`() {
        val initial = EarthCamera.fromManifest(manifest.getJSONObject("cameras").getJSONObject("overview")).copy(distance = EarthCamera.OVERVIEW_DISTANCE)
        val target = initial.copy(longitude = 2.44f, latitude = .63f, distance = EarthCamera.CLOSE_DISTANCE)
        val flight = EarthFlight(initial)
        flight.retarget(target, 0)
        assertEquals(initial.longitude, flight.camera(0).longitude, .000001f)
        assertNotEquals(target, flight.camera(750_000_000))
        assertTrue(flight.moving)
        assertEquals(target, flight.camera(EarthFlight.DURATION_NANOS))
        assertFalse(flight.moving)
        flight.retarget(target, 2_000_000_000)
        assertFalse(flight.moving)
        assertEquals(target, flight.camera(9_000_000_000))
        val dateLine = EarthFlight(initial.copy(longitude = Math.toRadians(179.0).toFloat(), latitude = .2f))
        dateLine.retarget(target.copy(longitude = Math.toRadians(-179.0).toFloat(), latitude = .2f), 0)
        assertTrue(kotlin.math.abs(dateLine.camera(750_000_000).longitude) > 3.1f)
        flight.retarget(initial, 9_000_000_000, immediate = true)
        assertEquals(initial, flight.camera(9_000_000_000)); assertFalse(flight.moving)
    }

    @Test fun `retarget preserves pose and derivatives independently of frame schedule`() {
        val initial = EarthCamera.fromManifest(manifest.getJSONObject("cameras").getJSONObject("overview")).copy(distance = EarthCamera.OVERVIEW_DISTANCE)
        val target = initial.copy(longitude = 2.4f, latitude = -.7f, distance = EarthCamera.CLOSE_DISTANCE, shift = Offset(-.2f, .5f))
        for (schedule in listOf(listOf(0L, 200_000_000, 400_000_000), listOf(0L, 16_000_000, 330_000_000), listOf(0L))) {
            for (time in listOf(600_000_000L, 1_490_000_000L)) {
                val flight = EarthFlight(initial); flight.retarget(target, 0)
                schedule.forEach(flight::camera)
                val before = flight.camera(time - 1_000_000)
                val center = flight.camera(time)
                flight.retarget(initial.copy(longitude = -1.7f, latitude = .8f, distance = EarthCamera.CLOSE_DISTANCE, shift = Offset(.15f, .1f)), time)
                val retarget = flight.camera(time)
                val after = flight.camera(time + 1_000_000)
                fun derivative(a: Float, b: Float) = (b - a) * 1000
                assertEquals(center.longitude, retarget.longitude, .000002f)
                assertEquals(center.latitude, retarget.latitude, .000002f)
                assertEquals(derivative(before.longitude, center.longitude), derivative(retarget.longitude, after.longitude), .02f)
                assertEquals(derivative(before.latitude, center.latitude), derivative(retarget.latitude, after.latitude), .02f)
                assertEquals(derivative(before.distance, center.distance), derivative(retarget.distance, after.distance), .02f)
                assertEquals(derivative(before.shift.x, center.shift.x), derivative(retarget.shift.x, after.shift.x), .01f)
                assertEquals(derivative(before.shift.y, center.shift.y), derivative(retarget.shift.y, after.shift.y), .01f)
                for (step in 0..150) {
                    val pose = flight.camera(time + step * 10_000_000L)
                    assertTrue(pose.longitude.isFinite() && pose.latitude.isFinite())
                    assertTrue("Zoom remains within the reference framing range", pose.distance in 1.64999f..5.60001f)
                }
            }
        }
        val antipodal = EarthFlight(initial.copy(longitude = 0f, latitude = .001f))
        antipodal.retarget(target.copy(longitude = Math.PI.toFloat() - .000001f, latitude = -.001f), 0)
        for (step in 0..150) assertTrue(antipodal.camera(step * 10_000_000L).latitude.isFinite())
        val returnToOverview = EarthFlight(initial)
        returnToOverview.retarget(target, 0)
        returnToOverview.retarget(initial, 1_490_000_000)
        for (step in 0..1500) assertTrue(returnToOverview.camera(1_490_000_000 + step * 1_000_000L).distance in 1.64999f..5.60001f)
    }

    @Test fun `country points are versioned displaced country labels and projection hides the back side`() {
        val data = JSONObject(resource("countries.json").toString(Charsets.UTF_8))
        assertEquals("country-label", data.getString("precision"))
        assertEquals(manifest.getJSONObject("atlas").getString("sha256"), data.getString("atlasSha256"))
        EarthRenderer().use { renderer ->
            assertTrue(renderer.countries.keys.containsAll(listOf("PL", "HK", "NL", "US")))
            val point = renderer.countries.getValue("NL")
            assertTrue(point.radius > 1f && point.radius < 1.0175f)
            val pose = point.camera(renderer.overview).copy(shift = Offset.Zero)
            val screen = requireNotNull(point.project(pose, 1000f, 610f, renderer.tanFov))
            assertEquals(500f, screen.x, .01f); assertEquals(305f, screen.y, .01f)
            assertNull(point.project(pose.copy(longitude = pose.longitude + Math.PI.toFloat(), latitude = -pose.latitude), 1000f, 610f, renderer.tanFov))
            assertNull(point.project(pose, 0f, 610f, renderer.tanFov))
        }
    }

    @Test fun `multihop arc follows the short geographic path and frames both endpoints`() {
        EarthRenderer().use { renderer ->
            val entry = renderer.countries.getValue("NL"); val exit = renderer.countries.getValue("PL")
            val route = entry.routeTo(exit)
            assertEquals(entry, route.first()); assertEquals(exit, route.last())
            assertTrue(route[32].radius > maxOf(entry.radius, exit.radius))
            for ((width, height) in listOf(500f to 450f, 1000f to 610f, 1800f to 1000f)) {
                val tanLimit = renderer.tanFov * minOf(width, height) / height * .5f
                val camera = exit.camera(renderer.overview, route, tanLimit).copy(shift = Offset.Zero)
                for (point in listOf(entry, exit)) {
                    val screen = requireNotNull(point.project(camera, width, height, renderer.tanFov))
                    assertTrue(screen.x in width * .2f..width * .8f && screen.y in height * .2f..height * .8f)
                }
                assertTrue(camera.distance >= EarthCamera.CLOSE_DISTANCE)
            }
            val west = EarthCountryPoint(Math.toRadians(179.0).toFloat(), .2f, 1.002f)
            val east = west.copy(longitude = Math.toRadians(-179.0).toFloat())
            assertTrue(kotlin.math.abs(west.routeTo(east)[32].longitude) > 3.1f)
            val opposite = west.copy(longitude = west.longitude - Math.PI.toFloat(), latitude = -west.latitude)
            assertTrue(west.routeTo(opposite).all { it.longitude.isFinite() && it.latitude.isFinite() && it.radius.isFinite() })
            val distant = entry.routeTo(renderer.countries.getValue("AU"))
            val pose = distant.last().camera(renderer.overview, distant, .15f)
            assertTrue(pose.distance > renderer.overview.distance)
            val flight = EarthFlight(renderer.overview); flight.retarget(pose, 0)
            for (step in 0..150) assertTrue(flight.camera(step * 10_000_000L).distance in EarthCamera.CLOSE_DISTANCE..pose.distance + .00001f)
            flight.retarget(exit.camera(renderer.overview), EarthFlight.DURATION_NANOS)
            assertEquals(EarthCamera.CLOSE_DISTANCE, flight.camera(EarthFlight.DURATION_NANOS * 2).distance, .000001f)
        }
    }

    @Test fun `country outlines preserve rings and hide the back side`() {
        EarthRenderer().use { renderer ->
            val rings = renderer.outline("PL")
            assertTrue(rings.isNotEmpty())
            assertTrue(renderer.outline("HK").isNotEmpty())
            assertTrue(renderer.outline("ZZ").isEmpty())
            for (ring in rings) {
                assertTrue(ring.size >= 4)
                assertEquals(ring.first(), ring.last())
                assertTrue(ring.all { it.radius in 1f..1.0175f })
            }
            val point = renderer.countries.getValue("PL")
            val front = point.camera(renderer.overview).copy(shift = Offset.Zero)
            val back = front.copy(longitude = front.longitude + Math.PI.toFloat(), latitude = -front.latitude)
            assertTrue(rings.flatten().all { it.project(front, 1000f, 610f, renderer.tanFov) != null })
            assertTrue(rings.flatten().all { it.project(back, 1000f, 610f, renderer.tanFov) == null })
        }
    }

    @Test fun `geographic paths never join separate rings or hidden segments`() {
        val path = earthPath(listOf(
            listOf(Offset(10f, 10f), Offset(20f, 10f), null, Offset(10f, 30f), Offset(20f, 30f)),
            listOf(Offset(40f, 10f), Offset(50f, 10f)),
        ))
        val image = Surface.makeRasterN32Premul(60, 40).use { surface ->
            surface.canvas.clear(0)
            Paint().use { paint ->
                paint.color = -1; paint.mode = PaintMode.STROKE; paint.strokeWidth = 2f
                surface.canvas.drawPath(path.asSkiaPath(), paint)
            }
            surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(data.bytes.inputStream()) } }
        }
        assertEquals(255, image.getRGB(15, 10) ushr 24)
        assertEquals(255, image.getRGB(15, 30) ushr 24)
        assertEquals(255, image.getRGB(45, 10) ushr 24)
        assertEquals("Hidden edges must not be bridged", 0, image.getRGB(15, 20) ushr 24)
        assertEquals("Islands and rings must stay separate", 0, image.getRGB(30, 20) ushr 24)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `reference presentation shows an unmarked overview selected contour and multihop route`() {
        val output = Path.of("build/screenshots/earth-reference"); Files.createDirectories(output)
        for ((name, entry, exit) in listOf(Triple("overview", null, null), Triple("selected", null, "PL"),
            Triple("china", null, "CN"), Triple("america", null, "US"), Triple("multihop", "NL", "PL"))) {
            val renderer = EarthRenderer()
            val scene = ImageComposeScene(width = 1000, height = 610) {
                DesktopTheme(null) {
                    EarthViewport(Modifier.fillMaxSize().background(Color(0xFF101010)), "3D Earth", "Earth failed",
                        renderer = renderer,
                        selectedCountryCode = exit, entryCountryCode = entry, reducedMotion = true)
                }
            }
            try {
                fun loaded(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("3D Earth") == true || node.children.any(::loaded)
                for (frame in 0 until 100) {
                    scene.render().close()
                    if (scene.semanticsOwners.any { loaded(it.rootSemanticsNode) }) break
                    Thread.sleep(10)
                }
                assertTrue("Renderer must load", scene.semanticsOwners.any { loaded(it.rootSemanticsNode) })
                scene.render().close()
                val image = scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    Files.write(output.resolve("$name.png"), data.bytes); ImageIO.read(data.bytes.inputStream())
                } }
                val mint = (0 until 610).sumOf { y -> (0 until 1000).count { x ->
                    val rgb = image.getRGB(x, y); val r = (rgb shr 16) and 255; val g = (rgb shr 8) and 255; val b = rgb and 255
                    g > 170 && g > r * 1.2 && b > r * 1.1
                } }
                when (name) {
                    "overview" -> assertEquals("Overview has no country scatter", 0, mint)
                    "selected", "china", "america" -> assertTrue("Selected country has a thin contour and one dot", mint in 300..6000)
                    "multihop" -> assertTrue("Two points and their route", mint > 300)
                }
            } finally { scene.close(); renderer.close() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `production viewport ignores all earth input and preserves overlay input`() {
        val clicks = AtomicInteger()
        val renderer = EarthRenderer()
        val scene = ImageComposeScene(width = 800, height = 480) {
            DesktopTheme(null) {
                Box(Modifier.fillMaxSize()) {
                    EarthViewport(Modifier.fillMaxSize(), "3D Earth", "Earth failed", renderer = renderer, selectedCountryCode = "NL", reducedMotion = true)
                    PcButton("Overlay", Modifier.padding(10.dp).width(90.dp).height(40.dp), onClick = { clicks.incrementAndGet() })
                }
            }
        }
        try {
            fun loaded(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("3D Earth") == true || node.children.any(::loaded)
            for (frame in 0 until 100) {
                scene.render().close()
                if (scene.semanticsOwners.any { loaded(it.rootSemanticsNode) }) break
                Thread.sleep(10)
            }
            assertTrue("Renderer must load", scene.semanticsOwners.any { loaded(it.rootSemanticsNode) })
            fun image() = scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(data.bytes.inputStream()) } }
            val before = image()
            scene.sendPointerEvent(PointerEventType.Press, Offset(220f, 110f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Move, Offset(190f, 130f), buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, Offset(190f, 130f), button = PointerButton.Primary)
            val rotated = image()
            val changed = (0 until 480).sumOf { y -> (110 until 800).count { x -> before.getRGB(x, y) != rotated.getRGB(x, y) } }
            assertEquals("Dragging must not rotate the model", 0, changed)
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(220f, 110f), scrollDelta = Offset(0f, -2f))
            val zoomed = image()
            val zoomChanges = (0 until 480).sumOf { y -> (110 until 800).count { x -> rotated.getRGB(x, y) != zoomed.getRGB(x, y) } }
            assertEquals("Scrolling must not move the camera", 0, zoomChanges)
            fun earth(node: SemanticsNode): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("3D Earth") == true) node else node.children.firstNotNullOfOrNull(::earth)
            assertNull(requireNotNull(scene.semanticsOwners.firstNotNullOfOrNull { earth(it.rootSemanticsNode) }).config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            val position = Offset(585f, 209.6f)
            scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            val clicked = image()
            assertEquals("Country point is purely illustrative", 0, (0 until 480).sumOf { y -> (110 until 800).count { x -> zoomed.getRGB(x, y) != clicked.getRGB(x, y) } })
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 30f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(50f, 30f), button = PointerButton.Primary)
            image()
            assertEquals("Earth pointer input must not capture controls", 1, clicks.get())
        } finally { scene.close(); renderer.close() }
    }

    @Test fun `export preserves master identity numeric atlas and coordinate convention`() {
        val info = manifest
        assertEquals("72d7b0f20636b7b7fdb6dbe36b670920c7bd23079463600dd06b37f468806a4a", info.getString("masterSha256"))
        val elevation = info.getJSONObject("elevationSource")
        assertEquals("All continents need the same detailed global elevation source", 60, elevation.getInt("arcSeconds"))
        assertEquals(21600, elevation.getJSONArray("size").getInt(0))
        assertEquals(10800, elevation.getJSONArray("size").getInt(1))
        assertEquals("9d27d4b8ea8e76977e2988bca667d7c8fa68b927355feffcddd6b4875a7fd08e", elevation.getString("sourceSha256"))
        val hash = MessageDigest.getInstance("SHA-256").digest(resource("relief.png")).joinToString("") { "%02x".format(it) }
        assertEquals(info.getJSONObject("atlas").getString("sha256"), hash)
        val atlas = ImageIO.read(resource("relief.png").inputStream())
        val sourceCoast = info.getJSONArray("sourceImages").getJSONObject(0).getJSONArray("size")
        assertEquals("Preserve the source coastline resolution", sourceCoast.getInt(0), atlas.width)
        assertEquals(sourceCoast.getInt(1), atlas.height)
        assertEquals(8192, atlas.width); assertEquals(4096, atlas.height)
        // Amsterdam is a material/UV check, never exported as a server coordinate.
        val land = atlas.getRGB(((4.9 + 180) / 360 * atlas.width).toInt(), ((90 - 52.37) / 180 * atlas.height).toInt())
        val ocean = atlas.getRGB((( -30.0 + 180) / 360 * atlas.width).toInt(), ((90 - 0.0) / 180 * atlas.height).toInt())
        assertTrue((land and 255) > 200)
        assertTrue((ocean and 255) < 30)
        assertEquals(255, land ushr 24)
        for (camera in listOf("overview", "close")) {
            val view = info.getJSONObject("cameras").getJSONObject(camera)
            assertEquals("VERTICAL", view.getString("sensorFit"))
            val eye = view.getJSONArray("position"); val forward = view.getJSONArray("forward")
            val right = view.getJSONArray("right"); val up = view.getJSONArray("up")
            fun dot(a: org.json.JSONArray, b: org.json.JSONArray) = (0..2).sumOf { a.getDouble(it) * b.getDouble(it) }
            assertEquals(1.0, dot(forward, forward), 1e-6)
            assertEquals(0.0, dot(right, up), 1e-6)
            assertTrue(dot(eye, forward) < -2.0)
        }
    }

    @Test fun `terrain detail has lit ridges shaded valleys and a restrained matte ocean`() {
        EarthRenderer().use { renderer ->
            val pose = EarthCountryPoint(Math.toRadians(20.0).toFloat(), Math.toRadians(40.0).toFloat(), 1.002f)
                .camera(renderer.overview).copy(distance = 2.35f, shift = Offset.Zero)
            val image = Surface.makeRasterN32Premul(1000, 700).use { surface ->
                surface.canvas.clear(0)
                renderer.draw(surface.canvas, 1000, 700, pose)
                surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(data.bytes.inputStream()) } }
            }
            fun patch(lon: Double, lat: Double): List<Int> {
                val point = EarthCountryPoint(Math.toRadians(lon).toFloat(), Math.toRadians(lat).toFloat(), 1.002f)
                val center = requireNotNull(point.project(pose, 1000f, 700f, renderer.tanFov))
                return (center.y.toInt() - 12..center.y.toInt() + 12).flatMap { y ->
                    (center.x.toInt() - 12..center.x.toInt() + 12).map { x ->
                        val pixel = image.getRGB(x, y)
                        assertEquals("Terrain shadows must remain opaque", 255, pixel ushr 24)
                        pixel and 255
                    }
                }
            }
            val mountains = patch(9.5, 46.5)
            val foreland = patch(20.0, 52.0)
            val ocean = patch(-12.0, 43.0)
            // Allow isolated dark samples, but reject the large black patches reported in the Alps.
            assertTrue("Mountain shadows must not form large black patches", mountains.count { it < 32 } <= mountains.size / 50)
            assertTrue("Relief must resolve lit ridges and shaded valleys", mountains.max() - mountains.min() > 60)
            assertTrue("Ocean must not wash out the continental silhouette", foreland.average() > ocean.average() * 1.8)
            println("Earth detail: Alps range ${mountains.min()}..${mountains.max()}, land ${foreland.average()}, ocean ${ocean.average()}")
        }
    }

    @Test fun `ray marched relief renders both master camera poses with alpha`() {
        val output = Path.of("build/screenshots/earth-probe")
        Files.createDirectories(output)
        EarthRenderer().use { renderer ->
            val images = listOf("overview", "close").map { camera ->
                Surface.makeRasterN32Premul(744, 529).use { surface ->
                    val start = System.nanoTime()
                    surface.canvas.clear(0)
                    renderer.draw(surface.canvas, 744, 529, renderer.camera(camera))
                    surface.makeImageSnapshot().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                            Files.write(output.resolve("$camera.png"), data.bytes)
                            println("Earth probe raster $camera: ${(System.nanoTime() - start) / 1_000_000} ms (CPU; not Direct3D performance)")
                            ImageIO.read(data.bytes.inputStream())
                        }
                    }
                }
            }
            for (image in images) {
                var solid = 0; var empty = 0; var lit = 0; var dark = 0; var partial = 0
                for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                    val pixel = image.getRGB(x, y)
                    if (pixel ushr 24 > 240) solid++ else empty++
                    if (pixel ushr 24 in 1..254) partial++
                    if (pixel ushr 24 > 240 && pixel and 255 > 50) lit++
                    if (pixel ushr 24 > 240 && pixel and 255 < 45) dark++
                }
                assertTrue("A displaced globe must render", solid > 1000)
                assertTrue("Background must remain transparent", empty > 1000)
                assertTrue("Lighting must have distinct lit and shaded terrain", lit > 1000 && dark > 100)
                assertTrue("Silhouette must have subpixel coverage", partial > 30)
            }
            // The master overview fits the whole globe vertically at its VERTICAL sensor fit.
            for (x in 0 until images[0].width) {
                assertEquals(0, images[0].getRGB(x, 0) ushr 24)
                assertEquals(0, images[0].getRGB(x, images[0].height - 1) ushr 24)
            }
            assertNotEquals(images[0].getRGB(372, 264), images[1].getRGB(372, 264))
            for ((index, name) in listOf("overview", "close").withIndex()) {
                val pose = renderer.camera(name)
                val image = images[index]
                for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                    val px = (x + .5 - image.width / 2.0) / image.height + pose.shift.x
                    val py = (image.height / 2.0 - y - .5) / image.height + pose.shift.y
                    val baseRadius = 1 / (2 * renderer.tanFov * kotlin.math.sqrt(pose.distance * pose.distance - 1))
                    val insidePixels = (baseRadius - kotlin.math.sqrt(px * px + py * py)) * image.height
                    if (insidePixels > 2)
                        assertEquals("Grazing rays inside the base sphere must not leave holes", 255, image.getRGB(x, y) ushr 24)
                }
            }
            assertEquals(2, renderer.generation)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `relief participates in the Kyant backdrop capture`() {
        val output = Path.of("build/screenshots/earth-probe")
        Files.createDirectories(output)
        fun render(captureEarth: Boolean): java.awt.image.BufferedImage {
            EarthRenderer().use { renderer ->
                val scene = ImageComposeScene(width = 744, height = 529)
                try {
                    scene.setContent {
                        val backdrop = rememberLayerBackdrop()
                        DesktopTheme(backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().background(Color.Black).layerBackdrop(backdrop))
                                Canvas(Modifier.fillMaxSize().then(if (captureEarth) Modifier.layerBackdrop(backdrop) else Modifier)) {
                                    drawIntoCanvas { renderer.draw(it.skiaCanvas, size.width.toInt(), size.height.toInt(), renderer.overview) }
                                }
                                PcButton("Стекло над рельефом", Modifier.padding(start = 330.dp, top = 230.dp).width(220.dp).height(65.dp), glass = true, glassColor = DesktopUiKit.Glass, onClick = {})
                            }
                        }
                    }
                    return scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                        Files.write(output.resolve(if (captureEarth) "glass.png" else "empty-capture.png"), data.bytes)
                        ImageIO.read(data.bytes.inputStream())
                    } }
                } finally { scene.close() }
            }
        }
        val empty = render(false); val glass = render(true)
        assertEquals(empty.getRGB(250, 150), glass.getRGB(250, 150))
        var changed = 0
        for (y in 245 until 280) for (x in 345 until 525) if (empty.getRGB(x, y) != glass.getRGB(x, y)) changed++
        assertTrue("Identical button must sample Earth only when it is in the backdrop capture", changed > 500)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `Direct3D host retains input resize and visibility with a stable scene filter during metric redraw`() {
        org.junit.Assume.assumeTrue("Requires explicit permission to resize windows and send input", System.getenv("NOKI_INTERACTIVE_TESTS") == "1")
        val clicks = AtomicInteger(); val painted = AtomicInteger(); val submissions = AtomicInteger()
        val firstPaint = java.util.concurrent.CountDownLatch(1)
        val metric = mutableStateOf(0)
        val camera = mutableStateOf("overview")
        var window: androidx.compose.ui.awt.ComposeWindow? = null
        val renderer = EarthRenderer()
        fun layer(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? =
            if (component is org.jetbrains.skiko.SkiaLayer) component else (component as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
        fun settle() { repeat(20) { SwingUtilities.invokeAndWait {}; Thread.sleep(25) } }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(10)
            }
        }
        fun press(x: Int, y: Int) {
            val previous = clicks.get()
            SwingUtilities.invokeAndWait {
                val target = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(requireNotNull(layer(requireNotNull(window).contentPane)).canvas))
                val scale = requireNotNull(window).graphicsConfiguration.defaultTransform.scaleX
                val point = com.sun.jna.platform.win32.WinDef.LPARAM(((y * scale).toLong() shl 16) or (x * scale).toLong())
                com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(target, 0x200, com.sun.jna.platform.win32.WinDef.WPARAM(0), point)
                com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(target, 0x201, com.sun.jna.platform.win32.WinDef.WPARAM(1), point)
                com.sun.jna.platform.win32.User32.INSTANCE.PostMessage(target, 0x202, com.sun.jna.platform.win32.WinDef.WPARAM(0), point)
            }
            await { clicks.get() > previous && painted.get() == metric.value }
        }
        try {
            SwingUtilities.invokeAndWait {
                window = androidx.compose.ui.awt.ComposeWindow().apply {
                    isUndecorated = true; setSize(744, 529)
                    setContent {
                        val backdrop = rememberLayerBackdrop()
                        DesktopTheme(backdrop) {
                            Box(Modifier.fillMaxSize()) {
                                Canvas(Modifier.fillMaxSize().background(DesktopUiKit.Canvas).layerBackdrop(backdrop)) {
                                    drawIntoCanvas { renderer.draw(it.skiaCanvas, size.width.toInt(), size.height.toInt(), renderer.camera(camera.value)); submissions.incrementAndGet() }
                                }
                                Column {
                                    val action = { metric.value = clicks.incrementAndGet() }
                                    PcTitlebar(onMinimize = action, onMaximize = action, onClose = action)
                                    PcButton("Metric ${metric.value}", Modifier.padding(start = 320.dp, top = 160.dp).width(260.dp).height(65.dp).drawWithContent {
                                        drawContent(); painted.set(metric.value); firstPaint.countDown()
                                    }, glass = true, onClick = action)
                                }
                            }
                        }
                    }
                    isVisible = true
                }
            }
            assertTrue("The Earth host and overlay must paint before sending input", firstPaint.await(5, java.util.concurrent.TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { assertEquals(org.jetbrains.skiko.GraphicsApi.DIRECT3D, requireNotNull(window).renderApi) }
            val previous = clicks.get(); press(420, 230)
            assertTrue("Click above 3D must reach glass button", clicks.get() > previous)
            assertEquals("Click must repaint without maximize", clicks.get(), painted.get())
            for (x in listOf(744 - 108, 744 - 66, 744 - 24)) {
                val previous = clicks.get(); press(x, 20)
                assertTrue("Caption click at $x must remain live", clicks.get() > previous)
            }
            val beforeMetric = renderer.generation
            SwingUtilities.invokeAndWait { metric.value += 1 }
            await { painted.get() == metric.value }
            assertEquals("Metric updates must repaint", metric.value, painted.get())
            assertEquals("Metric updates keep the same cached scene filter", beforeMetric, renderer.generation)
            println("Earth host: Direct3D, opaque input and caption PASS; metric updates keep scene filter (not a GPU timing measurement)")
            SwingUtilities.invokeAndWait { requireNotNull(window).setSize(900, 600) }; settle()
            val beforeHide = submissions.get()
            SwingUtilities.invokeAndWait { requireNotNull(window).isVisible = false }; settle()
            val hidden = submissions.get()
            SwingUtilities.invokeAndWait { camera.value = "close" }; settle()
            assertEquals("No scene loop while hidden", hidden, submissions.get())
            SwingUtilities.invokeAndWait { requireNotNull(window).isVisible = true }; settle()
            assertTrue("Resume must paint current viewport", submissions.get() > beforeHide)
            val idle = submissions.get(); settle()
            assertEquals("No periodic scene work at rest", idle, submissions.get())
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose(); renderer.close() }
        }
    }
}

/** Own fixture windows only: no account profile, VPN runtime or input automation. */
internal object EarthMemoryProbe {
    @JvmStatic fun main(args: Array<String>) {
        val output = Path.of(args[0])
        if (DesktopBuild.IS_LINUX) LinuxPlatform::class.java.getDeclaredField("key").apply { isAccessible = true }.set(null, ByteArray(32))
        val vault = WindowsVault(Files.createTempDirectory(Path.of("build"), "earth-memory-"), hardwareIdentity = { "fixture" })
        val app = DesktopController(vault, SingBoxRuntime(vault), com.noki.vpn.data.BackendApiClient(baseUrl = "http://127.0.0.1:9"))
        val user = com.noki.vpn.data.BackendUser("fixture", "Fixture", "fixture@example.invalid", null, true, false)
        var gpu: dev.nucleusframework.window.tao.TaoOpenGlRenderContext? = null
        fun sample(phase: String) {
            val row = JSONObject().put("phase", phase)
            if (DesktopBuild.IS_LINUX) {
                val values = Files.readAllLines(Path.of("/proc/self/smaps_rollup")).filter { ':' in it }
                    .associate { it.substringBefore(':') to it.substringAfter(':').trim().substringBefore(' ').toLongOrNull() }
                row.put("rss", checkNotNull(values["Rss"]) * 1024)
                    .put("private", ((values["Private_Clean"] ?: 0) + (values["Private_Dirty"] ?: 0)) * 1024)
            } else {
                // JNA 5.17 lacks PROCESS_MEMORY_COUNTERS_EX; this probe uses the Windows x64 layout.
                check(com.sun.jna.Native.POINTER_SIZE == 8)
                val counters = com.sun.jna.Memory(80).apply { clear(); setInt(0, 80) }
                check(com.sun.jna.Function.getFunction("psapi", "GetProcessMemoryInfo", com.sun.jna.Function.ALT_CONVENTION)
                    .invokeInt(arrayOf(com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(), counters, 80)) != 0)
                row.put("rss", counters.getLong(16)).put("private", counters.getLong(72))
                counters.close()
            }
            val heap = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
            row.put("heap_used", heap.used).put("heap_committed", heap.committed)
                .put("skia_resource_used", Graphics.resourceCacheTotalUsed).put("skia_resource_limit", Graphics.resourceCacheTotalLimit)
                .put("skia_font_used", Graphics.fontCacheUsed)
            gpu?.withContextCurrent { row.put("gpu_cache_limit", gpu?.skiaContext?.resourceCacheLimit) }
            Files.writeString(output, row.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        }
        dev.nucleusframework.application.nucleusApplication(backend = dev.nucleusframework.application.NucleusBackend.Tao,
            enableSingleInstance = false) {
            var earth by remember { mutableStateOf(false) }
            var page by remember { mutableStateOf(DesktopPage.HOME) }
            var country by remember { mutableStateOf("") }
            var signedIn by remember { mutableStateOf(true) }
            val base = DesktopState(user = if (signedIn) user else null, earthDisabled = !earth)
            val state = base.copy(settings = base.settings.copy(userProfile = base.settings.userProfile.copy(
                serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.COUNTRY, selectedCountryCode = country)))
            dev.nucleusframework.application.DecoratedWindow(onCloseRequest = {}, title = "Noki memory probe",
                hiddenFromDock = true, state = androidx.compose.ui.window.rememberWindowState(width = 1050.dp, height = 674.dp,
                    position = androidx.compose.ui.window.WindowPosition(-2000.dp, -2000.dp))) {
                val context = dev.nucleusframework.window.tao.rememberTaoGpuRenderContext() as? dev.nucleusframework.window.tao.TaoOpenGlRenderContext
                SideEffect { gpu = context }
                key(page) { DesktopContent(state, app, initialPage = page) }
                LaunchedEffect(Unit) {
                    try {
                        kotlinx.coroutines.delay(5_000); System.gc(); kotlinx.coroutines.delay(1_000); sample("map")
                        earth = true
                        kotlinx.coroutines.delay(15_000); sample("earth-active")
                        System.gc(); kotlinx.coroutines.delay(1_000); sample("earth-after-gc")
                        kotlinx.coroutines.delay(10_000); sample("earth-active-later")
                        for (code in listOf("NL", "US", "PL", "FI")) { country = code; kotlinx.coroutines.delay(2_000) }
                        sample("earth-after-flights")
                        page = DesktopPage.SECURITY
                        kotlinx.coroutines.delay(3_000); System.gc(); kotlinx.coroutines.delay(1_000); sample("settings")
                        earth = false
                        kotlinx.coroutines.delay(3_000); System.gc(); kotlinx.coroutines.delay(1_000); sample("map-after-earth")
                        page = DesktopPage.HOME; earth = true
                        kotlinx.coroutines.delay(3_000)
                        signedIn = false
                        kotlinx.coroutines.delay(3_000); System.gc(); kotlinx.coroutines.delay(1_000); sample("logout")
                        check(gpu?.skiaContext?.resourceCacheLimit == 32L * 1024 * 1024)
                        exitApplication()
                    } catch (error: Throwable) { error.printStackTrace(); kotlin.system.exitProcess(1) }
                }
            }
        }
        app.close()
        kotlin.system.exitProcess(0)
    }
}

internal object EarthGpuLifecycleProbe {
    @JvmStatic fun main(args: Array<String>) {
        val contexts = mutableMapOf<Int, dev.nucleusframework.window.tao.TaoOpenGlRenderContext>()
        val frames = mutableMapOf<Int, Int>()
        var renderer: EarthRenderer? = null
        var atlas: Image? = null
        var display: com.sun.jna.Pointer? = null
        fun native(name: String) = com.sun.jna.Function.getFunction("nucleus_tao_gl", name)
        fun egl(name: String) = com.sun.jna.Function.getFunction("libEGL", name, com.sun.jna.Function.ALT_CONVENTION)
        fun displayInitialized() = egl("eglQueryString").invokePointer(arrayOf(checkNotNull(display), 0x3054)) != null
        suspend fun await(label: String, condition: () -> Boolean) {
            kotlinx.coroutines.withTimeout(15_000) {
                while (!condition()) kotlinx.coroutines.delay(20)
            }
            println("earth-gpu-lifecycle: $label")
        }
        dev.nucleusframework.application.nucleusApplication(backend = dev.nucleusframework.application.NucleusBackend.Tao,
            enableSingleInstance = false) {
            var first by remember { mutableStateOf(true) }
            var second by remember { mutableStateOf(true) }
            var earth by remember { mutableStateOf(true) }
            var redraw by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                try {
                    await("both windows paint") { renderer?.generation?.let { it > 0 } == true && frames.getOrDefault(1, 0) > 0 }
                    check(displayInitialized())
                    val initialAtlas = checkNotNull(atlas)
                    earth = false
                    await("Earth closes on map switch") { initialAtlas.isClosed && renderer == null }
                    first = false
                    await("first context closes") { contexts.getValue(0).skiaContext.isClosed }
                    check(displayInitialized()) { "Closing one window terminated its sibling's EGL display" }
                    val previous = frames.getValue(1)
                    redraw++
                    await("sibling still paints") { frames.getValue(1) > previous }
                    second = false
                    await("last context closes") { contexts.values.all { it.skiaContext.isClosed } }
                    check(!displayInitialized()) { "Last non-headless window retained the initialized ANGLE display" }

                    val closedContext = contexts.getValue(0)
                    val previousFirst = frames.getValue(0)
                    first = true
                    await("map restores after display termination") {
                        contexts[0] !== closedContext && contexts[0]?.skiaContext?.isClosed == false && frames.getValue(0) > previousFirst
                    }
                    check(displayInitialized())
                    earth = true
                    await("Earth restores after display termination") { renderer?.generation?.let { it > 0 } == true }
                    val restoredAtlas = checkNotNull(atlas)
                    first = false
                    await("restored resources close") { restoredAtlas.isClosed && contexts.getValue(0).skiaContext.isClosed }
                    check(!displayInitialized())

                    val bridge = Class.forName("dev.nucleusframework.window.tao.ffi.NativeTaoGlBridge")
                    val ensureHeadless = bridge.declaredMethods.single { it.name == "nativeEnsureHeadlessContext" }
                    check(ensureHeadless.invoke(bridge.getDeclaredField("INSTANCE").get(null)) == true)
                    display = native("nucleus_tao_host_egl_display").invokePointer(emptyArray<Any>())
                    val headless = checkNotNull(native("nucleus_tao_host_egl_context").invokePointer(emptyArray<Any>()))
                    check(displayInitialized())
                    val previousContext = contexts.getValue(0)
                    earth = false
                    first = true
                    await("window opens beside headless context") { contexts[0] !== previousContext && contexts[0]?.skiaContext?.isClosed == false }
                    first = false
                    await("last window closes beside headless context") { contexts.getValue(0).skiaContext.isClosed }
                    check(displayInitialized()) { "Last window terminated a display owned by the headless context" }
                    val version = com.sun.jna.ptr.IntByReference()
                    check(egl("eglQueryContext").invokeInt(arrayOf(checkNotNull(display), headless, 0x3098, version)) != 0)
                    check(version.value >= 2)
                    println("earth-gpu-lifecycle: ok")
                    exitApplication()
                } catch (error: Throwable) {
                    error.printStackTrace()
                    kotlin.system.exitProcess(1)
                }
            }
            for (id in listOfNotNull(0.takeIf { first }, 1.takeIf { second })) key(id) {
                dev.nucleusframework.application.DecoratedWindow(onCloseRequest = {}, title = "Noki isolated Earth lifecycle",
                    hiddenFromDock = true, state = androidx.compose.ui.window.rememberWindowState(width = 600.dp, height = 450.dp,
                        position = androidx.compose.ui.window.WindowPosition(-2000.dp, -2000.dp))) {
                    val active by rememberEarthRenderer(id == 0 && earth)
                    val gpu = dev.nucleusframework.window.tao.rememberTaoGpuRenderContext() as? dev.nucleusframework.window.tao.TaoOpenGlRenderContext
                    SideEffect {
                        if (gpu != null) {
                            contexts[id] = gpu
                            display = native("nucleus_tao_host_egl_display").invokePointer(emptyArray<Any>())
                        }
                        if (id == 0) {
                            renderer = active?.getOrNull()
                            renderer?.let { current -> atlas = EarthRenderer::class.java.getDeclaredField("image")
                                .apply { isAccessible = true }.get(current) as Image }
                        }
                    }
                    Box(Modifier.fillMaxSize().background(DesktopUiKit.Canvas).drawWithContent {
                        redraw
                        drawContent()
                        frames[id] = frames.getOrDefault(id, 0) + 1
                    }) {
                        if (id == 0 && earth) EarthViewport(Modifier.fillMaxSize(), "Earth", "Failed", active?.getOrNull(),
                            selectedCountryCode = "NL", reducedMotion = true)
                        else CountryMapViewport(Modifier.fillMaxSize(), "NL", null, "Map")
                    }
                }
            }
        }
    }
}
