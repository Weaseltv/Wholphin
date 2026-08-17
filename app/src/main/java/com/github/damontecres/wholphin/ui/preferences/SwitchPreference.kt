package com.github.damontecres.wholphin.ui.preferences

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchColors
import androidx.tv.material3.SwitchDefaults
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.theme.colors.WeaselTvColors
import androidx.compose.ui.graphics.Color
import com.github.damontecres.wholphin.ui.theme.weaselListItemBorder

@Composable
fun SwitchPreference(
    title: String,
    value: Boolean,
    onClick: () -> Unit,
    summaryOn: String?,
    summaryOff: String?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = SwitchPreference(
    title = title,
    value = value,
    onClick = onClick,
    modifier = modifier,
    summary = if (value) summaryOn else summaryOff,
    onLongClick = onLongClick,
    interactionSource = interactionSource,
)

@Composable
fun SwitchPreference(
    title: String,
    value: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    ListItem(
        border = weaselListItemBorder(),
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = {
            PreferenceTitle(title)
        },
        supportingContent = {
            PreferenceSummary(summary)
        },
        trailingContent = {
            Switch(
                checked = value,
                onCheckedChange = { onClick.invoke() },
                colors = SwitchColors(),
            )
        },
        interactionSource = interactionSource,
        modifier = modifier,
    )
}

@Composable
fun SwitchColors(): SwitchColors {
    val theme = LocalTheme.current
    return when (theme) {
        AppThemeColors.UNRECOGNIZED,
        AppThemeColors.PURPLE,
        AppThemeColors.BLUE,
        -> {
            SwitchDefaults.colors()
        }

        AppThemeColors.GREEN,
        AppThemeColors.ORANGE,
        AppThemeColors.BOLD_BLUE,
        AppThemeColors.OLED_BLACK,
        AppThemeColors.RED,
        AppThemeColors.BROWN,
        -> {
            SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
            )
        }

        AppThemeColors.WEASELTV -> {
            SwitchDefaults.colors(
                checkedTrackColor = WeaselTvColors.NeonCyan,
                checkedThumbColor = Color.White,
                checkedBorderColor = WeaselTvColors.NeonCyan,
                uncheckedTrackColor = Color.White.copy(alpha = .14f),
                uncheckedThumbColor = WeaselTvColors.TextMuted,
                uncheckedBorderColor = WeaselTvColors.Hairline,
            )
        }
    }
}
