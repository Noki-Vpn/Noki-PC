package com.noki.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.random.Random
import kotlinx.coroutines.delay

internal data class EarthCamera(
    val longitude: Float,
    val latitude: Float,
    val distance: Float,
    val shift: Offset,
) {
    companion object {
        const val OVERVIEW_DISTANCE = 5.6f
        const val CLOSE_DISTANCE = 2.2f
        fun fromManifest(view: JSONObject): EarthCamera {
            val eye = view.getJSONArray("position")
            val x = eye.getDouble(0); val y = eye.getDouble(1); val z = eye.getDouble(2)
            val distance = sqrt(x * x + y * y + z * z)
            return EarthCamera(atan2(y, x).toFloat(), asin(z / distance).toFloat(), distance.toFloat(),
                Offset(view.getJSONArray("shift").getDouble(0).toFloat(), view.getJSONArray("shift").getDouble(1).toFloat()))
        }
    }
}

private data class EarthVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: EarthVector) = EarthVector(x + b.x, y + b.y, z + b.z)
    operator fun times(s: Double) = EarthVector(x * s, y * s, z * s)
    fun cross(b: EarthVector) = EarthVector(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    val length get() = sqrt(x * x + y * y + z * z)
}

private data class EarthRotation(val w: Double, val v: EarthVector) {
    operator fun times(b: EarthRotation) = EarthRotation(w * b.w - (v.x * b.v.x + v.y * b.v.y + v.z * b.v.z), b.v * w + v * b.w + v.cross(b.v))
    fun inverse() = EarthRotation(w, v * -1.0)
    fun log(): EarthVector {
        val sign = if (w < 0) -1.0 else 1.0
        return if (v.length < 1e-10) v * (2 * sign) else v * (2 * atan2(v.length, w * sign) / v.length * sign)
    }
    fun radial(): EarthVector = EarthVector(0.0, 0.0, 1.0).let { it + v.cross(it) * (2 * w) + v.cross(v.cross(it)) * 2.0 }
    companion object {
        fun exp(v: EarthVector): EarthRotation {
            val a = v.length
            return EarthRotation(cos(a / 2), v * if (a < 1e-10) .5 else sin(a / 2) / a)
        }
        fun camera(p: EarthCamera) = exp(EarthVector(0.0, 0.0, p.longitude.toDouble())) * exp(EarthVector(0.0, PI / 2 - p.latitude, 0.0))
    }
}

/** One elapsed-time flight. Analytic velocities survive retargeting; north-up is enforced at projection. */
internal class EarthFlight(initial: EarthCamera) {
    companion object { const val DURATION_NANOS = 1_500_000_000L }
    private data class Sample(val camera: EarthCamera, val angular: EarthVector, val distanceSpeed: Double, val shiftSpeed: Offset)
    private var rest = Sample(initial, EarthVector(0.0, 0.0, 0.0), 0.0, Offset.Zero)
    private var from = rest
    private var start = 0L
    private var rotation = EarthRotation.camera(initial)
    private var delta = EarthVector(0.0, 0.0, 0.0)
    var target = initial
        private set
    var moving = false
        private set

    fun retarget(next: EarthCamera, now: Long, immediate: Boolean = false) {
        if (next == target && !immediate) return
        from = sample(now)
        target = next
        start = now
        rotation = EarthRotation.camera(from.camera)
        delta = (EarthRotation.camera(next) * rotation.inverse()).log()
        moving = !immediate && from.camera != next
        if (!moving) rest = Sample(next, EarthVector(0.0, 0.0, 0.0), 0.0, Offset.Zero)
    }

    fun camera(now: Long): EarthCamera = sample(now).camera

    private fun sample(now: Long): Sample {
        if (!moving) return rest
        val elapsed = (now - start).coerceAtLeast(0) / 1e9
        if (elapsed >= 1.5) {
            moving = false
            rest = Sample(target, EarthVector(0.0, 0.0, 0.0), 0.0, Offset.Zero)
            return rest
        }
        fun hermite(a: Double, b: Double, velocity: Double, duration: Double = 1.5): Pair<Double, Double> {
            val t = (elapsed / duration).coerceIn(0.0, 1.0); val t2 = t * t; val t3 = t2 * t
            return ((2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * velocity * duration + (-2 * t3 + 3 * t2) * b) to
                (((6 * t2 - 6 * t) * a + (3 * t2 - 4 * t + 1) * velocity * duration + (-6 * t2 + 6 * t) * b) / duration)
        }
        val x = hermite(0.0, delta.x, from.angular.x); val y = hermite(0.0, delta.y, from.angular.y); val z = hermite(0.0, delta.z, from.angular.z)
        val rv = EarthVector(x.first, y.first, z.first); val speed = EarthVector(x.second, y.second, z.second)
        val angle = rv.length
        // SO(3) left Jacobian: true angular velocity, independent of the previous frame schedule.
        val omega = if (angle < 1e-6) speed + rv.cross(speed) * .5 + rv.cross(rv.cross(speed)) * (1.0 / 6) else
            speed + rv.cross(speed) * ((1 - cos(angle)) / (angle * angle)) + rv.cross(rv.cross(speed)) * ((angle - sin(angle)) / (angle * angle * angle))
        val radial = (EarthRotation.exp(rv) * rotation).radial()
        val radialSpeed = omega.cross(radial)
        val lon = atan2(radial.y, radial.x); val lat = asin(radial.z.coerceIn(-1.0, 1.0))
        val cosLat = cos(lat).coerceAtLeast(1e-8)
        val lonSpeed = (radial.x * radialSpeed.y - radial.y * radialSpeed.x) / (cosLat * cosLat)
        val latSpeed = radialSpeed.z / cosLat
        val difference = target.distance - from.camera.distance
        // Settle a zoom early when necessary to preserve velocity without crossing its framing bounds.
        val margin = if (from.distanceSpeed < 0) from.camera.distance - EarthCamera.CLOSE_DISTANCE else
            maxOf(EarthCamera.OVERVIEW_DISTANCE, from.camera.distance, target.distance) - from.camera.distance
        val distanceDuration = when {
            difference * from.distanceSpeed > 0 -> minOf(1.5, 3 * abs(difference / from.distanceSpeed))
            from.distanceSpeed != 0.0 -> minOf(1.5, 6 * margin.coerceAtLeast(0f) / abs(from.distanceSpeed))
            else -> 1.5
        }
        val distance = hermite(from.camera.distance.toDouble(), target.distance.toDouble(), from.distanceSpeed, distanceDuration.coerceAtLeast(1e-8))
        val sx = hermite(from.camera.shift.x.toDouble(), target.shift.x.toDouble(), from.shiftSpeed.x.toDouble())
        val sy = hermite(from.camera.shift.y.toDouble(), target.shift.y.toDouble(), from.shiftSpeed.y.toDouble())
        return Sample(EarthCamera(lon.toFloat(), lat.toFloat(), distance.first.toFloat(), Offset(sx.first.toFloat(), sy.first.toFloat())),
            EarthVector(latSpeed * sin(lon), -latSpeed * cos(lon), lonSpeed), distance.second, Offset(sx.second.toFloat(), sy.second.toFloat()))
    }
}

internal data class EarthCountryPoint(val longitude: Float, val latitude: Float, val radius: Float) {
    private fun radial() = EarthVector(cos(latitude.toDouble()) * cos(longitude.toDouble()), cos(latitude.toDouble()) * sin(longitude.toDouble()), sin(latitude.toDouble()))

    fun routeTo(exit: EarthCountryPoint): List<EarthCountryPoint> {
        val a = radial(); val b = exit.radial()
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0)
        val angle = acos(dot)
        val tangent = (b + a * -dot).let {
            if (it.length > 1e-7) it * (1 / it.length) else
                a.cross(if (abs(a.z) < .9) EarthVector(0.0, 0.0, 1.0) else EarthVector(1.0, 0.0, 0.0)).let { v -> v * (1 / v.length) }
        }
        return (0..64).map { step ->
            when (step) {
                0 -> this
                64 -> exit
                else -> {
                    val t = step / 64.0
                    val p = a * cos(angle * t) + tangent * sin(angle * t)
                    EarthCountryPoint(atan2(p.y, p.x).toFloat(), asin(p.z.coerceIn(-1.0, 1.0)).toFloat(),
                        (radius * (1 - t) + exit.radius * t + .016 * sin(PI * t)).toFloat())
                }
            }
        }
    }

    fun camera(overview: EarthCamera, route: List<EarthCountryPoint> = emptyList(), tanLimit: Float = .296f): EarthCamera {
        if (route.isEmpty()) return overview.copy(longitude = longitude, latitude = latitude, distance = EarthCamera.CLOSE_DISTANCE)
        val center = route[route.size / 2]
        val a = radial(); val b = center.radial()
        val facing = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(0.0, 1.0)
        val distance = maxOf(EarthCamera.CLOSE_DISTANCE.toDouble(),
            1.016 * (facing + sqrt(1 - facing * facing) / tanLimit.coerceAtLeast(.02f)), 1.016 / facing.coerceAtLeast(.1) + .05)
        return overview.copy(longitude = center.longitude, latitude = center.latitude, distance = distance.toFloat())
    }

    fun project(camera: EarthCamera, width: Float, height: Float, tanFov: Float): Offset? {
        if (width <= 0 || height <= 0) return null
        val lon = camera.longitude; val lat = camera.latitude
        val facing = cos(latitude) * cos(lat) * cos(longitude - lon) + sin(latitude) * sin(lat)
        // Conservative terrain-horizon test; never expose a marker on the far side.
        if (facing <= 1.016f / camera.distance) return null
        val depth = camera.distance - radius * facing
        val x = radius * cos(latitude) * sin(longitude - lon)
        val y = radius * (-sin(lat) * cos(latitude) * cos(longitude - lon) + cos(lat) * sin(latitude))
        return Offset(width / 2 + height / 2 * (x / depth / tanFov - 2 * camera.shift.x),
            height / 2 - height / 2 * (y / depth / tanFov - 2 * camera.shift.y))
    }
}

/** One displaced 3D surface, rendered by the existing Skia backend; no second HWND/context. */
internal class EarthRenderer(atlasWidth: Int = 4096) : AutoCloseable {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/noki/desktop/earth/$name")) { name }.use { it.readBytes() }
    private val info = JSONObject(resource("manifest.json").toString(Charsets.UTF_8))
    private var geography: CountryGeography? = CountryGeography()
    // Decode in the existing background loader; lazy PNG decoding would block the first UI frame.
    private val image = Data.makeFromBytes(resource(when (atlasWidth) {
        4096 -> "relief-4k.png"
        8192 -> "relief.png"
        else -> error("Unsupported Earth atlas size")
    })).use { data ->
        Codec.makeFromData(data).use { codec ->
            Bitmap().use { bitmap ->
                check(bitmap.allocPixels(codec.imageInfo)) { "Could not allocate Earth atlas" }
                codec.readPixels(bitmap)
                bitmap.setImmutable()
                Image.makeFromBitmap(bitmap)
            }
        }
    }
    private val relief = try {
        image.makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE))
    } catch (error: Throwable) { image.close(); throw error }
    private val effect = try {
        RuntimeEffect.makeForShader(resource("earth.sksl").toString(Charsets.UTF_8))
    } catch (error: Throwable) { relief.close(); image.close(); throw error }
    private val paint = Paint()
    private var filter: ImageFilter? = null
    private var key: Triple<Int, Int, EarthCamera>? = null
    private var glowColor = Color.Unspecified
    internal var generation = 0
        private set
    val overview = camera("overview").copy(distance = EarthCamera.OVERVIEW_DISTANCE, shift = Offset.Zero)
    val tanFov = tan(info.getJSONObject("cameras").getJSONObject("overview").getDouble("fovY") / 2).toFloat()
    private val lightInView = info.getJSONObject("cameras").getJSONObject("overview").let { view ->
        val keyLight = info.getJSONArray("keyDirection")
        listOf("right", "up", "forward").map { basis ->
            (0..2).sumOf { keyLight.getDouble(it) * view.getJSONArray(basis).getDouble(it) }.toFloat()
        }
    }
    val countries get() = geography?.countries.orEmpty()
    fun outline(countryCode: String) = geography?.outlines?.get(countryCode).orEmpty()
    fun camera(name: String) = EarthCamera.fromManifest(info.getJSONObject("cameras").getJSONObject(name))

    fun draw(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, camera: EarthCamera, atmosphere: Color = DesktopUiKit.Accent) {
        if (width <= 0 || height <= 0) return
        val next = Triple(width, height, camera)
        if (key != next || glowColor != atmosphere) {
            val lon = camera.longitude; val lat = camera.latitude
            val forward = floatArrayOf(-cos(lat) * cos(lon), -cos(lat) * sin(lon), -sin(lat))
            val right = floatArrayOf(-sin(lon), cos(lon), 0f)
            val up = floatArrayOf(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat))
            val atlas = info.getJSONObject("atlas")
            val material = info.getJSONObject("material")
            val values = floatArrayOf(
                width.toFloat(), height.toFloat(),
                -forward[0] * camera.distance, -forward[1] * camera.distance, -forward[2] * camera.distance,
                right[0], right[1], right[2],
                up[0], up[1], up[2],
                forward[0], forward[1], forward[2],
                right[0] * lightInView[0] + up[0] * lightInView[1] + forward[0] * lightInView[2],
                right[1] * lightInView[0] + up[1] * lightInView[1] + forward[1] * lightInView[2],
                right[2] * lightInView[0] + up[2] * lightInView[1] + forward[2] * lightInView[2],
                tanFov,
                camera.shift.x, camera.shift.y,
                image.width.toFloat(), image.height.toFloat(), atlas.getDouble("heightRange").toFloat(),
                material.getDouble("landLinear").toFloat(), material.getDouble("oceanLinear").toFloat(),
                material.getDouble("roughness").toFloat(), material.getDouble("rimIor").toFloat(),
                atmosphere.red, atmosphere.green, atmosphere.blue, atmosphere.alpha,
            )
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
            values.forEach(buffer::putFloat)
            val replacement = Data.makeFromBytes(buffer.array()).use { data ->
                effect.makeShader(data, arrayOf(relief), null).use { shader ->
                    // Source-independent Skia filter caches its rendered surface by filter/CTM/clip.
                    ImageFilter.makeShader(shader, false, IRect.makeWH(width, height))
                }
            }
            paint.imageFilter = replacement
            filter?.close(); filter = replacement; key = next; glowColor = atmosphere; generation++
        }
        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)
    }

    override fun close() {
        paint.close(); filter?.close(); filter = null; effect.close(); relief.close(); image.close()
        geography = null
    }
}

internal fun earthPath(parts: List<List<Offset?>>): ComposePath = ComposePath().apply {
    for (part in parts) {
        var connected = false
        for (position in part) {
            if (position != null) {
                if (connected) lineTo(position.x, position.y) else moveTo(position.x, position.y)
            }
            connected = position != null
        }
    }
}

internal fun EarthRenderer.viewportCamera(width: Float, height: Float, point: EarthCountryPoint?, route: List<EarthCountryPoint>): EarthCamera {
    val controlEnd = 110f + minOf(DesktopUiKit.HomePanelWidth.value, width - 130f) + 12f
    val left = if (width - controlEnd >= 180f) controlEnd else 110f
    val freeSize = minOf(width - left, height - 40f)
    val tanLimit = tanFov * (freeSize - 48f).coerceAtLeast(24f) / height.coerceAtLeast(1f)
    val pose = point?.camera(overview, route, tanLimit) ?: overview
    val near = ((EarthCamera.CLOSE_DISTANCE + .45f - pose.distance) / .45f).coerceIn(0f, 1f)
    val fitted = if (near == 0f) pose.copy(distance = maxOf(pose.distance, 1.016f * sqrt(1f + 1f / (tanLimit * tanLimit)))) else pose
    val angled = fitted.copy(longitude = fitted.longitude + Math.toRadians(10.0).toFloat() * near,
        latitude = (fitted.latitude - Math.toRadians(18.0).toFloat() * near).coerceIn(-1.52f, 1.52f))
    val scale = minOf(width / 1000f, height / 610f).coerceIn(.8f, 1.7f)
    val centerX = (left + width) / 2f
    // Close views keep the selected point's position; whole-globe views use the body below the header.
    val centerY = (height + 40f) / 2f * (1f - near) + (20f + height - 101f * scale) / 2f * near
    val focus = (route.getOrNull(route.size / 2) ?: point)?.project(angled, width, height, tanFov)
        ?: Offset(width / 2f, height / 2f)
    return angled.copy(shift = Offset((focus.x - centerX) / height.coerceAtLeast(1f),
        (centerY - focus.y) / height.coerceAtLeast(1f)))
}

@Composable internal fun EarthViewport(
    modifier: Modifier,
    description: String,
    failureMessage: String,
    renderer: EarthRenderer?,
    failedToLoad: Boolean = false,
    selectedCountryCode: String? = null,
    entryCountryCode: String? = null,
    visible: Boolean = true,
    reducedMotion: Boolean = false,
) {
    var camera by remember { mutableStateOf<EarthCamera?>(null) }
    var failed by remember(renderer) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val active = renderer
    val flight = remember(active) { active?.let { EarthFlight(it.overview) } }
    var presentedSize by remember(active) { mutableStateOf<DpSize?>(null) }
    val point = active?.countries?.get(selectedCountryCode?.uppercase())
    val entry = active?.countries?.get(entryCountryCode?.uppercase())
    val outlines = remember(active, selectedCountryCode, entryCountryCode) {
        listOfNotNull(selectedCountryCode, entryCountryCode).map { it.uppercase() }.distinct()
            .flatMap { active?.outline(it).orEmpty() }
    }
    val route = remember(entry, point) { if (entry != null && point != null && entry != point) entry.routeTo(point) else emptyList() }
    BoxWithConstraints(modifier.onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
        val target = active?.viewportCamera(maxWidth.value, maxHeight.value, point ?: entry, route)
        val targetSize = DpSize(maxWidth, maxHeight)
        LaunchedEffect(flight, target, visible, reducedMotion, viewport) {
            if (flight == null || target == null || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
            // Window geometry follows the resize immediately; only location changes fly.
            if (presentedSize != targetSize || reducedMotion || !visible) {
                flight.retarget(target, System.nanoTime(), immediate = true)
                camera = target
            } else {
                withFrameNanos { now -> flight.retarget(target, now); camera = flight.camera(now) }
            }
            presentedSize = targetSize
            while (flight.moving) withFrameNanos { now -> camera = flight.camera(now) }
        }
        val aligned = camera
        val contour = remember(outlines, aligned, viewport) {
            if (active == null || aligned == null) ComposePath() else earthPath(outlines.map { ring ->
                ring.map { it.project(aligned, viewport.width.toFloat(), viewport.height.toFloat(), active.tanFov) }
            })
        }
        if (active != null && aligned != null && visible && !failed) {
            val accent = LocalDesktopStyle.current.accent
            val spaceTick = remember { mutableIntStateOf(0) }
            LaunchedEffect(reducedMotion) {
                if (reducedMotion) spaceTick.intValue = 0 else while (true) {
                    // Ten draw-only updates per second; leaving this visible branch cancels the timer.
                    delay(100)
                    spaceTick.intValue = (spaceTick.intValue + 1) % 20_480
                }
            }
            Canvas(Modifier.matchParentSize().drawWithCache {
                val random = Random(0x4E4F4B49)
                fun stars(columns: Int, rows: Int) = List(columns * rows) { index ->
                    Offset((index % columns + .1f + random.nextFloat() * .8f) * size.width / columns,
                        (index / columns + .1f + random.nextFloat() * .8f) * size.height / rows)
                }
                val distant = stars(16, 10)
                val middle = stars(10, 8)
                val bright = stars(6, 4)
                onDrawBehind {
                    // Half-speed motion repeats after 2048 seconds without accumulated rounding drift.
                    val time = spaceTick.intValue / 20f
                    val pulse = time * (2f * PI.toFloat() / 1024f)
                    drawRect(Color(0xFF101112))
                    // Two adjacent tiles keep the same density through each layer's wrap.
                    for (tile in 0..1) {
                        translate(size.width * (tile - time / 1024f)) {
                            drawPoints(distant, PointMode.Points, Color(0xFFC8D0CE).copy(alpha = .4f), 1.15.dp.toPx(), StrokeCap.Round)
                        }
                        translate(size.width * (tile - (time % 512f) / 512f)) {
                            drawPoints(middle, PointMode.Points, Color.White.copy(alpha = .5f), 1.45.dp.toPx(), StrokeCap.Round)
                        }
                        translate(size.width * (tile - (time % 256f) / 256f)) {
                            for ((index, star) in bright.withIndex()) {
                                val light = .88f + .12f * sin(pulse * (56 + index * 3) + index * 2.4f)
                                drawCircle(Color.White.copy(alpha = .04f * light), 2.dp.toPx(), star)
                                drawCircle(Color.White.copy(alpha = .8f * light), 1.dp.toPx(), star)
                            }
                        }
                    }
                }
            }) {}
            // Retain the terrain's display list when only the sibling space Canvas is invalidated.
            Canvas(Modifier.matchParentSize().graphicsLayer().semantics { contentDescription = description }) {
                try {
                    drawIntoCanvas { active.draw(it.skiaCanvas, size.width.toInt(), size.height.toInt(), aligned, accent) }
                    drawPath(contour, accent.copy(alpha = .8f), style = Stroke(1.25.dp.toPx(), join = StrokeJoin.Round))
                    val projected = route.map { it.project(aligned, size.width, size.height, active.tanFov) }.let { positions ->
                        val start = positions.firstOrNull(); val end = positions.lastOrNull()
                        val bow = if (start != null && end != null) minOf(48.dp.toPx(), (end - start).getDistance() * .22f) else 0f
                        positions.mapIndexed { index, position -> position?.plus(Offset(0f, (-sin(PI * index / 64) * bow).toFloat())) }
                    }
                    drawPath(earthPath(listOf(projected)), accent.copy(alpha = .9f), style = Stroke(1.5.dp.toPx()))
                    val arrow = projected.getOrNull(56); val tail = projected.getOrNull(54)
                    if (arrow != null && tail != null && (arrow - tail).getDistance() > .1f) {
                        val direction = (arrow - tail) / (arrow - tail).getDistance()
                        val normal = Offset(-direction.y, direction.x)
                        drawLine(accent, arrow - direction * 8.dp.toPx() + normal * 4.dp.toPx(), arrow, 1.5.dp.toPx())
                        drawLine(accent, arrow - direction * 8.dp.toPx() - normal * 4.dp.toPx(), arrow, 1.5.dp.toPx())
                    }
                    for (marker in listOfNotNull(entry, point).distinct()) {
                        val position = marker.project(aligned, size.width, size.height, active.tanFov) ?: continue
                        drawCircle(accent, 3.5.dp.toPx(), position)
                    }
                } catch (_: Exception) { failed = true }
            }
        }
        if (visible && (failed || failedToLoad)) PcText(failureMessage, size = 12, color = DesktopUiKit.Muted)
    }
}
