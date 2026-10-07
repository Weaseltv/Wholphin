package com.github.damontecres.wholphin.data.model

import kotlinx.serialization.Serializable

/** Home-only focus border and poster badge tuning; defaults preserve existing cards. */
@Serializable
data class HomeCardAppearance(
    val borderWidthDp: Int = 1,
    val borderOpacityPercent: Int = 100,
    val glowSpreadDp: Int = 22,
    val glowOpacityPercent: Int = 50,
    val focusScalePercent: Int = 106,
    val cornerRadiusDp: Int = 0,
    val accentIndex: Int = 0,
    val badgeTextSizeSp: Int? = null,
    val badgeTextOpacityPercent: Int = 100,
    val badgeBackgroundOpacityPercent: Int = 50,
    val badgeHorizontalInsetDp: Int? = null,
    val badgeVerticalInsetDp: Int? = null,
    val badgeHorizontalPaddingDp: Int? = null,
    val badgeVerticalPaddingDp: Int? = null,
    val badgeCornerPercent: Int = 25,
    val collectionBadge: CollectionBadgeAppearance = CollectionBadgeAppearance(),
)

/** Defaults preserve the existing COLLECTION label until the owner adjusts it. */
@Serializable
data class CollectionBadgeAppearance(
    val textSizeSp: Int = 12,
    val textOpacityPercent: Int = 100,
    val backgroundOpacityPercent: Int = 72,
    val borderWidthDp: Int = 1,
    val borderOpacityPercent: Int = 100,
    val glowSpreadDp: Int = 12,
    val glowOpacityPercent: Int = 30,
    val horizontalInsetDp: Int = 8,
    val verticalInsetDp: Int = 8,
    val horizontalPaddingDp: Int = 9,
    val verticalPaddingDp: Int = 3,
    val letterSpacingTenthsSp: Int = 12,
    val cornerRadiusDp: Int = 0,
)
