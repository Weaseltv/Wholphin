package com.github.damontecres.wholphin.leanback.setup

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.forgotPinUrl
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The View PIN entry keeps the old entry's rules (PinEntryLatchTest, SetupLayoutTest): latch, OK, lockout, forgot PIN */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
class PinEntryViewTest {
    private val arrows =
        intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER

    private fun entry(
        onSubmit: suspend (String) -> Unit,
        length: Int = PinRules.LENGTH,
        allowDigits: Boolean = true,
        lockedUntilMillis: Long = 0L,
        onClickServerAuth: (() -> Unit)? = null,
        outcome: PinOutcome = PinOutcome.OTHER,
    ): PinEntryView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view =
            PinEntryView(
                activity,
                PinEntryConfig(
                    onSubmit = {
                        onSubmit(it)
                        outcome
                    },
                    length = length,
                    allowDigits = allowDigits,
                    lockedUntilMillis = lockedUntilMillis,
                    onClickServerAuth = onClickServerAuth,
                ),
            )
        activity.setContentView(view)
        view.requestFocus()
        idle()
        return view
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun PinEntryView.press(vararg codes: Int) {
        codes.forEach {
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, it))
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, it))
        }
        idle()
    }

    private fun View.shownTexts(): List<String> =
        when {
            !isShown -> emptyList()
            this is TextView -> listOf(text.toString())
            this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).shownTexts() }
            else -> emptyList()
        }

    @Test
    fun `the fourth press submits the pin and OK only confirms`() {
        val submitted = mutableListOf<String>()
        var serverAuth = 0
        val view = entry(onSubmit = { submitted += it }, onClickServerAuth = { serverAuth++ })
        view.press(*arrows)
        assertEquals(listOf("URDL"), submitted)
        // With nothing typed, OK is the server sign-in, never a PIN
        view.press(ok)
        assertEquals(1, serverAuth)
        view.press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, ok)
        assertEquals(listOf("URDL", "LL"), submitted)
    }

    @Test
    fun `nothing typed during a check counts, and the latch releases when it ends`() {
        val submitted = mutableListOf<String>()
        val check = CompletableDeferred<Unit>()
        var serverAuth = 0
        val view =
            entry(onSubmit = {
                submitted += it
                if (submitted.size == 1) check.await()
            }, onClickServerAuth = { serverAuth++ })
        view.press(*arrows)
        view.press(ok, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, ok)
        assertEquals(listOf("URDL"), submitted)
        assertEquals(0, serverAuth)
        check.complete(Unit)
        idle()
        view.press(KeyEvent.KEYCODE_DPAD_LEFT, ok)
        assertEquals(listOf("URDL", "L"), submitted)
    }

    @Test
    fun `a fourth press followed straight away by OK submits once`() {
        val submitted = mutableListOf<String>()
        val check = CompletableDeferred<Unit>()
        val view =
            entry(onSubmit = {
                submitted += it
                check.await()
            })
        view.press(*arrows, ok)
        assertEquals(listOf("URDL"), submitted)
        check.complete(Unit)
    }

    @Test
    fun `only a press that started here counts`() {
        val submitted = mutableListOf<String>()
        var serverAuth = 0
        val view = entry(onSubmit = { submitted += it }, onClickServerAuth = { serverAuth++ })
        // The release of the OK that opened the prompt
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, ok))
        idle()
        assertEquals(0, serverAuth)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `the latch releases when the check throws, with a retryable error`() {
        val submitted = mutableListOf<String>()
        val view =
            entry(onSubmit = {
                submitted += it
                if (submitted.size == 1) throw IllegalStateException("storage failed")
            })
        view.press(*arrows)
        assertTrue(view.shownTexts().contains("Something went wrong. Try again."))
        view.press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("URDL", "DDDD"), submitted)
    }

    @Test
    fun `a new pin takes arrows only, a saved one accepts number keys`() {
        val chosen = mutableListOf<String>()
        val newPin = entry(onSubmit = { chosen += it }, allowDigits = false)
        newPin.press(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_2, *arrows)
        assertEquals(listOf("URDL"), chosen)
        val checked = mutableListOf<String>()
        val saved = entry(onSubmit = { checked += it })
        saved.press(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4)
        assertEquals(listOf("1234"), checked)
    }

    @Test
    fun `an older longer pin is collected in full before it is submitted`() {
        val submitted = mutableListOf<String>()
        val view = entry(onSubmit = { submitted += it }, length = 6)
        repeat(5) { view.press(KeyEvent.KEYCODE_DPAD_UP) }
        assertTrue(submitted.isEmpty())
        view.press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("UUUUUD"), submitted)
    }

    @Test
    fun `a lockout ignores presses until it ends, one ending now does not`() {
        val submitted = mutableListOf<String>()
        val locked = entry(onSubmit = { submitted += it }, lockedUntilMillis = System.currentTimeMillis() + 60_000)
        locked.press(*arrows)
        assertTrue(submitted.isEmpty())
        val boundary = entry(onSubmit = { submitted += it }, lockedUntilMillis = System.currentTimeMillis())
        boundary.press(*arrows)
        assertEquals(listOf("URDL"), submitted)
    }

    @Test
    fun `the forgot-PIN hint shows from the fourth wrong entry only`() {
        Assume.assumeTrue(forgotPinUrl() != null)
        val view = entry(onSubmit = {}, outcome = PinOutcome.WRONG)
        repeat(PinRules.FORGOT_PIN_AFTER_WRONG) { view.press(*arrows) }
        assertFalse(view.shownTexts().any { "Forgot your PIN?" in it })
        view.press(*arrows)
        assertTrue(view.shownTexts().any { "Forgot your PIN?" in it && "theweasel.tv/weaselplex/profiles" in it })
    }

    @Test
    fun `entries that aren't wrong never offer the forgot-PIN hint`() {
        val view = entry(onSubmit = {})
        repeat(PinRules.FORGOT_PIN_AFTER_WRONG + 2) { view.press(*arrows) }
        assertFalse(view.shownTexts().any { "Forgot your PIN?" in it })
    }
}
