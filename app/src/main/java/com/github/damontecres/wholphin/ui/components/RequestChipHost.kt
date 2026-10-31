package com.github.damontecres.wholphin.ui.components

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.isPlayback
import com.github.damontecres.wholphin.services.*
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind

private const val REQUEST_CHIP_TIMEOUT_MILLIS = 60_000L

/** Nonmodal root overlay. Native dialog/window focus is checked in addition to shared dialog tracking. */
@Composable
fun BoxScope.RequestChipHost(
    repository: RequestStatusRepository,
    navigation: NavigationManager,
    screensaver: ScreensaverService,
    pageFocus: FocusRequester,
    ownerRowId: Int,
    presentationBlocked: Boolean = false,
) {
    val state by repository.state.collectAsState()
    val saver by screensaver.state.collectAsState()
    val ui = LocalRequestUiState.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val safe = state.ownerRowId == ownerRowId && state.connected && state.notifications.enabled && windowFocused &&
        lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
        !navigation.backStack.lastOrNull().isPlayback && !saver.show && !saver.showDim &&
        !ui.textInputActive && ui.dialogs == 0 && !presentationBlocked
    var events by remember(state.scope) { mutableStateOf<List<RequestEvent>>(emptyList()) }
    var closedKeys by remember(state.scope) { mutableStateOf<Set<String>>(emptySet()) }
    var nextPresentationAt by remember(state.scope) { mutableLongStateOf(0L) }
    var displayed by remember(state.scope) { mutableStateOf(false) }
    var chipFocused by remember(state.scope) { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(0L) }
    var remaining by remember(state.scope) { mutableFloatStateOf(1f) }
    val firstFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    var shownAt by remember { mutableLongStateOf(0L) }
    var armed by remember { mutableStateOf(false) }
    var validPress by remember { mutableStateOf(false) }
    val candidates = state.notifications.pending.filter { e -> e.key !in closedKeys && state.items.any { it.request.id == e.requestId && it.fresh && it.eventKey() == e.key } }

    LaunchedEffect(safe, state.scope, candidates.map { it.key }) {
        if (safe) candidates.forEach { repository.loadMetadata(it.requestId) }
    }

    fun restore() {
        if (!runCatching { pageFocus.restoreFocusedChild() }.getOrDefault(false)) pageFocus.tryRequestFocus()
    }
    fun close(restoreFocus: Boolean = true) {
        if (events.isNotEmpty()) {
            closedKeys = closedKeys + events.map { it.key }
            repository.delivered(events)
        }
        events = emptyList()
        displayed = false
        if (restoreFocus && chipFocused) restore()
    }
    LaunchedEffect(safe, state.scope, candidates.map { it.key }, state.items.filter { row -> events.any { it.requestId == row.request.id } }.map { it.eventKey() to it.fresh }) {
        if (!safe) {
            // Do not consume a chip that was hidden by a dialog, playback, dimming or app stop.
            if (events.isNotEmpty()) {
                events = emptyList()
                displayed = false
                if (chipFocused && windowFocused && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !navigation.backStack.lastOrNull().isPlayback) restore()
            }
        } else if (events.isEmpty() && candidates.isNotEmpty()) {
            delay(maxOf(500L, nextPresentationAt - SystemClock.uptimeMillis())) // Let navigation settle; back off if focus was temporarily refused.
            events = candidates.toList()
            runCatching { pageFocus.saveFocusedChild() }
            shownAt = SystemClock.uptimeMillis()
            lastInput = shownAt
            remaining = 1f
            armed = false
            validPress = false
            displayed = true
        } else if (events.isNotEmpty() && events.none { e -> state.items.any { it.request.id == e.requestId && it.eventKey() == e.key } }) {
            events = emptyList(); displayed = false; if (chipFocused) restore()
        }
    }
    LaunchedEffect(displayed, events.map { it.key }) {
        if (displayed) {
            var focused = false
            repeat(3) {
                if (!focused) { withFrameNanos { }; focused = firstFocus.tryRequestFocus() }
            }
            if (!focused) {
                nextPresentationAt = SystemClock.uptimeMillis() + 5_000
                events = emptyList()
                displayed = false
                return@LaunchedEffect
            }
            while (displayed) {
                val now = SystemClock.uptimeMillis()
                armed = now - shownAt >= 700
                val left = (REQUEST_CHIP_TIMEOUT_MILLIS - (now - lastInput)).coerceAtLeast(0)
                remaining = left.toFloat() / REQUEST_CHIP_TIMEOUT_MILLIS
                if (left == 0L) { close(); break }
                delay(50)
            }
        }
    }
    BackHandler(enabled = displayed && chipFocused) { close() }
    if (displayed && safe && events.isNotEmpty()) {
        val rows = events.mapNotNull { event -> state.items.firstOrNull { it.request.id == event.requestId } }
        val single = rows.singleOrNull().takeIf { events.size == 1 }
        val canWatch = single?.stage?.ready == true && single.jellyfinId != null
        RequestNotificationCard(
            rows = rows,
            eventCount = events.size,
            canWatch = canWatch,
            firstFocus = firstFocus,
            remaining = { remaining },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp, start = 24.dp, end = 24.dp).width(350.dp)
                .focusRequester(chipFocus).onFocusChanged { chipFocused = it.hasFocus }.focusGroup()
                .onPreviewKeyEvent { event ->
                    lastInput = SystemClock.uptimeMillis()
                    if (event.key == Key.Back && chipFocused) {
                        if (event.type == KeyEventType.KeyUp) close()
                        return@onPreviewKeyEvent true
                    }
                    if (event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                            validPress = armed && event.nativeKeyEvent.downTime >= shownAt + 700
                            if (!validPress) return@onPreviewKeyEvent true
                        } else if (event.type == KeyEventType.KeyUp && !validPress) return@onPreviewKeyEvent true
                    }
                    false
                },
            onPrimary = {
                if (armed) {
                    close(false)
                    repository.markOpened(single?.request?.id)
                    val itemId = single?.jellyfinId
                    if (canWatch && itemId != null) navigation.navigateTo(Destination.MediaItem(itemId, if (single.request.type == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE))
                    else navigation.navigateToFromDrawer(Destination.Discover(1, single?.request?.id))
                }
            },
            onDismiss = { if (armed) close() },
        )
    }
}
