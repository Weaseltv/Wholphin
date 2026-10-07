package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio

/** Owner-approved values captured from all eight Alpha rows on 2026-10-07. */
object ApprovedHomeLayout {
    const val REVISION = 1

    fun apply(options: HomeRowViewOptions, streaming: Boolean = false): HomeRowViewOptions =
        options.copy(
            heightDp = 172,
            spacing = 20,
            verticalPaddingDp = 4,
            extraVerticalPaddingDp = 12,
            dividerGapDp = 12,
            rowGapDp = 8,
            edgePaddingDp = 4,
            endPaddingDp = 16,
            titleDividerGapDp = 3,
            titleSizeSp = 30,
            titleLetterSpacingTenthsSp = 0,
            countSizeSp = 14,
            countOpacityPercent = 100,
            countEndPaddingDp = 15,
            dividerThicknessDp = 5,
            dividerGlowDp = 10,
            dividerGlowStrength = 70,
            aspectRatio = AspectRatio.TALL,
            episodeAspectRatio = if (streaming) AspectRatio.WIDE else AspectRatio.TALL,
            contentScale = if (streaming) PrefContentScale.FIT else PrefContentScale.FILL,
            episodeContentScale = if (streaming) PrefContentScale.FIT else PrefContentScale.FILL,
            showTitles = false,
        )
}

