package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.AvatarGroup
import com.github.damontecres.wholphin.ui.avatars.ProfilePicturePicker
import com.github.damontecres.wholphin.ui.setup.PinEntry
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.seerr.AddSeerrServerUsername
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `value editor grows for text metrics instead of clipping at forty dp`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.padding(24.dp)) { EditTextBox("Readable text", {}, Modifier.width(400.dp)) }
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val text = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
        val bounds = field.getUnclippedBoundsInRoot()
        assertTrue(
            "Actual glyph metrics and field padding fit: $bounds",
            text.isNotEmpty() && (bounds.bottom - bounds.top).value >= text.first().size.height + 16,
        )
    }

    @Test
    fun `pin prompt fits the tv frame with its four boxes and key hints`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PinEntryDialog(onDismissRequest = {}, onSubmit = { PinOutcome.OTHER }, onClickServerAuth = {})
                }
            }
        }
        // With nothing typed, OK is the server sign-in, and the hint says so
        val hint = compose.onNodeWithText("Login via server")
        hint.assertIsDisplayed()
        assertTrue("Key hints stay in the viewport", hint.getUnclippedBoundsInRoot().bottom.value <= 532)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `the fourth press submits the pin and OK only confirms`() {
        val submitted = mutableListOf<String>()
        var serverAuth = 0
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(600.dp)) {
                    PinEntry(onSubmit = {
                        submitted += it
                        PinOutcome.OTHER
                    }, onClickServerAuth = { serverAuth++ })
                }
            }
        }
        val entry = compose.onNode(isFocused())
        entry.performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionLeft)
        }
        compose.waitForIdle()
        assertEquals(listOf("URDL"), submitted)
        // The check is done: OK with nothing typed is the server sign-in, never a PIN
        entry.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, serverAuth)
        assertEquals(listOf("URDL"), submitted)
        // OK confirms what is typed so far; it is not part of the PIN
        entry.performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals(listOf("URDL", "LL"), submitted)
    }

    @Test
    fun `a new pin takes arrows only`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Column {
                    PinEntry(onSubmit = {
                        chosen += it
                        PinOutcome.OTHER
                    }, allowDigits = false)
                }
            }
        }
        compose.onNode(isFocused()).performKeyInput {
            pressKey(Key.One)
            pressKey(Key.NumPad2)
            listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft).forEach { pressKey(it) }
        }
        compose.waitForIdle()
        assertEquals(listOf("URDL"), chosen)
    }

    @Test
    fun `checking a saved pin still accepts number keys`() {
        val checked = mutableListOf<String>()
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Column {
                    PinEntry(onSubmit = {
                        checked += it
                        PinOutcome.OTHER
                    })
                }
            }
        }
        compose.onNode(isFocused()).performKeyInput { listOf(Key.One, Key.Two, Key.Three, Key.Four).forEach { pressKey(it) } }
        compose.waitForIdle()
        assertEquals(listOf("1234"), checked)
    }

    @Test
    fun `pin footer stays on screen with a long title, prompt and error at large text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PinEntryDialog(
                        onDismissRequest = {},
                        onSubmit = { PinOutcome.OTHER },
                        onClickServerAuth = {},
                        length = 8,
                        title = "Turn off kid protection on this TV",
                        prompt = AnnotatedString("Enter Maximilian Alexander-Fitzgerald's PIN to continue"),
                        message = "Too many wrong PINs. Try again in 300 seconds. ".repeat(3),
                    )
                }
            }
        }
        for (hint in listOf("Login via server", "Cancel")) {
            val node = compose.onNodeWithText(hint)
            node.assertIsDisplayed()
            assertTrue("$hint stays in the viewport", node.getUnclippedBoundsInRoot().bottom.value <= 540)
        }
    }

    @Test
    fun `the picture picker opens on the saved avatar even past the first rows`() {
        val avatars = (0 until 48).map { Avatar("avatar-$it", AvatarGroup.KIDS) }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProfilePicturePicker(title = "Change picture", subtitle = null, avatars = avatars, current = "avatar-46", onPick = {})
            }
        }
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasContentDescription("avatar-46"))
    }

    @Test
    fun `an older longer pin is collected in full before it is submitted`() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(600.dp)) {
                    PinEntry(onSubmit = {
                        submitted += it
                        PinOutcome.OTHER
                    }, length = 6)
                }
            }
        }
        compose.onNode(isFocused()).performKeyInput {
            repeat(5) { pressKey(Key.DirectionUp) }
        }
        compose.waitForIdle()
        assertTrue(submitted.isEmpty())
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(listOf("UUUUUD"), submitted)
    }

    @Test
    fun `long request server error stays scrollable with its submit action`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    BasicDialog({}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        AddSeerrServerUsername(
                            { _, _, _ -> error("No account should be submitted") },
                            "Test",
                            LoadingState.Error("Unable to connect to this server. ".repeat(40)),
                            Modifier.width(600.dp),
                        )
                    }
                }
            }
        }
        val button = compose.onNodeWithText("Submit")
        button.performScrollTo()
        button.assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue("Submit remains fully inside the panel viewport: $bounds", bounds.top.value >= 8 && bounds.bottom.value <= 532)
    }
}
