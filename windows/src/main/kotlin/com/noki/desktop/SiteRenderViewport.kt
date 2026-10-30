package com.noki.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.useResource
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import kotlin.math.ceil
import kotlin.math.max
import org.jetbrains.skia.Image as SkiaImage

@Composable internal fun SiteRenderViewport(modifier: Modifier, visible: Boolean, description: String, alignment: Alignment = Alignment.Center) {
    if (!visible) return
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val width = siteRenderDecodeWidth(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        val image by produceState<SkiaImage?>(null, width) {
            value = null
            var decoded: SkiaImage? = null
            try {
                withContext(Dispatchers.Default) { decoded = decodeSiteRender(width) }
                ensureActive()
                value = decoded
                awaitCancellation()
            } finally { decoded?.close() }
        }
        val loaded = image ?: return@BoxWithConstraints
        val bitmap = remember(loaded) { loaded.toComposeImageBitmap() }
        Image(bitmap, description, Modifier.matchParentSize().drawWithContent {
        drawContent()
        // The site's render already includes canvas grading; only .hero::after remains.
        drawRect(Color.Black.copy(alpha = .2f))
        }, contentScale = ContentScale.Crop, alignment = alignment, filterQuality = FilterQuality.High)
    }
}

internal fun siteRenderDecodeWidth(width: Float, height: Float): Int =
    (ceil(max(width, height * 16f / 9f) / 256f).toInt() * 256).coerceIn(256, 3840)

internal fun decodeSiteRender(width: Int): SkiaImage {
    require(width in 256..3840)
    // WebP supports scaled decoding: never allocate the full 4K pixels for a small window.
    return useResource("noki/desktop/scene/site-render.webp") { input ->
        Data.makeFromBytes(input.readBytes()).use { encoded ->
            Codec.makeFromData(encoded).use { codec ->
                Bitmap().use { pixels ->
                    check(pixels.allocN32Pixels(width, width * 9 / 16))
                    codec.readPixels(pixels)
                    pixels.setImmutable()
                    SkiaImage.makeFromBitmap(pixels)
                }
            }
        }
    }
}
