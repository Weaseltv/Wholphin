package com.github.damontecres.wholphin.leanback.playback

import android.view.KeyEvent

/**
 * Key classification for the player and the slideshow with [android.view.KeyEvent] (no-Compose UI, 2026-11-22).
 * The same key groups as `ui/playback/KeyIdentifier.kt`, which keeps the Compose versions for other old screens.
 */
internal object PlaybackKeys {
    fun isDirectionalDpad(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
            -> true

            else -> false
        }

    fun isDpad(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || isDirectionalDpad(event)

    fun isEnterKey(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_A,
            -> true

            else -> false
        }

    fun isBackKey(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_B

    fun isControllerMedia(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2,
            -> true

            else -> false
        }

    fun isDpadLeft(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT

    fun isDpadRight(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT

    fun isSkipBack(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_L2

    fun isSkipForward(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_R2

    fun isMedia(event: KeyEvent): Boolean =
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
            KeyEvent.KEYCODE_MEDIA_STOP,
            -> true

            else -> false
        }

    fun isUp(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP

    fun isDown(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN
}
