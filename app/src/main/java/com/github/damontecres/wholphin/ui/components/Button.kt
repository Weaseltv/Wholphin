// This file was inspired by related Button source files from
// https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3

package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonOutlineColors
import com.github.damontecres.wholphin.ui.theme.neonSurfaceBorder
import com.github.damontecres.wholphin.ui.theme.neonSurfaceGlow

/**
 * This is a re-implementation of [androidx.tv.material3.Button] with altered sizing, padding, colors, etc
 *
 * This allows for creating smaller and fully circular Buttons.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    glow: ClickableSurfaceGlow = neonSurfaceGlow(),
    shape: ClickableSurfaceShape =
        ClickableSurfaceDefaults.shape(
            // Neon Board: square everything except people avatars.
            shape = if (isWeaselTv()) RectangleShape else CircleShape,
        ),
    colors: ClickableSurfaceColors =
        neonOutlineColors(
            fallback =
                ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                    focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    pressedContainerColor = MaterialTheme.colorScheme.onSurface,
                    pressedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                ),
        ),
    tonalElevation: Dp = 0.dp,
    border: ClickableSurfaceBorder =
        neonSurfaceBorder(
            // The outline button: 1dp `line2` at rest, the accent when focused.
            restWidth = 1.dp,
            fallback =
                ClickableSurfaceDefaults.border(
                    border = Border.None,
                    focusedBorder = Border.None,
                    pressedBorder = Border.None,
                    disabledBorder = Border.None,
                    focusedDisabledBorder =
                        Border(
                            border =
                                BorderStroke(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                ),
                            shape = CircleShape,
                        ),
                ),
        ),
    contentPadding: PaddingValues = DefaultButtonPadding,
    contentHeight: Dp = MinButtonSize,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.semantics { role = Role.Button },
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        tonalElevation = tonalElevation,
        border = border,
        interactionSource = interactionSource,
    ) {
        // Button labels: Barlow 16 / 700 on the WeaselTV theme, the stock label elsewhere.
        ProvideTextStyle(value = if (isWeaselTv()) NeonType.button() else MaterialTheme.typography.labelLarge) {
            Row(
                modifier =
                    Modifier
                        .padding(contentPadding)
                        .height(contentHeight),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
@NonRestartableComposable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues =
        PaddingValues(
            start = 8.dp,
            top = 4.dp,
            end = 8.dp,
            bottom = 4.dp,
        ),
    contentHeight: Dp = 32.dp,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    onLongClick = onLongClick,
    enabled = enabled,
    contentPadding = contentPadding,
    contentHeight = contentHeight,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun TextButton(
    @StringRes stringRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues =
        PaddingValues(
            start = 8.dp,
            top = 4.dp,
            end = 8.dp,
            bottom = 4.dp,
        ),
    contentHeight: Dp = 32.dp,
    interactionSource: MutableInteractionSource? = null,
) = Button(
    onClick = onClick,
    modifier = modifier,
    onLongClick = onLongClick,
    enabled = enabled,
    contentPadding = contentPadding,
    contentHeight = contentHeight,
    interactionSource = interactionSource,
    content = {
        Text(text = stringResource(stringRes))
    },
)

val DefaultButtonPadding =
    PaddingValues(
        start = 4.dp,
        top = 4.dp,
        end = 4.dp,
        bottom = 4.dp,
    )
