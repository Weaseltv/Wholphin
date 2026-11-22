package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.ui.preferences.ComposablePreference
import com.github.damontecres.wholphin.ui.preferences.PreferenceOptionsDialog
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * A dialog that shows the controls for making changes to a [ViewOptions] object. The caller must manage the state of the [ViewOptions].
 *
 * It displays the [AppPreference] objects from [ViewOptions.GRID_OPTIONS]
 */
@Composable
fun ViewOptionsDialog(
    viewOptions: ViewOptions,
    onDismissRequest: () -> Unit,
    onViewOptionsChange: (ViewOptions) -> Unit,
    defaultViewOptions: ViewOptions = ViewOptions(),
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val columnState = rememberLazyListState()
    val options =
        remember(viewOptions.type) {
            when (viewOptions.type) {
                ViewOptionsType.GRID -> ViewOptions.GRID_OPTIONS
                ViewOptionsType.LIST -> ViewOptions.LIST_OPTIONS
                ViewOptionsType.DENSE_LIST -> ViewOptions.LIST_OPTIONS
            }
        }
    PreferenceOptionsDialog(onDismissRequest) {
        LazyColumn(
            state = columnState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = DialogListEdge),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.weight(1f, fill = false).focusRequester(focusRequester),
        ) {
            items(options, key = { it.title }) { pref ->
                pref as AppPreference<ViewOptions, Any>
                val interactionSource = remember { MutableInteractionSource() }
                val value = pref.getter.invoke(viewOptions)
                ComposablePreference(
                    preference = pref,
                    value = value,
                    onNavigate = {},
                    onValueChange = { newValue ->
                        onViewOptionsChange.invoke(pref.setter(viewOptions, newValue))
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier.keepFocusedItemVisible(),
                    onClickPreference = { pref ->
                        if (pref == ViewOptions.ViewOptionsReset) {
                            onViewOptionsChange.invoke(defaultViewOptions)
                        }
                    },
                )
            }
        }
    }
}
