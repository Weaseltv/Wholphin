package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.util.LocalClock

/** Space shared page headings must leave for the independently overlaid clock. */
@Composable
internal fun clockHeaderEndPadding(): Dp {
    val timeString by LocalClock.current.timeString
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val clockWidth =
        measurer
            .measure(
                timeString.ifBlank { "12:59 PM" },
                MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
            ).size.width
    return with(density) { clockWidth.toDp() } + 40.dp
}

/**
 * Displays the [LocalClock] in the upper right corner of the parent [androidx.compose.foundation.layout.Box]
 */
@Composable
fun BoxScope.TimeDisplay(modifier: Modifier = Modifier) {
    val timeString by LocalClock.current.timeString
    Text(
        text = timeString,
        fontSize = 18.sp,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            modifier
                .align(Alignment.TopEnd)
                .padding(vertical = 16.dp, horizontal = 24.dp),
    )
}
