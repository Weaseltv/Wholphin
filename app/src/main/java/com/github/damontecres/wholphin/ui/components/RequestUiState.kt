package com.github.damontecres.wholphin.ui.components

import androidx.compose.runtime.*
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import com.github.damontecres.wholphin.ui.tryRequestFocus

/** Shared by every editable field, including search and text inside dialogs. */
@Stable
class RequestUiState {
    private val textFields = mutableStateMapOf<Any, Boolean>()
    internal var focusedRegion: RequestFocusRegion? = null
    var dialogs by mutableIntStateOf(0)
    val textInputActive: Boolean get() = textFields.values.any { it }
    fun field(token: Any, focused: Boolean) { if (focused) textFields[token] = true else textFields.remove(token) }
}
val LocalRequestUiState = staticCompositionLocalOf { RequestUiState() }

/** Keep the page and rail separate: requesting the app root defaults to the profile button. */
internal class RequestFocusRegion(val requester: FocusRequester) {
    var attached = false
    var focused = false

    fun capture(): (() -> Boolean)? {
        if (!attached || !focused) return null
        val saved = runCatching { requester.saveFocusedChild() }.getOrDefault(false)
        return {
            attached && ((saved && runCatching { requester.restoreFocusedChild() }.getOrDefault(false)) || requester.tryRequestFocus())
        }
    }
}

/** Each region restores its own child, then the page's existing row/item restorers take over. */
fun Modifier.rememberRequestFocus(): Modifier = composed {
    val ui = LocalRequestUiState.current
    val region = remember(ui) { RequestFocusRegion(FocusRequester()) }
    DisposableEffect(region) {
        region.attached = true
        onDispose {
            region.attached = false
            if (ui.focusedRegion === region) ui.focusedRegion = null
        }
    }
    this.focusRequester(region.requester)
        .onFocusChanged {
            region.focused = it.hasFocus
            if (it.hasFocus) ui.focusedRegion = region
        }
        .focusRestorer()
        .focusGroup()
}

fun Modifier.trackRequestTextInput(editable: Boolean = true): Modifier = composed {
    val state = LocalRequestUiState.current
    val token = remember { Any() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(state, token, editable, focused) {
        state.field(token, editable && focused)
        onDispose { state.field(token, false) }
    }
    this.onFocusChanged { focused = it.hasFocus }
}
