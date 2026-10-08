package com.github.damontecres.wholphin.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * The in-app brand: the Media Dial mascot beside the text wordmark, `WEASEL` in `text` and
 * `PLEX` in volt, Orbitron 800 uppercase (handoff README § Brand). The mascot
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
            context.resources.getIdentifier("weaselplex_mascot", "drawable", context.packageName).takeIf { it != 0 }
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
    // The owner ruled for the approved lettering in-app (2026-09-17): when the flavor ships
    // the colour lockup it is used instead of the text wordmark; other flavors keep the text.
    val context = LocalContext.current
    val lockup =
        remember(context) {
            context.resources.getIdentifier("weaselplex_lockup", "drawable", context.packageName).takeIf { it != 0 }
        }
    if (lockup != null) {
        Image(
            painter = painterResource(lockup),
            contentDescription = "WeaselPlex",
            modifier = modifier.height(mascotSize * 1.6f),
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        NeonMascot(size = mascotSize, glow = glow)
        NeonWordmark(size = wordmarkSize)
    }
}

/** Resolve only the artwork bundled by this flavor, without advancing the loading rotation. */
@Composable
internal fun rememberLoadingArtworks(): List<Int> {
    val context = LocalContext.current
    return remember(context) {
        LoadingArtworkRotation.resourceNames.mapNotNull { name ->
            context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
        }
    }
}

/** Keep the whole illustration inside a centered 70% area, including in narrow loading panels. */
@Composable
internal fun NeonLoadingArtwork(
    painter: Painter,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(.7f),
        )
    }
}

/** Each appearance advances through the bundled artwork; recomposition keeps the same image. */
@Composable
fun NeonLoadingMark(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val artworks = rememberLoadingArtworks()
    val artwork = remember(context, artworks) { LoadingArtworkRotation.next(context, artworks) }
    if (artwork != null) {
        NeonLoadingArtwork(painterResource(artwork), modifier)
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            NeonMascot(size = NeonBoard.Size.ScreensaverMark, glow = true)
        }
    }
}
