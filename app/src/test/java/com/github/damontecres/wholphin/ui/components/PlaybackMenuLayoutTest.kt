package com.github.damontecres.wholphin.ui.components

import android.app.Application
import android.view.Gravity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.playback.PlaybackDialog
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.PlaybackSettings
import com.github.damontecres.wholphin.ui.playback.SimpleMediaStream
import com.github.damontecres.wholphin.ui.playback.StreamChoiceBottomDialog
import com.github.damontecres.wholphin.ui.playback.SubtitleChoiceBottomDialog
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
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
class PlaybackMenuLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long audio and subtitle menus keep first and last focused entries inside the screen`() {
        val subtitles = mutableStateOf(false)
        val choices =
            List(30) { SimpleMediaStream(it, "Track ${it + 1}", "English descriptive surround audio with a long title ${it + 1}") }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(Color.Cyan) {
                    if (subtitles.value) {
                        SubtitleChoiceBottomDialog(choices, {}, {}, {}, Gravity.END, true, 0)
                    } else {
                        StreamChoiceBottomDialog(choices, {}, { _, _ -> }, Gravity.END, 0)
                    }
                }
            }
        }
        for (subtitle in listOf(false, true)) {
            compose.runOnIdle { subtitles.value = subtitle }
            compose.waitForIdle()
            val first = if (subtitle) "None" else "Track 1"
            val last = if (subtitle) "Search & Download" else "Track 30"
            for (label in listOf(first, last)) {
                // Follow the real remote path. Programmatically scrolling a lazy list
                // while its old entry retains focus makes visibility restoration fight
                // the test scroll; it does not represent D-pad navigation.
                repeat(choices.size + 3) {
                    if (compose.onAllNodes(hasText(label) and isFocused()).fetchSemanticsNodes().isEmpty()) {
                        compose.onNode(isDialog()).performKeyInput {
                            val direction = if (label == first) Key.DirectionUp else Key.DirectionDown
                            keyDown(direction)
                            keyUp(direction)
                        }
                        compose.waitForIdle()
                    }
                }
                compose.onNode(hasText(label) and isFocused()).assertIsDisplayed()
                compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
                assertTrue("$label starts inside viewport: $bounds", bounds.top.value >= 0)
                assertTrue("$label ends inside viewport: $bounds", bounds.bottom.value <= 540)
            }
        }
    }

    @Test
    fun `settings speed scale and delay controls fit at both menu edges`() {
        val type = mutableStateOf(PlaybackDialogType.SETTINGS)
        val settings = PlaybackSettings(false, 0, emptyList(), -1, emptyList(), 1f, ContentScale.Fit, kotlin.time.Duration.ZERO, true, true)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(Color.Red) {
                    PlaybackDialog(true, true, type.value, settings, {}, {}, {}, {}, {})
                }
            }
        }
        for ((menu, labels) in listOf(
            PlaybackDialogType.SETTINGS to listOf("Playback Speed", "Show debug info"),
            PlaybackDialogType.PLAYBACK_SPEED to listOf(".25", "2.0"),
            PlaybackDialogType.VIDEO_SCALE to listOf("Fit", "Fill height"),
            PlaybackDialogType.SUBTITLE_DELAY to listOf("-250ms", "+250ms"),
        )) {
            compose.runOnIdle { type.value = menu }
            compose.waitForIdle()
            for (label in labels) {
                if (menu != PlaybackDialogType.SUBTITLE_DELAY) {
                    compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                }
                compose.onNode(hasText(label) and isFocusable()).requestFocus()
                compose.waitForIdle()
                compose.onNodeWithText(label).assertIsDisplayed()
                val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
                assertTrue(
                    "$menu $label fully inside screen: $bounds",
                    bounds.top.value >= 0 && bounds.bottom.value <= 540 && bounds.left.value >= 0 && bounds.right.value <= 960,
                )
                if (menu == PlaybackDialogType.SUBTITLE_DELAY) {
                    val text = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
                    assertTrue("Delay label must fit on one line within the button: $text", (text.bottom - text.top).value <= 32)
                }
            }
        }
    }
}
