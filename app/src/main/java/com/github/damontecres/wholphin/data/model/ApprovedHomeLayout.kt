package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio

/** Owner-approved values captured from all eight Alpha rows on 2026-10-07. */
object ApprovedHomeLayout {
    const val REVISION = 3

    fun apply(options: HomeRowViewOptions, streaming: Boolean = false): HomeRowViewOptions =
        options.copy(
            heightDp = 172,
            spacing = 16,
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
            cardAppearance = if (streaming) options.cardAppearance.copy(borderOpacityPercent = 100) else options.cardAppearance,
        )

    /** Apply only revisions the saved layout has not received, preserving later user tuning. */
    fun upgrade(options: HomeRowViewOptions, revision: Int, streaming: Boolean): HomeRowViewOptions {
        val layout = when {
            revision < 1 -> apply(options, streaming)
            revision < 2 -> options.copy(spacing = 16)
            else -> options
        }
        return if (revision < 3 && streaming) {
            layout.copy(cardAppearance = layout.cardAppearance.copy(borderOpacityPercent = 100))
        } else {
            layout
        }
    }
}

