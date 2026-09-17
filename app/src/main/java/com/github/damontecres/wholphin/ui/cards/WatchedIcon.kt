package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

@Composable
fun WatchedIcon(
    modifier: Modifier = Modifier,
    padding: Dp = 2.dp,
) {
    // Neon Board: watched is a green SQUARE with a dark check (`02-components-tv.md` § T3).
    val shape = if (isWeaselTv()) RectangleShape else CircleShape
    Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = WatchedIconColor(),
        modifier =
            modifier
                .background(WatchedIconBackground(), shape = shape)
                .border(.5.dp, Color.Black, shape)
                .padding(padding),
    )
}

@Composable
fun WatchedIconBackground(): Color =
    when (LocalTheme.current) {
        AppThemeColors.UNRECOGNIZED,
        AppThemeColors.PURPLE,
        AppThemeColors.BLUE,
        AppThemeColors.GREEN,
        AppThemeColors.ORANGE,
        AppThemeColors.BOLD_BLUE,
        AppThemeColors.RED,
        AppThemeColors.BROWN,
        -> MaterialTheme.colorScheme.border.copy(alpha = 1f)

        AppThemeColors.OLED_BLACK -> MaterialTheme.colorScheme.secondaryContainer

        AppThemeColors.WEASELTV -> NeonBoard.Green
    }

@Composable
fun WatchedIconColor(): Color =
    when (LocalTheme.current) {
        AppThemeColors.UNRECOGNIZED,
        AppThemeColors.PURPLE,
        AppThemeColors.BLUE,
        AppThemeColors.GREEN,
        AppThemeColors.ORANGE,
        AppThemeColors.BOLD_BLUE,
        AppThemeColors.OLED_BLACK,
        AppThemeColors.RED,
        AppThemeColors.BROWN,
        -> Color.White

        // Dark ink on the neon square; white on green is unreadable.
        AppThemeColors.WEASELTV -> NeonBoard.OnAccent
    }

@PreviewTvSpec
@Composable
private fun WatchedIconPreview() {
    WholphinTheme {
        WatchedIcon(Modifier.size(64.dp))
    }
}
