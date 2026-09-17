package com.github.damontecres.wholphin.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/**
 * Placeholder for [com.github.damontecres.wholphin.ui.cards.ItemRow]. It is [focusable] so it can be scrolled.
 */
@Composable
@NonRestartableComposable
fun FocusableItemRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) = FocusableItemRow(
    titleContent = {
        ItemRowTitle(
            title = title,
            accent = if (isError) NeonBoard.Red else LocalNeonAccent.current,
            modifier = Modifier.padding(start = 0.dp),
        )
    },
    subtitleContent = {
        Text(
            text = subtitle,
            style = if (isWeaselTv()) NeonType.rowSubtitle() else MaterialTheme.typography.titleMedium,
            color =
                when {
                    isError -> MaterialTheme.colorScheme.error
                    isWeaselTv() -> NeonBoard.Mid
                    else -> MaterialTheme.colorScheme.onBackground
                },
            modifier = Modifier.padding(start = 8.dp),
        )
    },
    modifier = modifier,
)

@Composable
fun FocusableItemRow(
    titleContent: @Composable () -> Unit,
    subtitleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val background by animateColorAsState(
        if (focused) {
            if (isWeaselTv()) NeonBoard.chipOn(LocalNeonAccent.current) else MaterialTheme.colorScheme.border.copy(alpha = .25f)
        } else {
            Color.Unspecified
        },
    )
    val shape = if (isWeaselTv()) RectangleShape else RoundedCornerShape(8.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .padding(start = 8.dp)
                .focusable(interactionSource = interactionSource)
                .background(background, shape = shape)
                .padding(8.dp),
    ) {
        titleContent.invoke()
        subtitleContent.invoke()
    }
}
