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
    internal var focusedTarget: RequestFocusTarget? = null
    var dialogs by mutableIntStateOf(0)
    val textInputActive: Boolean get() = textFields.values.any { it }
    fun field(token: Any, focused: Boolean) { if (focused) textFields[token] = true else textFields.remove(token) }

    internal fun captureFocus(): (() -> Boolean)? {
        val restoreRegion = focusedRegion?.capture()
        val restoreTarget = focusedTarget?.capture() ?: return restoreRegion
        return {
            // Re-enter the page first, so an ancestor's tab/group entry policy
            // cannot replace the explicit button request that follows it.
            val regionRestored = restoreRegion?.invoke() == true
            restoreTarget() || regionRestored
        }
    }
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

/** A stable control survives label/status changes; disposed controls are never requested. */
internal class RequestFocusTarget(val requester: FocusRequester) {
    var attached = false
    var focused = false

    fun capture(): (() -> Boolean)? = if (attached && focused) {
        { attached && requester.tryRequestFocus() }
    } else null
}

/** Remember the exact button as well as the surrounding page/rail focus region. */
fun Modifier.rememberRequestFocusTarget(requester: FocusRequester? = null): Modifier = composed {
    val ui = LocalRequestUiState.current
    val localRequester = remember { FocusRequester() }
    val target = remember(ui, requester) { RequestFocusTarget(requester ?: localRequester) }
    DisposableEffect(target) {
        target.attached = true
        onDispose {
            target.attached = false
            if (ui.focusedTarget === target) ui.focusedTarget = null
        }
    }
    this.focusRequester(target.requester).onFocusChanged {
        target.focused = it.isFocused
        if (it.isFocused) ui.focusedTarget = target
        else if (ui.focusedTarget === target) ui.focusedTarget = null
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
