package com.github.damontecres.wholphin.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.CollectionBadgeAppearance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import org.jellyfin.sdk.model.api.BaseItemKind

/** Owner-approved Picks focus/count values, also used before Home settings finish loading. */
val ApprovedCollectionCardAppearance = HomeCardAppearance(
    borderWidthDp = 2,
    borderOpacityPercent = 100,
    glowSpreadDp = 15,
    glowOpacityPercent = 70,
    focusScalePercent = 109,
    badgeTextSizeSp = 11,
    badgeBackgroundOpacityPercent = 75,
    badgeHorizontalInsetDp = 5,
    badgeVerticalInsetDp = 5,
    badgeHorizontalPaddingDp = 2,
    badgeVerticalPaddingDp = 2,
    badgeCornerPercent = 20,
)

val LocalCollectionCardAppearance = compositionLocalOf<HomeCardAppearance?> { null }

@Composable
fun CollectionCardStyle(
    item: BaseItem?,
    appearance: HomeCardAppearance,
    streamingAppearance: HomeCardAppearance = appearance,
    content: @Composable () -> Unit,
) {
    if (isWeaselTv() && item?.type == BaseItemKind.BOX_SET) {
        val providerAccent = streamingProviderAccent(item.name)
        val focusAppearance = if (providerAccent != null) streamingAppearance else appearance
        CompositionLocalProvider(
            LocalCollectionCardAppearance provides appearance,
            LocalHomeCardAppearance provides focusAppearance.copy(accentIndex = 0, borderOpacityPercent = 100),
            LocalHomeRowAccent provides (providerAccent ?: collectionPosterAccent(item.name)),
            LocalHomeCardBorderAccent provides streamingProviderBorderAccent(item.name),
            content = content,
        )
    } else {
        content()
    }
}

/** Picks, additional collections and sports use the owner's supplied poster palettes. */
fun collectionPosterAccent(name: String?) = curatedPickAccent(name) ?: when (
    name?.trim()?.lowercase(java.util.Locale.ROOT)?.replace("’", "")?.replace("'", "")
) {
    "a24 mood" -> Color(0xFF6440FF)
    "afis 100 greatest", "afi 100 greatest" -> Color(0xFFFFCC40)
    "amblin magic" -> Color(0xFF4094FF)
    "anime feature night", "anime feature nights" -> Color(0xFFFF40A7)
    "box office champs" -> Color(0xFF55FF40)
    "capes and cowls", "capes & cowls" -> Color(0xFFFF2649)
    "edge of your seat" -> Color(0xFFFF3930)
    "family movie night" -> Color(0xFFFFD640)
    "front lines" -> Color(0xFF8DFF40)
    "game day" -> Color(0xFF2EFF8B)
    "gold statue" -> Color(0xFFFFD140)
    "groovy 70s" -> Color(0xFFFF8B2A)
    "grown up toons", "grown-up toons" -> Color(0xFFFF40DF)
    "imdb most popular" -> Color(0xFFFFCE2C)
    "imdb top 250" -> Color(0xFFFFCF31)
    "ink and pixels", "ink & pixels" -> Color(0xFF40FFD9)
    "mic drop" -> Color(0xFFFF4918)
    "one and done", "one & done" -> Color(0xFF31D2FF)
    "out of this world" -> Color(0xFF39D5FF)
    "saddle up" -> Color(0xFFFF9134)
    "silver screen" -> Color(0xFF407EFF)
    "sword and sorcery", "sword & sorcery" -> Color(0xFF9840FF)
    "the auteur shelf" -> Color(0xFFFFC940)
    "the complete set" -> Color(0xFF4067FF)
    "the sandlerverse" -> Color(0xFFFF8B2E)
    "the usual suspects" -> Color(0xFFFF5A39)
    "tmdb top rated" -> Color(0xFF39FFE2)
    "turn it up" -> Color(0xFFFF33D4)
    "whodunit", "whodunnit" -> Color(0xFFFFC932)
    "boxing" -> Color(0xFFF29E19)
    "ufc fight night" -> Color(0xFF3B8BF2)
    "ufc on abc" -> Color(0xFF1FF2D4)
    "ufc on espn" -> Color(0xFFF22519)
    "ufc ppv" -> Color(0xFFFECD30)
    else -> null
}

/** Separate from count badges: initial values reproduce the existing COLLECTION label. */
@Composable
fun CollectionTypeBadge(text: String, accent: Color, appearance: CollectionBadgeAppearance) {
    Box(
        modifier = Modifier.drawBehind {
            val glow = appearance.glowSpreadDp.dp.toPx()
            val alpha = appearance.glowOpacityPercent / 100f
            if (glow > 0f && alpha > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = alpha * .5f), Color.Transparent),
                        center = center,
                        radius = size.maxDimension / 2 + glow,
                    ),
                    topLeft = Offset(-glow, -glow),
                    size = Size(size.width + 2 * glow, size.height + 2 * glow),
                )
            }
            val radius = CornerRadius(appearance.cornerRadiusDp.dp.toPx())
            drawRoundRect(color = NeonBoard.Card.copy(alpha = appearance.backgroundOpacityPercent / 100f), cornerRadius = radius)
            if (appearance.borderWidthDp > 0 && appearance.borderOpacityPercent > 0) {
                drawRoundRect(
                    color = accent.copy(alpha = appearance.borderOpacityPercent / 100f),
                    cornerRadius = radius,
                    style = Stroke(width = appearance.borderWidthDp.dp.toPx()),
                )
            }
        }.padding(horizontal = appearance.horizontalPaddingDp.dp, vertical = appearance.verticalPaddingDp.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = accent.copy(alpha = appearance.textOpacityPercent / 100f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = appearance.textSizeSp.sp,
                lineHeight = (appearance.textSizeSp * 1.2f).sp,
                letterSpacing = (appearance.letterSpacingTenthsSp / 10f).sp,
            ),
            maxLines = 1,
        )
    }
}
