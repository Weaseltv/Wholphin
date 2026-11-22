package com.github.damontecres.wholphin.preferences

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.playback.isDown
import com.github.damontecres.wholphin.ui.playback.isUp
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch

/**
 * Adds modifiers so that pressing Up on the first element scrolls to the bottom and vice versa
 */
@Composable
fun Modifier.lazyListWrapScrolling(
    state: LazyListState,
    focused: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    firstFocusRequester: FocusRequester,
    lastFocusRequester: FocusRequester,
): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .ifElse(
            isFirst,
            Modifier
                .focusRequester(firstFocusRequester)
                .onKeyEvent {
                    if (focused && isUp(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                            lastFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        ).ifElse(
            isLast,
            Modifier
                .focusRequester(lastFocusRequester)
                .onKeyEvent {
                    if (focused && isDown(it) && it.type == KeyEventType.KeyDown) {
                        scope.launch {
                            state.animateScrollToItem(0)
                            firstFocusRequester.tryRequestFocus()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
        )
}
