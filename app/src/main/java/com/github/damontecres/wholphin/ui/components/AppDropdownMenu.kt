package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/** Sort and nested filter popups share the app's dark menu surface. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    containerColor: Color = MenuDefaults.containerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val neon = isWeaselTv()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        shape = if (neon) RectangleShape else MenuDefaults.shape,
        containerColor = if (neon) NeonBoard.Card else containerColor,
        tonalElevation = if (neon) 0.dp else MenuDefaults.TonalElevation,
        content = content,
    )
}
