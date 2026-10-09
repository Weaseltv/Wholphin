package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.github.damontecres.wholphin.ui.theme.streamingProviderAccent

/** Native poster backgrounds and bundled logos avoid server thumbnail upscaling. */
@Composable
fun StreamingProviderPoster(
    name: String?,
    wordmark: String,
    modifier: Modifier = Modifier,
) {
    val brand = streamingProviderAccent(name) ?: Color.White
    val background = Color(0xFF080C14)
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to background,
                .5f to Color(
                    red = background.red * .72f + brand.red * .28f,
                    green = background.green * .72f + brand.green * .28f,
                    blue = background.blue * .72f + brand.blue * .28f,
                ),
                1f to background,
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        // Decode above the displayed size so the existing focus scale remains crisp.
        val widthPx = with(density) { (maxWidth * .78f).roundToPx() * 2 }.coerceAtLeast(1)
        val heightPx = with(density) { (maxHeight * .38f).roundToPx() * 2 }.coerceAtLeast(1)
        val request = remember(context, wordmark, widthPx, heightPx) {
            ImageRequest.Builder(context)
                .data("file:///android_asset/provider-wordmarks/$wordmark")
                .size(widthPx, heightPx)
                .apply { if (wordmark.endsWith(".svg")) decoderFactory(SvgDecoder.Factory()) }
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            colorFilter = when (wordmark) {
                "peacock.svg" -> null
                "netflix.svg", "hulu.svg", "prime_video.svg", "amc.png", "mgm.svg",
                "britbox.svg", "crunchyroll.svg" -> ColorFilter.tint(brand)
                else -> ColorFilter.tint(Color.White)
            },
            modifier = Modifier.fillMaxWidth(.78f).height(maxHeight * .38f),
        )
    }
}
