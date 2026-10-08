package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio

/** Owner-approved values captured from all eight Alpha rows on 2026-10-07. */
object ApprovedHomeLayout {
    const val REVISION = 8
    const val CARD_SPACING_DP = 14
    val CARD_APPEARANCE = HomeCardAppearance(
        borderWidthDp = 2,
        borderOpacityPercent = 100,
        glowSpreadDp = 15,
        glowOpacityPercent = 70,
        focusScalePercent = 109,
        badgeTextSizeSp = 11,
        badgeBackgroundOpacityPercent = 75,
        badgeHorizontalInsetDp = 8,
        badgeVerticalInsetDp = 8,
        badgeHorizontalPaddingDp = 4,
        badgeVerticalPaddingDp = 4,
        badgeCornerPercent = 20,
    )

    fun apply(options: HomeRowViewOptions, streaming: Boolean = false): HomeRowViewOptions =
        options.copy(
            heightDp = 172,
            spacing = CARD_SPACING_DP,
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
    fun upgrade(options: HomeRowViewOptions, revision: Int, streaming: Boolean, curated: Boolean = false): HomeRowViewOptions {
        val layout = when {
            revision < 1 -> apply(options, streaming)
            revision < 2 -> options.copy(spacing = 16)
            else -> options
        }
        val appearance = if ((revision < 3 && streaming) || (revision < 4 && curated)) {
            layout.copy(cardAppearance = layout.cardAppearance.copy(borderOpacityPercent = 100))
        } else {
            layout
        }
        val spaced = if (revision < 5) appearance.copy(spacing = CARD_SPACING_DP) else appearance
        val defaults = if (revision < 6) {
            spaced.copy(cardAppearance = upgradeCardDefaults(spaced.cardAppearance, streaming || curated))
        } else spaced
        // Revision 7 makes every poster border fully opaque once; later tuning remains available.
        val opaque = if (revision < 7) defaults.copy(cardAppearance = defaults.cardAppearance.copy(borderOpacityPercent = 100)) else defaults
        // Revision 8 aligns media/count badges and makes their inner padding symmetric once.
        return if (revision < 8) opaque.copy(cardAppearance = opaque.cardAppearance.copy(
            badgeHorizontalInsetDp = CARD_APPEARANCE.badgeHorizontalInsetDp,
            badgeVerticalInsetDp = CARD_APPEARANCE.badgeVerticalInsetDp,
            badgeHorizontalPaddingDp = CARD_APPEARANCE.badgeHorizontalPaddingDp,
            badgeVerticalPaddingDp = CARD_APPEARANCE.badgeVerticalPaddingDp,
        )) else opaque
    }

    /** Replace untouched legacy defaults; retain values a user has already tuned. */
    private fun upgradeCardDefaults(saved: HomeCardAppearance, collection: Boolean): HomeCardAppearance {
        val legacy = HomeCardAppearance()
        val approved = CARD_APPEARANCE
        fun <T> value(current: T, old: T, default: T): T = if (current == old) default else current
        return saved.copy(
            borderWidthDp = value(saved.borderWidthDp, legacy.borderWidthDp, approved.borderWidthDp),
            borderOpacityPercent = value(saved.borderOpacityPercent, legacy.borderOpacityPercent, approved.borderOpacityPercent),
            glowSpreadDp = value(saved.glowSpreadDp, legacy.glowSpreadDp, approved.glowSpreadDp),
            glowOpacityPercent = value(saved.glowOpacityPercent, legacy.glowOpacityPercent, approved.glowOpacityPercent),
            focusScalePercent = value(saved.focusScalePercent, legacy.focusScalePercent, approved.focusScalePercent),
            badgeTextSizeSp = value(saved.badgeTextSizeSp, legacy.badgeTextSizeSp, approved.badgeTextSizeSp),
            badgeBackgroundOpacityPercent = value(saved.badgeBackgroundOpacityPercent, legacy.badgeBackgroundOpacityPercent, approved.badgeBackgroundOpacityPercent),
            badgeHorizontalInsetDp = value(saved.badgeHorizontalInsetDp, legacy.badgeHorizontalInsetDp, approved.badgeHorizontalInsetDp),
            badgeVerticalInsetDp = value(saved.badgeVerticalInsetDp, legacy.badgeVerticalInsetDp, approved.badgeVerticalInsetDp),
            badgeHorizontalPaddingDp = value(saved.badgeHorizontalPaddingDp, legacy.badgeHorizontalPaddingDp, approved.badgeHorizontalPaddingDp),
            badgeVerticalPaddingDp = value(saved.badgeVerticalPaddingDp, legacy.badgeVerticalPaddingDp, approved.badgeVerticalPaddingDp),
            badgeCornerPercent = value(saved.badgeCornerPercent, legacy.badgeCornerPercent, approved.badgeCornerPercent),
        )
    }
}

