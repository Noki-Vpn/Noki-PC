package com.noki.vpn.ui

import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

private const val InviteQrPrefix = "noki://invite/"

fun inviteQrPayload(inviteCode: String): String = InviteQrPrefix + inviteCode.trim()

fun inviteCodeQrBitmap(
    inviteCode: String,
    sizePx: Int,
): ImageBitmap = qrPayloadBitmap(inviteQrPayload(inviteCode), sizePx)

fun qrPayloadBitmap(
    payload: String,
    sizePx: Int,
): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        hints,
    )
    val bitmap = ByteArray(sizePx * sizePx * 4) { 255.toByte() }
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            if (matrix[x, y]) { val offset = (y * sizePx + x) * 4; bitmap[offset] = 0; bitmap[offset + 1] = 0; bitmap[offset + 2] = 0 }
        }
    }
    return Image.makeRaster(ImageInfo.makeN32Premul(sizePx, sizePx), bitmap, sizePx * 4).toComposeImageBitmap()
}
