package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.NeonRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/** Shared, naturally measured heading for basic, advanced and nested preference panels. */
@Composable
fun PreferencePanelHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    if (isWeaselTv()) {
        Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            FittedPanelTitle(
                title = title.uppercase(),
                style = NeonType.pageTitle(),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            NeonRule(modifier = Modifier.padding(top = 6.dp))
        }
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }
}

/** Fit long dialog and preference titles without consuming the options' viewport. */
@Composable
fun FittedPanelTitle(
    title: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fitted =
            remember(title, style, constraints, measurer) {
                (style.fontSize.value.toInt() downTo 18)
                    .map { style.copy(fontSize = it.sp, lineHeight = (it * 1.25f).sp) }
                    .firstOrNull {
                        val layout = measurer.measure(title, it, maxLines = 3, constraints = constraints)
                        !layout.hasVisualOverflow && layout.multiParagraph.minIntrinsicWidth <= constraints.maxWidth
                    } ?: style.copy(fontSize = 18.sp, lineHeight = 22.5.sp)
            }
        Text(
            text = title,
            style = fitted,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
