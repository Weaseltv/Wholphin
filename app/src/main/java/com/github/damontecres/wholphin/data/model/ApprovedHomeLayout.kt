package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio

/** Owner-approved presentation captured from all eight Alpha rows on 2026-10-09. */
object ApprovedHomeLayout {
    const val REVISION = 12
    const val CARD_SPACING_DP = 10
    const val CARD_HEIGHT_DP = 170
    const val VERTICAL_PADDING_DP = 4
    const val EXTRA_VERTICAL_PADDING_DP = 4
    const val DIVIDER_GAP_DP = 12
    const val ROW_GAP_DP = 8
    const val EDGE_PADDING_DP = 4
    const val END_PADDING_DP = 16
    const val TITLE_DIVIDER_GAP_DP = 1
    const val TITLE_SIZE_SP = 35
    const val TITLE_TRACKING_TENTHS_SP = 18
    const val COUNT_SIZE_SP = 14
    const val COUNT_OPACITY_PERCENT = 100
    const val COUNT_END_PADDING_DP = 15
    const val DIVIDER_THICKNESS_DP = 3
    const val DIVIDER_GLOW_DP = 10
    const val DIVIDER_GLOW_STRENGTH = 80
    val CARD_APPEARANCE = HomeCardAppearance(
        borderWidthDp = 1,
        borderOpacityPercent = 100,
        glowSpreadDp = 10,
        glowOpacityPercent = 85,
        focusScalePercent = 108,
        badgeTextSizeSp = 11,
        badgeBackgroundOpacityPercent = 75,
        badgeHorizontalInsetDp = 8,
        badgeVerticalInsetDp = 8,
        badgeHorizontalPaddingDp = 4,
        badgeVerticalPaddingDp = 4,
        badgeCornerPercent = 20,
    )

    /** The shared row/grid/section recipe; content choices and collection-specific badges survive. */
    fun applyPresentation(options: HomeRowViewOptions): HomeRowViewOptions =
        options.copy(
            heightDp = CARD_HEIGHT_DP,
            spacing = CARD_SPACING_DP,
            verticalPaddingDp = VERTICAL_PADDING_DP,
            extraVerticalPaddingDp = EXTRA_VERTICAL_PADDING_DP,
            dividerGapDp = DIVIDER_GAP_DP,
            rowGapDp = ROW_GAP_DP,
            edgePaddingDp = EDGE_PADDING_DP,
            endPaddingDp = END_PADDING_DP,
            titleDividerGapDp = TITLE_DIVIDER_GAP_DP,
            titleSizeSp = TITLE_SIZE_SP,
            titleLetterSpacingTenthsSp = TITLE_TRACKING_TENTHS_SP,
            countSizeSp = COUNT_SIZE_SP,
            countOpacityPercent = COUNT_OPACITY_PERCENT,
            countEndPaddingDp = COUNT_END_PADDING_DP,
            dividerThicknessDp = DIVIDER_THICKNESS_DP,
            dividerGlowDp = DIVIDER_GLOW_DP,
            dividerGlowStrength = DIVIDER_GLOW_STRENGTH,
            cardAppearance = CARD_APPEARANCE.copy(collectionBadge = options.cardAppearance.collectionBadge),
        )

    fun apply(options: HomeRowViewOptions, streaming: Boolean = false): HomeRowViewOptions =
        applyPresentation(options).copy(
            aspectRatio = AspectRatio.TALL,
            episodeAspectRatio = if (streaming) AspectRatio.WIDE else AspectRatio.TALL,
            contentScale = if (streaming) PrefContentScale.FIT else PrefContentScale.FILL,
            episodeContentScale = if (streaming) PrefContentScale.FIT else PrefContentScale.FILL,
            showTitles = false,
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
        val badges = if (revision < 8) opaque.copy(cardAppearance = opaque.cardAppearance.copy(
            badgeHorizontalInsetDp = CARD_APPEARANCE.badgeHorizontalInsetDp,
            badgeVerticalInsetDp = CARD_APPEARANCE.badgeVerticalInsetDp,
            badgeHorizontalPaddingDp = CARD_APPEARANCE.badgeHorizontalPaddingDp,
            badgeVerticalPaddingDp = CARD_APPEARANCE.badgeVerticalPaddingDp,
        )) else opaque
        // Orbitron section headers use approximately 0.08 em tracking at their saved size.
        val orbitron = if (revision < 9) badges.copy(
            titleLetterSpacingTenthsSp = (badges.titleSizeSp * 0.8f).toInt(),
        ) else badges
        // Audiowide uses 0.06 em tracking once; subsequent owner tuning remains available.
        val audiowide = if (revision < 10) orbitron.copy(
            titleLetterSpacingTenthsSp = (orbitron.titleSizeSp * 0.6f).toInt(),
        ) else orbitron
        // Owner approved this recipe globally for existing and new users, once per saved layout.
        val presentation = if (revision < 11) applyPresentation(audiowide) else audiowide
        // Revision 12 changes only focus thickness; retain all other saved tuning.
        return if (revision < 12) presentation.copy(
            cardAppearance = presentation.cardAppearance.copy(borderWidthDp = CARD_APPEARANCE.borderWidthDp),
        ) else presentation
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
