package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.seerr.AddSeerrServerUsername
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
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
    fun `pin credential action fits both its title and explanatory line`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) { PinEntryDialog({}, {}, {}) }
            }
        }
        val button = compose.onNode(hasText("Login via server") and isFocusable())
        button.requestFocus()
        button.assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Login via server", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Credential label stays within its button", title.top >= bounds.top && title.bottom <= bounds.bottom)
        assertTrue("Full button stays in the viewport", bounds.bottom.value <= 532)
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
