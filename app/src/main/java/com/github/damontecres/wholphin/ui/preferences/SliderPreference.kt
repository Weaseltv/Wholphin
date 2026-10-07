package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.contentColorFor
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.ui.components.SliderBar
import com.github.damontecres.wholphin.ui.components.SliderColors
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent

@Composable
fun SliderPreference(
    preference: AppSliderPreference<*>,
    title: String,
    summary: String?,
    value: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    summaryBelow: Boolean = false,
    additionalSummary: @Composable (ColumnScope.() -> Unit)? = null,
) {
    val neon = isWeaselTv()
    val focused = interactionSource.collectIsFocusedAsState().value
    val background =
        if (neon) {
            Color.Black
        } else if (focused) {
            MaterialTheme.colorScheme.inverseSurface
        } else {
            Color.Unspecified
        }
    val contentColor = if (neon) NeonBoard.Text else contentColorFor(background)

    Column(
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier =
            modifier
                .defaultMinSize(minHeight = 72.dp)
                .fillMaxWidth()
                .background(background, shape = if (neon) RectangleShape else RoundedCornerShape(8.dp))
                .then(if (neon && focused) Modifier.border(1.dp, LocalNeonAccent.current, RectangleShape) else Modifier)
                .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
    ) {
        PreferenceTitle(title, color = contentColor, modifier = Modifier.padding(bottom = 8.dp))

        ProvideTextStyle(PreferenceSummaryStyle.copy(color = contentColor)) {
            additionalSummary?.invoke(this)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            SliderBar(
                value = value,
                min = preference.min,
                max = preference.max,
                interval = preference.interval,
                onChange = onChange,
                enableWrapAround = false,
                outlineColor = if (neon) NeonBoard.IceWhite else null,
                colors = if (neon) SliderColors(NeonBoard.IceWhite, NeonBoard.IceWhite, Color.Black, Color.Black) else SliderColors.default(),
                interactionSource = interactionSource,
                modifier = Modifier.weight(1f),
            )

            if (!summaryBelow) {
                PreferenceSummary(summary, color = contentColor)
            }
        }
        if (summaryBelow) {
            PreferenceSummary(summary, color = contentColor)
        }
    }
}
