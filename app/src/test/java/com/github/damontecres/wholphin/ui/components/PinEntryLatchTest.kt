package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.setup.ChooserPinPrompt
import com.github.damontecres.wholphin.ui.setup.ChooserPrompt
import com.github.damontecres.wholphin.ui.setup.PinEntry
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.PinPurpose
import com.github.damontecres.wholphin.ui.setup.forgotPinUrl
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** The PIN entry's own latch (review round 2): it can't stick, and nothing typed during a check counts. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinEntryLatchTest {
    @get:Rule val compose = createComposeRule()

    private val arrows = listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft)

    private fun entry(
        onSubmit: suspend (String) -> Unit,
        lockedUntilMillis: Long = 0L,
        onClickServerAuth: (() -> Unit)? = null,
        outcome: PinOutcome = PinOutcome.OTHER,
    ) {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(600.dp)) {
                    PinEntry(
                        onSubmit = {
                            onSubmit(it)
                            outcome
                        },
                        lockedUntilMillis = lockedUntilMillis,
                        onClickServerAuth = onClickServerAuth,
                    )
                }
            }
        }
    }

    private fun press(keys: List<Key>) {
        compose.onNode(isFocused()).performKeyInput { keys.forEach { pressKey(it) } }
        compose.waitForIdle()
    }

    @Test
    fun `nothing typed during a check counts, and the latch releases when it ends`() {
        val submitted = mutableListOf<String>()
        val check = CompletableDeferred<Unit>()
        var serverAuth = 0
        entry(onSubmit = {
            submitted += it
            if (submitted.size == 1) check.await()
        }, onClickServerAuth = { serverAuth++ })
        press(arrows)
        // While checking: no new entry, no partial guess, no server sign-in that would cancel it
        press(listOf(Key.DirectionCenter, Key.DirectionUp, Key.DirectionUp, Key.DirectionCenter))
        assertEquals(listOf("URDL"), submitted)
        assertEquals(0, serverAuth)
        // The check ends (eg a wrong PIN): the boxes are clear and the entry takes presses again
        check.complete(Unit)
        compose.waitForIdle()
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))
        assertEquals(listOf("URDL", "L"), submitted)
    }

    @Test
    fun `a fourth press followed straight away by OK submits once`() {
        val submitted = mutableListOf<String>()
        val check = CompletableDeferred<Unit>()
        entry(onSubmit = {
            submitted += it
            check.await()
        })
        // One input batch: the OK arrives before anything recomposes
        press(arrows + Key.DirectionCenter)
        assertEquals(listOf("URDL"), submitted)
        check.complete(Unit)
    }

    @Test
    fun `the latch releases when the check throws, with a retryable error`() {
        val submitted = mutableListOf<String>()
        entry(onSubmit = {
            submitted += it
            if (submitted.size == 1) throw IllegalStateException("storage failed")
        })
        press(arrows)
        compose.onNodeWithText("Something went wrong. Try again.").assertIsDisplayed()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        assertEquals(listOf("URDL", "DDDD"), submitted)
    }

    @Test
    fun `a lockout ending now or already over leaves the entry usable`() {
        val submitted = mutableListOf<String>()
        // The boundary: no time left to wait
        entry(onSubmit = { submitted += it }, lockedUntilMillis = System.currentTimeMillis())
        press(arrows)
        assertEquals(listOf("URDL"), submitted)
    }

    @Test
    fun `a lockout ignores presses until it ends`() {
        val submitted = mutableListOf<String>()
        entry(onSubmit = { submitted += it }, lockedUntilMillis = System.currentTimeMillis() + 400)
        press(arrows)
        assertEquals(emptyList<String>(), submitted)
        Thread.sleep(450)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        press(arrows)
        assertEquals(listOf("URDL"), submitted)
    }

    @Test
    fun `a replacement prompt gets a fresh entry, a wrong try keeps it`() {
        val server = UUID.randomUUID()
        val kid = JellyfinUser(rowId = 2, id = UUID.randomUUID(), name = "Abby", serverId = server, accessToken = "k", pin = "UUUU")
        val parent = JellyfinUser(rowId = 1, id = UUID.randomUUID(), name = "Justin M", serverId = server, accessToken = "p", pin = "DDDD")
        val submitted = mutableListOf<String>()
        val never = CompletableDeferred<Unit>()
        var prompt by mutableStateOf(ChooserPrompt.Pin(target = kid, pinOwner = kid, purpose = PinPurpose.SWITCH, id = 1))
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ChooserPinPrompt(prompt = prompt, onDismissRequest = {}, onClickServerAuth = {}, onSubmit = {
                    submitted += it
                    never.await()
                    PinOutcome.OTHER
                })
            }
        }
        press(arrows)
        assertEquals(listOf("URDL"), submitted)
        // Same prompt, new message: still the same entry, still latched
        prompt = prompt.copy(message = "Wrong PIN. 4 tries left before a pause.")
        compose.waitForIdle()
        press(arrows)
        assertEquals(listOf("URDL"), submitted)
        // Escalated to the parent's PIN: a new prompt, a fresh entry
        prompt = ChooserPrompt.Pin(target = kid, pinOwner = parent, purpose = PinPurpose.SWITCH, id = 2)
        compose.waitForIdle()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
        assertEquals(listOf("URDL", "DDDD"), submitted)
    }

    @Test
    fun `the forgot-PIN hint shows from the fourth wrong entry`() {
        Assume.assumeTrue(forgotPinUrl() != null)
        val submitted = mutableListOf<String>()
        entry(onSubmit = { submitted += it }, outcome = PinOutcome.WRONG)
        repeat(PinRules.FORGOT_PIN_AFTER_WRONG) { press(arrows) }
        assertEquals(PinRules.FORGOT_PIN_AFTER_WRONG, submitted.size)
        compose.onNodeWithText("Forgot your PIN?", substring = true).assertDoesNotExist()
        press(arrows)
        compose.onNodeWithText("Forgot your PIN?", substring = true).assertIsDisplayed()
        compose.onNodeWithText("theweasel.tv/weaselplex/profiles", substring = true).assertIsDisplayed()
    }

    @Test
    fun `entries that aren't wrong never offer the forgot-PIN hint`() {
        entry(onSubmit = {})
        repeat(PinRules.FORGOT_PIN_AFTER_WRONG + 2) { press(arrows) }
        compose.onNodeWithText("Forgot your PIN?", substring = true).assertDoesNotExist()
    }
}
