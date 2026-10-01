package com.github.damontecres.wholphin.ui.setup

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.BuildConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The page a phone opens to approve this TV's Quick Connect [code], or null when the flavor has
 * no approve page (every upstream flavor) or there is no code yet. The web page signs the
 * customer in with their website account and approves the code; the TV keeps polling as usual.
 */
fun quickConnectApproveUrl(code: String?): String? {
    val base = BuildConfig.QUICK_CONNECT_APPROVE_URL
    if (base.isBlank() || code.isNullOrBlank()) return null
    return "$base?code=${Uri.encode(code)}&device=tv"
}

/**
 * A QR code for [content], black on white so a phone camera reads it off a TV screen.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val bitmap = remember(content) { renderQrCode(content, QR_PIXELS) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

private const val QR_PIXELS = 512

internal fun renderQrCode(
    content: String,
    pixels: Int,
): Bitmap {
    val hints =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val width = matrix.width
    val height = matrix.height
    val colors = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            colors[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.RGB_565)
}
