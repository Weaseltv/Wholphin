package com.github.damontecres.wholphin.ui.detail.music

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
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.components.DialogListEdge
import com.github.damontecres.wholphin.ui.preferences.ComposablePreference
import com.github.damontecres.wholphin.ui.preferences.PreferenceOptionsDialog
import com.github.damontecres.wholphin.ui.tryRequestFocus

@Composable
fun MusicViewOptionsDialog(
    appPreferences: AppPreferences,
    onDismissRequest: () -> Unit,
    onViewOptionsChange: (AppPreferences) -> Unit,
    onEnableVisualizer: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    val columnState = rememberLazyListState()
    val items = remember { getMusicPreferences() }

    PreferenceOptionsDialog(onDismissRequest) {
        LazyColumn(
            state = columnState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = DialogListEdge),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .focusRequester(focusRequester),
        ) {
            items(items) { pref ->
                pref as AppPreference<AppPreferences, Any>
                val interactionSource = remember { MutableInteractionSource() }
                val value = pref.getter.invoke(appPreferences)
                // Using title is a bit hacky
                when (pref.title) {
                    R.string.show_visualizer -> {
                        ComposablePreference(
                            preference = pref,
                            value = value,
                            onNavigate = {},
                            onValueChange = { newValue ->
                                newValue as Boolean
                                if (newValue) {
                                    onEnableVisualizer.invoke()
                                } else {
                                    onViewOptionsChange.invoke(
                                        pref.setter(
                                            appPreferences,
                                            newValue,
                                        ),
                                    )
                                }
                            },
                            interactionSource = interactionSource,
                            modifier = Modifier,
                            onClickPreference = { pref ->
                            },
                        )
                    }

                    else -> {
                        ComposablePreference(
                            preference = pref,
                            value = value,
                            onNavigate = {},
                            onValueChange = { newValue ->
                                onViewOptionsChange.invoke(pref.setter(appPreferences, newValue))
                            },
                            interactionSource = interactionSource,
                            modifier = Modifier,
                            onClickPreference = { pref ->
                            },
                        )
                    }
                }
            }
        }
    }
}
