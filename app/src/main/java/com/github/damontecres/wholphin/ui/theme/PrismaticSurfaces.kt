package com.github.damontecres.wholphin.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.LocalTextStyle
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.colors.WeaselTvColors

/**
 * Shared prismatic surface treatments (handoff §3, §5).
 *
 * Every helper returns the stock value unless the WeaselTV theme is active, so these
 * can be applied at a call site without touching how any other theme renders.
 */

/** Radii from theme-tokens.json `radiiDp`. */
object WeaselRadius {
    val Card = 10.dp
    val CardWide = 12.dp
    val Row = 14.dp
    val Dialog = 16.dp
}

@Composable
fun isWeaselTv(): Boolean = LocalTheme.current == AppThemeColors.WEASELTV

/**
 * Unfocused = 1dp hairline; focused = 3dp animated rainbow.
 *
 * `widthPx` is deliberately small for cards: the gradient tiles at twice the element
 * width, so a poster-sized tile needs a short period or the sweep is imperceptible.
 */
@Composable
fun weaselCardBorder(
    shape: Shape = RoundedCornerShape(WeaselRadius.Card),
    widthPx: Float = 320f,
    fallback: CardBorder? = null,
): CardBorder {
    // `fallback` preserves a call site's original, bespoke border for every other
    // theme. Without it, adding the prismatic ring would quietly restyle them too.
    if (!isWeaselTv()) return fallback ?: CardDefaults.border()
    val brush = rememberPrismaticBrush(PrismaticDuration.FOCUS_BORDER, widthPx)
    return CardDefaults.border(
        border =
            Border(
                border = BorderStroke(1.dp, WeaselTvColors.Hairline),
                shape = shape,
            ),
        focusedBorder =
            Border(
                border = BorderStroke(3.dp, brush),
                shape = shape,
            ),
    )
}

/** Cyan bloom behind a focused surface, per focusRing.glow in the tokens. */
@Composable
fun weaselCardGlow(): CardGlow {
    if (!isWeaselTv()) return CardDefaults.glow()
    return CardDefaults.glow(
        focusedGlow = Glow(elevationColor = WeaselTvColors.NeonCyan, elevation = 22.dp),
    )
}

/** The same ring for anything built on a clickable Surface rather than a Card. */
@Composable
fun weaselSurfaceBorder(
    shape: Shape = RoundedCornerShape(WeaselRadius.Row),
    widthPx: Float = 480f,
    fallback: ClickableSurfaceBorder? = null,
): ClickableSurfaceBorder {
    if (!isWeaselTv()) return fallback ?: ClickableSurfaceDefaults.border()
    val brush = rememberPrismaticBrush(PrismaticDuration.FOCUS_BORDER, widthPx)
    return ClickableSurfaceDefaults.border(
        border =
            Border(
                border = BorderStroke(1.dp, WeaselTvColors.Hairline),
                shape = shape,
            ),
        focusedBorder =
            Border(
                border = BorderStroke(3.dp, brush),
                shape = shape,
            ),
    )
}

/**
 * Hero and page titles (§3): animated rainbow text, weight 800, tight tracking.
 * Body copy deliberately keeps its solid colour — only titles take the brush.
 */
@Composable
fun weaselTitleStyle(
    base: TextStyle = LocalTextStyle.current,
    widthPx: Float = 900f,
): TextStyle {
    if (!isWeaselTv()) return base
    val brush = rememberPrismaticBrush(PrismaticDuration.TITLE_TEXT, widthPx)
    return base.copy(
        brush = brush,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.04).em,
    )
}
