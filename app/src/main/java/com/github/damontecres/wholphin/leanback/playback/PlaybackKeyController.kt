package com.github.damontecres.wholphin.leanback.playback

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.github.damontecres.wholphin.preferences.DpadSeekMode
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.HOLD_TO_SEEK_REPEAT_START_COUNT
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.calculateSeekAccelerationMultiplier
import com.github.damontecres.wholphin.ui.seekBack
import com.github.damontecres.wholphin.ui.seekForward
import kotlin.time.Duration

/**
 * The player page's key handling with [android.view.KeyEvent] (no-Compose port of `PlaybackKeyHandler`, same rules).
 *
 * The old handler sat on the player box as an `onKeyEvent` (after the focused control). The fragment calls
 * [onKeyEvent] from `onPreviewKeyEvent` only while focus is in the player box, and passes [controlsVisible] so the
 * D-pad/OK keys of a focused control are never consumed here (the old handler only acted on them with the controls
 * hidden, when nothing but the player box has focus). BACK is handled by the fragment's `onBackPressed`.
 */
@OptIn(UnstableApi::class)
internal class PlaybackKeyController(
    private val player: Player,
    private val skipWithLeftRight: Boolean,
    private val seekBack: Duration,
    private val seekForward: Duration,
    private val getDurationMs: () -> Long,
    private val controllerViewState: ControllerViewState,
    private val updateSkipIndicator: (Long) -> Unit,
    private val clearSkipIndicator: () -> Unit,
    private val skipBackOnResume: Duration?,
    private val oneClickPause: Boolean,
    private val onInteraction: () -> Unit,
    private val onStop: () -> Unit,
    private val onPlaybackDialogTypeClick: (PlaybackDialogType) -> Unit,
    private val isDpadSeekVisible: () -> Boolean,
    private val onDpadSeek: (Long) -> Unit,
    private val dpadSeekMode: DpadSeekMode,
) {
    private var leftHandledByRepeat = false
    private var rightHandledByRepeat = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        val keyUp = event.action == KeyEvent.ACTION_UP
        if (keyUp) onInteraction.invoke()

        if (handleHoldSkip(event)) {
            return true
        } else if (!keyUp) {
            return false
        }

        var result = true
        if (PlaybackKeys.isDirectionalDpad(event) || PlaybackKeys.isEnterKey(event) || PlaybackKeys.isControllerMedia(event)) {
            if (!controllerViewState.controlsVisible) {
                if (skipWithLeftRight && PlaybackKeys.isSkipBack(event)) {
                    seekBy(-seekBack)
                } else if (skipWithLeftRight && PlaybackKeys.isSkipForward(event)) {
                    seekBy(seekForward)
                } else if (PlaybackKeys.isEnterKey(event) && isDpadSeekVisible()) {
                    // If d-pad seek bar is visible, hide it
                    clearSkipIndicator.invoke()
                } else if (oneClickPause && PlaybackKeys.isEnterKey(event)) {
                    val wasPlaying = player.isPlaying
                    Util.handlePlayPauseButtonAction(player)
                    if (!wasPlaying) {
                        skipBackOnResume?.let {
                            player.seekBack(it)
                        }
                    }
                } else {
                    controllerViewState.showControls()
                }
            } else {
                // When the controller is visible, its focused control handles the key (and pulses the controls)
                result = false
            }
        } else if (PlaybackKeys.isMedia(event)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // no-op, MediaSession will handle (it acts on the key down, which is not consumed)
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    player.seekForward(seekForward)
                    updateSkipIndicator(seekForward.inWholeMilliseconds)
                }

                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    player.seekBack(seekBack)
                    updateSkipIndicator(-seekBack.inWholeMilliseconds)
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) player.seekToNext()
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) player.seekToPrevious()
                }

                KeyEvent.KEYCODE_CAPTIONS -> {
                    onPlaybackDialogTypeClick.invoke(PlaybackDialogType.CAPTIONS)
                }

                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                    onPlaybackDialogTypeClick.invoke(PlaybackDialogType.AUDIO)
                }

                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    onStop.invoke()
                }

                else -> {
                    result = false
                }
            }
        } else if (PlaybackKeys.isBackKey(event)) {
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B && controllerViewState.controlsVisible) {
                // A gamepad B hides the controls like BACK (BACK itself goes through the fragment's onBackPressed)
                controllerViewState.hideControls()
            } else {
                result = false
            }
        } else {
            controllerViewState.pulseControls()
            result = false
        }
        return result
    }

    private fun handleHoldSkip(event: KeyEvent): Boolean {
        if (
            controllerViewState.controlsVisible ||
            !skipWithLeftRight ||
            (!PlaybackKeys.isSkipBack(event) && !PlaybackKeys.isSkipForward(event))
        ) {
            return false
        }

        val isBack = PlaybackKeys.isSkipBack(event)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val repeatCount = event.repeatCount
                if (repeatCount > 0) {
                    if (repeatCount < HOLD_TO_SEEK_REPEAT_START_COUNT) {
                        setHandledByRepeat(isBack = isBack, handled = false)
                        return true
                    }
                    val multiplier =
                        calculateSeekAccelerationMultiplier(
                            repeatCount = repeatCount - HOLD_TO_SEEK_REPEAT_START_COUNT,
                            durationMs = normalizedDurationMs(),
                        )
                    setHandledByRepeat(isBack = isBack, handled = true)
                    seekWithMultiplier(isBack = isBack, multiplier = multiplier)
                } else {
                    setHandledByRepeat(isBack = isBack, handled = false)
                }
                true
            }

            KeyEvent.ACTION_UP -> {
                if (!handledByRepeat(isBack = isBack)) {
                    seekWithMultiplier(isBack = isBack, multiplier = 1)
                }
                setHandledByRepeat(isBack = isBack, handled = false)
                true
            }

            else -> {
                false
            }
        }
    }

    private fun seekWithMultiplier(
        isBack: Boolean,
        multiplier: Int,
    ) {
        if (isBack) {
            seekBy(-(seekBack * multiplier))
        } else {
            seekBy(seekForward * multiplier)
        }
    }

    private fun seekBy(duration: Duration) {
        val durationMs = duration.inWholeMilliseconds
        if (dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY) {
            onDpadSeek.invoke(durationMs)
        } else {
            if (duration < Duration.ZERO) {
                player.seekBack(-duration)
            } else {
                player.seekForward(duration)
            }
            updateSkipIndicator(durationMs)
        }
    }

    private fun setHandledByRepeat(
        isBack: Boolean,
        handled: Boolean,
    ) {
        if (isBack) {
            leftHandledByRepeat = handled
        } else {
            rightHandledByRepeat = handled
        }
    }

    private fun handledByRepeat(isBack: Boolean): Boolean = if (isBack) leftHandledByRepeat else rightHandledByRepeat

    private fun normalizedDurationMs(): Long = getDurationMs().coerceAtLeast(0L)
}
