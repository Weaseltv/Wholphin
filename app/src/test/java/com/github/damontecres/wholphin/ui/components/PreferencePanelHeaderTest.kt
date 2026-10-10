package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.preferences.PreferencePanelHeader
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertFalse
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
class PreferencePanelHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `nested panel headings wrap between words without clipping`() {
        val title = mutableStateOf("Screensaver settings")
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(360.dp)) { PreferencePanelHeader(title.value) }
            }
        }
        for (label in listOf(
            "Screensaver settings",
            "Advanced Settings",
            "Subtitle style",
            "User profile settings",
            "Navigation Drawer Items",
        )) {
            compose.runOnIdle { title.value = label }
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label.uppercase()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            val layout = result.single()
            assertFalse(label, layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = true)
                assertTrue("$label broke inside a word at $end", label[end].isWhitespace())
            }
        }
    }
}
