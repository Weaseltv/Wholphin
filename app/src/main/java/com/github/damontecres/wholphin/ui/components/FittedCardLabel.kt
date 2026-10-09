package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/** Fit long genre/studio names before wrapping, preserving whole words where possible. */
@Composable
fun FittedCardLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val neon = isWeaselTv()
    val style =
        MaterialTheme.typography.titleLarge.copy(
            color = if (neon) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (neon) FontWeight.Normal else FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        val fitted =
            remember(text, style, constraints, measurer) {
                if (!neon) {
                    style
                } else {
                    val sizes = (style.fontSize.value.toInt() downTo 10).map { it.sp }
                    sizes
                        .map { size -> style.copy(fontSize = size, lineHeight = size * 1.2f) }
                        .firstOrNull { candidate ->
                            val measured = measurer.measure(text, candidate, maxLines = 2, constraints = constraints)
                            !measured.hasVisualOverflow && measured.multiParagraph.minIntrinsicWidth <= constraints.maxWidth
                        } ?: style.copy(fontSize = 10.sp, lineHeight = 12.sp)
                }
            }
        Text(
            text = text,
            style = fitted,
            maxLines = if (neon) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
