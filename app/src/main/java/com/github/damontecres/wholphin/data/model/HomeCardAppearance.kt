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
)
