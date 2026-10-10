package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.preferences.QuickConnectDialog
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
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
class QuickConnectLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `invalid quick connect keeps its message and submit button visible`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    QuickConnectDialog({ error("Invalid code must not be sent") }, {})
                }
            }
        }
        val submit = compose.onNode(hasText("Submit") and isFocusable())
        submit.requestFocus()
        submit.performSemanticsAction(SemanticsActions.OnClick) { it() }
        val message = compose.onNodeWithText("Code must be 6 digits")
        message.assertIsDisplayed()
        val button = submit.getUnclippedBoundsInRoot()
        assertTrue("Validation stays above the submit button", message.getUnclippedBoundsInRoot().bottom <= button.top)
        assertTrue("Complete button remains within the screen: $button", button.bottom.value <= 540)
    }
}
