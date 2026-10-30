package com.noki.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import org.jetbrains.skia.*
import kotlin.math.*

internal fun mapMercator(latitude: Float): Float = ln(tan(PI / 4 + latitude.coerceIn(-1.484f, 1.484f) / 2)).toFloat()
internal fun mapLongitude(longitude: Float): Float = ((longitude + PI).mod(2 * PI) - PI).toFloat()
internal fun mapRasterSize(width: Int, height: Int): IntSize {
    val scale = minOf(1.0, sqrt(2_097_152.0 / (width.toDouble() * height)), 2048.0 / maxOf(width, height))
    return IntSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
}

@Composable internal fun CountryMapViewport(modifier: Modifier, selectedCountryCode: String?, entryCountryCode: String?, description: String) {
    val accent = LocalDesktopStyle.current.accent
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current.density
        val width = (maxWidth.value * density).roundToInt().coerceAtLeast(1)
        val height = (maxHeight.value * density).roundToInt().coerceAtLeast(1)
        var image by remember { mutableStateOf<org.jetbrains.skia.Image?>(null) }
        DisposableEffect(Unit) { onDispose { image?.close() } }
        LaunchedEffect(width, height, density, selectedCountryCode, entryCountryCode, accent) {
            var loaded: org.jetbrains.skia.Image? = null
            try {
                withContext(Dispatchers.Default) {
                    loaded = renderCountryMap(width, height, density, selectedCountryCode, entryCountryCode, accent)
                }
                ensureActive()
                val previous = image
                image = loaded; loaded = null
                previous?.close()
            } finally { loaded?.close() }
        }
        image?.let { loaded -> Image(remember(loaded) { loaded.toComposeImageBitmap() }, description,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium) }
    }
}

internal suspend fun renderCountryMap(width: Int, height: Int, density: Float, selectedCountryCode: String?, entryCountryCode: String?, accent: Color): org.jetbrains.skia.Image {
    val data = CountryGeography()
    val selected = listOfNotNull(selectedCountryCode, entryCountryCode).map(String::uppercase).distinct()
    val points = selected.mapNotNull(data.countries::get)
    val focus = points.firstOrNull() ?: data.countries.getValue("PL")
    val controlEnd = (110 + DesktopUiKit.HomePanelWidth.value + 12) * density
    val left = if (width - controlEnd >= 180 * density) controlEnd else 110 * density
    val center = Offset((left + width) / 2, (40 * density + height) / 2)
    val focusY = mapMercator(focus.latitude)
    val targets = selected.flatMap { data.outlines[it].orEmpty().flatten() } + points
    val nearby = targets.filter { abs(mapLongitude(it.longitude - focus.longitude)) < 1.4f && abs(it.latitude - focus.latitude) < .9f }
    val halfWidth = maxOf(.40f, nearby.maxOfOrNull { abs(mapLongitude(it.longitude - focus.longitude)) } ?: 0f,
        points.maxOfOrNull { abs(mapLongitude(it.longitude - focus.longitude)) } ?: 0f)
    val halfHeight = maxOf(.32f, nearby.maxOfOrNull { abs(mapMercator(it.latitude) - focusY) } ?: 0f,
        points.maxOfOrNull { abs(mapMercator(it.latitude) - focusY) } ?: 0f)
    val scale = minOf((width - left - 48 * density).coerceAtLeast(24f) / (halfWidth * 2.3f),
        (height - 120 * density).coerceAtLeast(24f) / (halfHeight * 2.3f))
    fun project(point: EarthCountryPoint) = Offset(center.x + mapLongitude(point.longitude - focus.longitude) * scale,
        center.y - (mapMercator(point.latitude) - focusY) * scale)
    val raster = mapRasterSize(width, height)
    return Surface.makeRasterN32Premul(raster.width, raster.height).use { surface ->
        val canvas = surface.canvas
        canvas.scale(raster.width.toFloat() / width, raster.height.toFloat() / height)
        canvas.clear(0xFF121A1B.toInt())
        Paint().use { paint ->
            paint.isAntiAlias = true
            for ((code, rings) in data.outlines) {
                currentCoroutineContext().ensureActive()
                for (world in -1..1) PathBuilder(PathFillMode.EVEN_ODD).use { path ->
                    for (ring in rings) {
                        if (ring.isEmpty()) continue
                        var longitude = mapLongitude(ring.first().longitude - focus.longitude)
                        ring.forEachIndexed { index, point ->
                            if (index > 0) longitude += mapLongitude(point.longitude - ring[index - 1].longitude)
                            val x = center.x + (longitude + world * 2 * PI.toFloat()) * scale
                            val y = center.y - (mapMercator(point.latitude) - focusY) * scale
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.closePath()
                    }
                    paint.mode = PaintMode.FILL
                    paint.color = if (code in selected) accent.copy(alpha = .2f).toArgb() else 0xFF293133.toInt()
                    path.detach().use { outline ->
                        canvas.drawPath(outline, paint)
                        paint.mode = PaintMode.STROKE
                        paint.color = if (code in selected) accent.copy(alpha = .85f).toArgb() else 0xFF414B4D.toInt()
                        paint.strokeWidth = (if (code in selected) 1.3f else .65f) * density
                        canvas.drawPath(outline, paint)
                    }
                }
            }
            if (points.size == 2) PathBuilder().use { path ->
                points[1].routeTo(points[0]).map(::project).forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                paint.mode = PaintMode.STROKE; paint.strokeWidth = 1.5f * density; paint.color = accent.toArgb()
                path.detach().use { canvas.drawPath(it, paint) }
            }
            paint.mode = PaintMode.FILL; paint.color = accent.toArgb()
            for (point in points.map(::project)) canvas.drawCircle(point.x, point.y, 3.5f * density, paint)
        }
        currentCoroutineContext().ensureActive()
        surface.makeImageSnapshot()
    }
}
