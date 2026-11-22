package com.github.damontecres.wholphin.ui.playback

import androidx.annotation.IntRange
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce

/**
 * The visibility state of the playback controls. Can [pulseControls] to show the controls for a specified time.
 *
 * A coroutine must call [observe]. Compose-free (no-Compose UI, 2026-11-22): observe [visible].
 */
class ControllerViewState internal constructor(
    @param:IntRange(from = 0)
    var hideMilliseconds: Long,
    val controlsEnabled: Boolean,
) {
    private val channel = Channel<Long>(CONFLATED)
    private val _visible = MutableStateFlow(false)

    /** Whether the controls are showing, as a flow */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    val controlsVisible get() = _visible.value

    fun showControls(milliseconds: Long = hideMilliseconds) {
        if (controlsEnabled) {
            _visible.value = true
        }
        pulseControls(milliseconds)
    }

    fun hideControls() {
        _visible.value = false
    }

    fun pulseControls(milliseconds: Long = hideMilliseconds) {
        channel.trySend(milliseconds)
    }

    @OptIn(FlowPreview::class)
    suspend fun observe() {
        channel
            .consumeAsFlow()
            .debounce { it }
            .collect {
                _visible.value = false
            }
    }
}
