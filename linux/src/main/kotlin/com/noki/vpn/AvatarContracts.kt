package com.noki.vpn
import kotlin.math.*
import com.noki.desktop.WindowsAvatarMatrix as Matrix

internal data class AvatarCropRequest(
    val sourceUri: String,
    val previewWidthPx: Float,
    val previewHeightPx: Float,
    val cropCircleSizePx: Float,
    val cropScale: Float,
    val cropOffsetX: Float,
    val cropOffsetY: Float,
    val rotationQuarterTurns: Int = 0,
) {
    private val quarterTurns: Int get() = Math.floorMod(rotationQuarterTurns, 4)

    private fun imageScale(width: Int, height: Int): Float {
        require(width > 0 && height > 0)
        val rotatedWidth = if (quarterTurns % 2 == 0) width else height
        val rotatedHeight = if (quarterTurns % 2 == 0) height else width
        val fit = min(previewWidthPx / rotatedWidth, previewHeightPx / rotatedHeight)
        val cover = max(cropCircleSizePx / rotatedWidth, cropCircleSizePx / rotatedHeight)
        return max(fit, cover) * (cropScale.takeIf(Float::isFinite)?.coerceIn(1f, 5f) ?: 1f)
    }

    fun clampOffsets(width: Int, height: Int): AvatarCropRequest {
        val scale = imageScale(width, height)
        val rotatedWidth = if (quarterTurns % 2 == 0) width else height
        val rotatedHeight = if (quarterTurns % 2 == 0) height else width
        val maxX = max(0f, (rotatedWidth * scale - cropCircleSizePx) / 2f)
        val maxY = max(0f, (rotatedHeight * scale - cropCircleSizePx) / 2f)
        return copy(
            cropOffsetX = (cropOffsetX.takeIf(Float::isFinite) ?: 0f).coerceIn(-maxX, maxX),
            cropOffsetY = (cropOffsetY.takeIf(Float::isFinite) ?: 0f).coerceIn(-maxY, maxY),
        )
    }

    // Shared by preview and JPEG export: source pixels -> preview coordinates.
    fun imageMatrix(width: Int, height: Int): Matrix {
        val crop = clampOffsets(width, height)
        val scale = imageScale(width, height)
        return Matrix().apply {
            postTranslate(-width / 2f, -height / 2f)
            postRotate(quarterTurns * 90f)
            postScale(scale, scale)
            postTranslate(previewWidthPx / 2f + crop.cropOffsetX, previewHeightPx / 2f + crop.cropOffsetY)
        }
    }
}

internal object AvatarBitmapDecoder {
    private const val MAX_DECODED_SIDE = 2_048
    private const val MAX_DECODED_PIXELS = 4_194_304L

    fun inSampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "avatar_open_failed" }
        var sample = 1
        while (true) {
            val sampledWidth = (width.toLong() + sample - 1L) / sample
            val sampledHeight = (height.toLong() + sample - 1L) / sample
            if (
                sampledWidth <= MAX_DECODED_SIDE &&
                sampledHeight <= MAX_DECODED_SIDE &&
                sampledWidth * sampledHeight <= MAX_DECODED_PIXELS
            ) {
                return sample
            }
            check(sample <= Int.MAX_VALUE / 2) { "avatar_open_failed" }
            sample *= 2
        }
    }

}
