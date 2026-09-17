package com.github.damontecres.wholphin.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * The in-app brand: the white mascot beside the text wordmark, `WEASEL` in `text` and
 * `PLEX` in volt, Barlow Condensed 800 uppercase (handoff README § Brand). The mascot
 * drawable ships only in the `weaselfin` flavor and is resolved by name; on any other
 * flavor only the wordmark renders.
 */
@Composable
fun NeonWordmark(
    size: TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            buildAnnotatedString {
                withStyle(SpanStyle(color = NeonBoard.Text)) { append("WEASEL") }
                withStyle(SpanStyle(color = NeonBoard.Volt)) { append("PLEX") }
            },
        style = NeonType.wordmark(size),
        maxLines = 1,
        modifier = modifier,
    )
}

/** The mascot mark with an optional volt glow (`drawBehind` radial, no blur filter). */
@Composable
fun NeonMascot(
    size: Dp,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
) {
    val context = LocalContext.current
    val id =
        remember(context) {
            context.resources.getIdentifier("weaselplex_mascot_white", "drawable", context.packageName).takeIf { it != 0 }
        }
    if (id == null) return
    Image(
        painter = painterResource(id),
        contentDescription = null,
        modifier =
            modifier
                .size(size)
                .then(
                    if (glow) {
                        Modifier.drawBehind {
                            val r = this.size.minDimension * .9f
                            drawCircle(
                                brush =
                                    Brush.radialGradient(
                                        colors = listOf(NeonBoard.Volt.copy(alpha = .45f), Color.Transparent),
                                        center = center,
                                        radius = r,
                                    ),
                                radius = r,
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
    )
}

/** Mascot + wordmark side by side (rail 20, sign-in 34, screensaver 36). */
@Composable
fun NeonBrandRow(
    mascotSize: Dp,
    wordmarkSize: TextUnit,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        NeonMascot(size = mascotSize, glow = glow)
        NeonWordmark(size = wordmarkSize)
    }
}

/**
 * The launch / loading block (`03-screens.md` board 18): mascot 96 with the volt glow,
 * wordmark, a 2dp volt progress rule and the "OPENING WEASELPLEX" eyebrow.
 */
@Composable
fun NeonLoadingMark(
    modifier: Modifier = Modifier,
    caption: String = "Opening WeaselPlex",
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier,
    ) {
        NeonMascot(size = NeonBoard.Size.ScreensaverMark, glow = true)
        NeonWordmark(size = 36.sp)
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .width(240.dp)
                    .height(2.dp)
                    .padding(top = 0.dp)
                    .neonRuleBelow(NeonBoard.Volt),
        )
        NeonEyebrow(text = caption, modifier = Modifier.fillMaxWidth(), accent = NeonBoard.Mid)
    }
}
