package com.github.damontecres.wholphin.leanback.shell

import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Long press of OK from senders that deliver it as one flagged key event (`KeyEvent.FLAG_LONG_PRESS`) right after the
 * press, without holding the key: phone remote apps and `adb shell input keyevent --longpress`. tv-material's Surface
 * opened its long-click action on that flag; a View's long click only fires from its own timer while the key is held,
 * so those senders got a click instead (eg Select user's profile menu never opened).
 *
 * A remote held down sends the flagged repeat only after the long-press timeout, when the View's timer has already
 * run, so it is left alone.
 */
internal class InjectedLongPress {
    private var swallowUpOf: Int? = null

    /** Handle [event] for [focused]; true when it was consumed */
    fun onKey(
        event: KeyEvent,
        focused: View?,
    ): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && swallowUpOf == code) {
            swallowUpOf = null
            focused?.isPressed = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !event.isLongPress || code !in CONFIRM_KEYS) return false
        if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) return false
        val view = focused?.takeIf { it.isLongClickable && it.isEnabled } ?: return false
        view.cancelLongPress()
        view.performLongClick()
        swallowUpOf = code
        return true
    }

    private companion object {
        val CONFIRM_KEYS =
            setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
    }
}
