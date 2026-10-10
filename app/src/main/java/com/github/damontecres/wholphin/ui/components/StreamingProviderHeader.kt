package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonEyebrow
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.streamingProviderAccent
import java.util.Locale

/** Provider artwork is bundled so this header doesn't depend on collection backdrop metadata. */
@Composable
fun StreamingProviderHeader(
    item: BaseItem,
    modifier: Modifier = Modifier,
) {
    val wordmark = providerWordmark(item.name)
    val providerColor = streamingProviderAccent(item.name) ?: LocalNeonAccent.current
    Box(modifier = modifier.fillMaxWidth()) {
        if (wordmark != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data("file:///android_asset/provider-wordmarks/$wordmark")
                        .apply { if (wordmark.endsWith(".svg")) decoderFactory(SvgDecoder.Factory()) }
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter =
                    when (wordmark) {
                        "peacock.svg" -> null

                        "netflix.svg", "hulu.svg", "prime_video.svg", "amc.png", "mgm.svg",
                        "britbox.svg", "crunchyroll.svg", "hallmark.svg",
                        -> ColorFilter.tint(providerColor)

                        else -> ColorFilter.tint(Color.White)
                    },
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(.56f)
                        .padding(end = 64.dp)
                        .height(120.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(.42f),
        ) {
            NeonEyebrow(text = stringResource(R.string.collection), accent = NeonSectionPalette.Collections.glow)
            TitleOrLogo(title = item.title, logoImageUrl = null, showLogo = false)
            QuickDetails(item.ui.quickDetails, item.timeRemainingOrRuntime, endsAt = item.data.endDate)
            Text(
                text = stringResource(R.string.streaming_provider_description, item.name.orEmpty()),
                style = NeonType.body(),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun providerWordmark(name: String?): String? =
    when (name?.trim()?.lowercase(Locale.ROOT)) {
        "netflix" -> "netflix.svg"
        "disney+", "disney plus" -> "disney.svg"
        "hulu" -> "hulu.svg"
        "max", "hbo", "hbo max", "max (hbo)" -> "max.svg"
        "prime video", "amazon prime video" -> "prime_video.svg"
        "paramount+", "paramount plus" -> "paramount.svg"
        "peacock" -> "peacock.svg"
        "apple tv", "apple tv+", "apple tv plus" -> "apple_tv.svg"
        "amc+", "amc plus" -> "amc.png"
        "mgm+", "mgm plus" -> "mgm.svg"
        "starz" -> "starz.svg"
        "britbox", "brit box" -> "britbox.svg"
        "crunchyroll" -> "crunchyroll.svg"
        "hallmark", "hallmark+", "hallmark plus" -> "hallmark.svg"
        "angel", "angel studios" -> "angel.svg"
        else -> null
    }
