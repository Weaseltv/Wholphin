package com.github.damontecres.wholphin.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
