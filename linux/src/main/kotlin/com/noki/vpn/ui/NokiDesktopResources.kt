package com.noki.vpn.ui

import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import com.noki.vpn.R
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal val LocalNokiUiSize = staticCompositionLocalOf<DpSize?> { null }

internal val ManropeFontFamily = FontFamily(
    Font(R.paths.getValue(R.font.manrope_regular), FontWeight.Normal),
    Font(R.paths.getValue(R.font.manrope_medium), FontWeight.Medium),
    Font(R.paths.getValue(R.font.manrope_semibold), FontWeight.SemiBold),
    Font(R.paths.getValue(R.font.manrope_bold), FontWeight.Bold),
)

@Composable internal fun nokiScreenSize(): DpSize {
    LocalNokiUiSize.current?.let { return it }
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}

private val svgResources = LruCache<String, ByteArray>(64)
private val vectorResources = LruCache<Pair<String, Density>, ImageVector>(64)
private val bitmapResources = LruCache<String, ImageBitmap>(16)
private val avatarImages = LruCache<Triple<String, FileTime, Long>, ImageBitmap>(4)

internal fun clearNokiUiCaches() {
    synchronized(svgResources) { svgResources.evictAll() }
    synchronized(vectorResources) { vectorResources.evictAll() }
    // Existing painters can still own these images after cache eviction.
    synchronized(bitmapResources) { bitmapResources.evictAll() }
    synchronized(avatarImages) { avatarImages.evictAll() }
}

private inline fun <K : Any, V : Any> LruCache<K, V>.cached(key: K, load: () -> V): V =
    synchronized(this) { get(key) ?: load().also { put(key, it) } }

internal fun nokiBitmapResource(path: String): ImageBitmap =
    bitmapResources.cached(path) { useResource(path, ::loadImageBitmap) }

@Composable internal fun nokiPainterResource(resourceId: Int): Painter = nokiPainterResource(R.paths.getValue(resourceId))

@Composable internal fun nokiPainterResource(path: String): Painter {
    val density = LocalDensity.current
    return when (path.substringAfterLast('.')) {
        "svg" -> remember(path, density) {
            val bytes = svgResources.cached(path) { useResource(path) { it.readAllBytes() } }
            loadSvgPainter(ByteArrayInputStream(bytes), density)
        }
        "xml" -> rememberVectorPainter(vectorResources.cached(path to density) {
            useResource(path) { loadXmlImageVector(InputSource(it), density) }
        })
        else -> remember(path) { BitmapPainter(nokiBitmapResource(path)) }
    }
}

internal fun nokiAvatarImage(path: String?): ImageBitmap? = path?.let { value ->
    runCatching {
        val file = Path.of(value)
        val version = Files.readAttributes(file, BasicFileAttributes::class.java)
        avatarImages.cached(Triple(value, version.lastModifiedTime(), version.size())) {
            com.noki.desktop.loadAvatarImage(file).toComposeImageBitmap()
        }
    }.getOrNull()
}

// Skia text has no Android includeFontPadding setting.
internal fun nokiPlatformTextStyle(includeFontPadding: Boolean): PlatformTextStyle? = null

@Composable internal fun nokiRawResourceId(name: String?): Int =
    R.paths.entries.firstOrNull { it.value.endsWith("/raw/$name.svg") }?.key ?: 0

@Composable internal fun FigmaSvgAsset(resId: Int, viewportWidth: Int, viewportHeight: Int, modifier: Modifier = Modifier) {
    Image(nokiPainterResource(resId), contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
}

internal typealias NokiRuntimeShader = RuntimeShaderBuilder

@Composable internal fun rememberNokiRuntimeShader(source: String): NokiRuntimeShader {
    val effect = remember(source) { RuntimeEffect.makeForShader(source) }
    val shader = remember(effect) { RuntimeShaderBuilder(effect) }
    DisposableEffect(shader) { onDispose { shader.close(); effect.close() } }
    return shader
}

internal fun NokiRuntimeShader.setFloatUniform(name: String, vararg values: Float) { uniform(name, values) }

internal fun DrawScope.drawNokiRuntimeShader(shader: NokiRuntimeShader, width: Float, height: Float) {
    shader.makeShader().use { nativeShader ->
        Paint().use { paint ->
            paint.shader = nativeShader
            drawIntoCanvas { it.nativeCanvas.drawRect(Rect.makeWH(width, height), paint) }
        }
    }
}
