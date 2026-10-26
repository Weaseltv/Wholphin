package com.github.damontecres.wholphin.ui.walkthrough

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import com.github.damontecres.wholphin.ui.FontAwesome
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType

/** Illustrations are examples of the real screens, labelled as such (plan 4.5) */
@Composable
fun IllustrationFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        content()
        Text(
            text = stringResource(R.string.tour_example).uppercase(),
            style = NeonType.eyebrow(),
            color = NeonBoard.Low,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A generic TV remote (no brand): arrows around OK, BACK and ⏯, with the keys the card names lit */
@Composable
fun RemoteIllustration(modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier =
                Modifier
                    .width(170.dp)
                    .height(360.dp),
        ) { drawRemote(measurer) }
    }
}

private fun DrawScope.drawRemote(measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    val body = NeonBoard.Card2
    val line = NeonBoard.Line2
    val lit = NeonBoard.Volt
    // Body
    drawRoundRect(color = body, cornerRadius = CornerRadius(w * .3f), size = Size(w, h))
    drawRoundRect(color = line, cornerRadius = CornerRadius(w * .3f), size = Size(w, h), style = Stroke(2.dp.toPx()))

    // D-pad ring with arrows, OK in the middle
    val center = Offset(w / 2, h * .3f)
    val ring = w * .36f
    drawCircle(color = NeonBoard.Well, radius = ring, center = center)
    drawCircle(color = lit, radius = ring, center = center, style = Stroke(2.dp.toPx()))
    val arrow = ring * .2f
    val reach = ring * .72f
    listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
        val tip = Offset(center.x + dx * (reach + arrow / 2), center.y + dy * (reach + arrow / 2))
        val back = Offset(center.x + dx * (reach - arrow / 2), center.y + dy * (reach - arrow / 2))
        val side = Offset(-dy * arrow * .7f, dx * arrow * .7f)
        val path =
            Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(back.x + side.x, back.y + side.y)
                lineTo(back.x - side.x, back.y - side.y)
                close()
            }
        drawPath(path, color = lit)
    }
    drawCircle(color = body, radius = ring * .45f, center = center)
    drawCircle(color = lit, radius = ring * .45f, center = center, style = Stroke(2.dp.toPx()))
    drawLabel(measurer, "OK", center, lit)

    // BACK and ⏯ below
    val keyRadius = w * .13f
    val back = Offset(w * .3f, h * .58f)
    val play = Offset(w * .7f, h * .58f)
    listOf(back to "BACK", play to "⏯").forEach { (c, label) ->
        drawCircle(color = NeonBoard.Well, radius = keyRadius, center = c)
        drawCircle(color = lit, radius = keyRadius, center = c, style = Stroke(2.dp.toPx()))
        drawLabel(measurer, label, Offset(c.x, c.y + keyRadius + 12.dp.toPx()), NeonBoard.Text, small = true)
    }
    drawLabel(measurer, "↩", back, lit)
    drawLabel(measurer, "⏯", play, lit)

    // Other keys, unlit
    for (row in 0..1) {
        for (col in 0..1) {
            drawRoundRect(
                color = line,
                topLeft = Offset(w * (.22f + col * .34f), h * (.74f + row * .1f)),
                size = Size(w * .22f, h * .05f),
                cornerRadius = CornerRadius(h * .025f),
            )
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    small: Boolean = false,
) {
    val layout =
        measurer.measure(
            text,
            TextStyle(color = color, fontSize = if (small) 11.sp else 15.sp, fontWeight = FontWeight.Bold),
        )
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** A plain panel standing in for part of a screen */
@Composable
fun IllustrationPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(4.dp),
    ) { content() }
}

/** A box standing in for a poster or a block of text */
@Composable
private fun Block(
    width: Dp,
    height: Dp,
    color: Color = NeonBoard.Card2,
    border: Color? = null,
) {
    Box(
        Modifier
            .size(width, height)
            .background(color)
            .then(if (border != null) Modifier.border(2.dp, border) else Modifier),
    )
}

@Composable
private fun RowLabel(text: String) {
    Text(text = text.uppercase(), style = NeonType.eyebrow(), color = NeonBoard.Text, maxLines = 1)
}

/** A screen-shaped frame: the stage colour with a thin outline */
@Composable
private fun Screen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .width(360.dp)
                .background(NeonBoard.Stage)
                .border(1.dp, NeonBoard.Line2)
                .padding(14.dp),
    ) { content() }
}

/** Home: the header for the focused poster, then rows of posters */
@Composable
fun HomeIllustration(ctx: TourContext) {
    Screen {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Block(140.dp, 14.dp, NeonBoard.Text.copy(alpha = .8f))
            Block(220.dp, 8.dp, NeonBoard.Mid.copy(alpha = .6f))
            Block(200.dp, 8.dp, NeonBoard.Mid.copy(alpha = .6f))
            Spacer(Modifier.height(6.dp))
            RowLabel(
                if (ctx.isKid) stringResource(R.string.tour_ill_row_next_up_kid) else stringResource(R.string.tour_ill_row_next_up),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Block(52.dp, 78.dp, NeonBoard.Card2, border = NeonBoard.Volt)
                repeat(4) { Block(52.dp, 78.dp) }
                // The arrow card at the end of a full row
                Box(Modifier.size(28.dp, 78.dp).background(NeonBoard.Card), contentAlignment = Alignment.Center) {
                    Text("›", style = NeonType.dialogTitle(), color = NeonBoard.Mid)
                }
            }
            if (!ctx.isKid) {
                RowLabel(stringResource(R.string.tour_ill_row_streaming))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(5) { Block(52.dp, 30.dp, NeonBoard.Card) }
                }
            }
        }
    }
}

/** A title's page: title and details, then its buttons as on the real page (not focusable) */
@Composable
fun TitleIllustration(ctx: TourContext) {
    Screen {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Block(170.dp, 18.dp, NeonBoard.Text.copy(alpha = .85f))
            Block(120.dp, 8.dp, NeonBoard.Mid.copy(alpha = .6f))
            Block(250.dp, 8.dp, NeonBoard.Mid.copy(alpha = .5f))
            Block(230.dp, 8.dp, NeonBoard.Mid.copy(alpha = .5f))
            Spacer(Modifier.height(4.dp))
            val buttons =
                if (ctx.isKid) {
                    listOf(
                        stringResource(R.string.play) to true,
                        stringResource(R.string.tour_ill_start_over) to false,
                        stringResource(R.string.tour_ill_watchlist_kid) to false,
                    )
                } else {
                    listOf(
                        stringResource(R.string.resume) to true,
                        stringResource(R.string.restart) to false,
                        stringResource(R.string.add_favorite) to false,
                        "⋯" to false,
                    )
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons.forEach { (label, primary) -> FakeButton(label, primary) }
            }
            Spacer(Modifier.height(2.dp))
            RowLabel(stringResource(R.string.more_like_this))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(5) { Block(52.dp, 40.dp) } }
        }
    }
}

@Composable
private fun FakeButton(
    label: String,
    primary: Boolean,
) {
    Box(
        modifier =
            Modifier
                .background(if (primary) NeonBoard.Volt else NeonBoard.Card)
                .border(1.dp, if (primary) NeonBoard.Volt else NeonBoard.Line2)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = NeonType.badge(),
            color = if (primary) NeonBoard.OnAccent else NeonBoard.Text,
            maxLines = 1,
        )
    }
}

/** The player with its controls showing: progress, rewind / play / forward, then CC, audio and ⚙ */
@Composable
fun PlayerIllustration(ctx: TourContext) {
    Box(
        modifier =
            Modifier
                .width(360.dp)
                .height(210.dp)
                .background(NeonBoard.Video)
                .border(1.dp, NeonBoard.Line2),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
        ) {
            Block(150.dp, 12.dp, NeonBoard.Text.copy(alpha = .85f))
            Box(Modifier.width(332.dp).height(4.dp).background(NeonBoard.Line2)) {
                Box(Modifier.width(120.dp).height(4.dp).background(NeonBoard.Volt))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerIcon(R.drawable.baseline_fast_rewind_24)
                PlayerIcon(R.drawable.baseline_pause_24, lit = true)
                PlayerIcon(R.drawable.baseline_fast_forward_24)
                Spacer(Modifier.width(40.dp))
                PlayerIcon(R.drawable.captions_svgrepo_com)
                Text(
                    text = stringResource(R.string.fa_volume_high),
                    fontFamily = FontAwesome,
                    color = NeonBoard.Text,
                    fontSize = 18.sp,
                )
                PlayerIcon(R.drawable.vector_settings)
            }
        }
        if (ctx.externalPlayer) {
            Box(Modifier.matchParentSize().background(NeonBoard.Scrim))
        }
    }
}

@Composable
private fun PlayerIcon(
    res: Int,
    lit: Boolean = false,
) {
    Icon(
        painter = painterResource(res),
        contentDescription = null,
        tint = if (lit) NeonBoard.Volt else NeonBoard.Text,
        modifier = Modifier.size(24.dp),
    )
}

/** Search: the box with the mic button, then results */
@Composable
fun SearchIllustration(ctx: TourContext) {
    Screen {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .width(250.dp)
                            .border(2.dp, NeonBoard.Volt)
                            .background(NeonBoard.Well)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.tour_ill_search), style = NeonType.body(15.sp), color = NeonBoard.Mid)
                }
                Box(
                    modifier = Modifier.size(38.dp).border(1.dp, NeonBoard.Line2).background(NeonBoard.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.fa_microphone), fontFamily = FontAwesome, color = NeonBoard.Text, fontSize = 16.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RowLabel(stringResource(R.string.tour_ill_library))
                ctx.requestsLabel?.let { Text(it.uppercase(), style = NeonType.eyebrow(), color = NeonBoard.Mid) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(5) { Block(52.dp, 78.dp) } }
        }
    }
}
