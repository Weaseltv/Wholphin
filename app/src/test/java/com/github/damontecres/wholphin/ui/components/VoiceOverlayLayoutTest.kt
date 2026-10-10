package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceOverlayLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `voice starting state establishes focus without a retry action`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                VoiceSearchOverlay(0f, "", true, false, null, false, {}, {})
            }
        }
        compose.waitForIdle()
        compose.onNode(isFocused()).assertIsDisplayed()
    }

    @Test fun `long voice errors scroll while retry and cancel hint stay within the panel`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    VoiceSearchOverlay(0f, "", false, false, "A voice recognition error with detailed guidance. ".repeat(30), true, {}, {})
                }
            }
        }
        repeat(60) {
            compose.onNode(isFocused()).performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Final voice error guidance is reachable", scroll.value() == scroll.maxValue())
        val retry = compose.onNode(hasText("Retry") and isFocusable())
        retry.requestFocus()
        retry.assertIsDisplayed()
        val b = retry.getUnclippedBoundsInRoot()
        assertTrue("Retry fits: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.right.value <= 960)
        val hint = compose.onNodeWithText("Press back to cancel").getUnclippedBoundsInRoot()
        assertTrue("Cancel hint fits: $hint", hint.top.value >= 0 && hint.bottom.value <= 540)
    }

    @Test fun `listening and processing retain a focused bounded status without retry`() {
        val processing = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    VoiceSearchOverlay(.5f, "A spoken title with several words", false, processing.value, null, false, {}, {})
                }
            }
        }
        for (value in listOf(false, true)) {
            compose.runOnIdle { processing.value = value }
            val b = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            assertTrue("Voice status fits: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.right.value <= 960)
        }
    }
}
