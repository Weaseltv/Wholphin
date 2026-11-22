package com.github.damontecres.wholphin.ui.components

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

/*
 * The bundled streaming provider wordmarks, moved out of StreamingProviderHeader.kt for the no-Compose UI (2026-11-22). Unchanged.
 */

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
