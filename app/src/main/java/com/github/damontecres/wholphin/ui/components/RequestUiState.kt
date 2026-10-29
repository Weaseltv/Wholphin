package com.github.damontecres.wholphin.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged

/** Shared by every editable field, including search and text inside dialogs. */
@Stable
class RequestUiState {
    private val textFields = mutableStateMapOf<Any, Boolean>()
    var dialogs by mutableIntStateOf(0)
    val textInputActive: Boolean get() = textFields.values.any { it }
    fun field(token: Any, focused: Boolean) { if (focused) textFields[token] = true else textFields.remove(token) }
}
val LocalRequestUiState = staticCompositionLocalOf { RequestUiState() }

fun Modifier.trackRequestTextInput(): Modifier = composed {
    val state = LocalRequestUiState.current
    val token = remember { Any() }
    DisposableEffect(state, token) { onDispose { state.field(token, false) } }
    this.onFocusChanged { state.field(token, it.hasFocus) }
}
