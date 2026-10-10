package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.preferences.resetSubtitles
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitlePreferencesContent
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HdrSubtitleLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `hdr style panel keeps every preference within its narrow viewport`() {
        val vm = mockk<PreferencesViewModel>(relaxed = true)
        val prefs = SubtitlePreferences.newBuilder().apply { resetSubtitles() }.build()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    SubtitlePreferencesContent(
                        "HDR subtitle style",
                        prefs,
                        SubtitleSettings.preferences,
                        {},
                        Modifier.width(320.dp).fillMaxHeight(),
                        viewModel = vm,
                    )
                }
            }
        }
        val labels =
            SubtitleSettings.preferences
                .flatMap {
                    it.preferences
                }.map {
                    org.robolectric.RuntimeEnvironment
                        .getApplication()
                        .getString(it.title)
                }
        for ((index, label) in labels.withIndex()) {
            val item = compose.onNode(isFocused(), useUnmergedTree = true)
            val matcher =
                hasText(label) or hasContentDescription(label) or
                    hasAnyAncestor(hasContentDescription(label)) or hasAnyDescendant(hasText(label))
            assertTrue("Focused preference is $label", matcher.matches(item.fetchSemanticsNode()))
            item.assertIsDisplayed()
            val b = item.getUnclippedBoundsInRoot()
            assertTrue("$label fits: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.right.value <= 320)
            if (index < labels.lastIndex) {
                item.performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
            }
        }
        compose.onNodeWithText("HDR SUBTITLE STYLE").assertIsDisplayed()
    }
}
