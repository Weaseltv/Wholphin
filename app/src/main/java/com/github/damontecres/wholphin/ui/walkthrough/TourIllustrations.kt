package com.github.damontecres.wholphin.ui.walkthrough

import androidx.compose.foundation.Canvas
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
