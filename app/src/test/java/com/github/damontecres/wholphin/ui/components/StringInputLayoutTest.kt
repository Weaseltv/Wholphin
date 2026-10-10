package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.preferences.StringInput
import com.github.damontecres.wholphin.ui.preferences.StringInputDialog
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
class StringInputLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w800dp-h280dp-land-mdpi")
    fun `editor actions fit a keyboard reduced viewport`() = checkEditor(280)

    @Test
    fun `long configuration text leaves both action buttons inside the viewport`() = checkEditor(540)

    private fun checkEditor(viewportHeight: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    StringInputDialog(
                        StringInput(
                            "Edit mpv.conf",
                            (1..50).joinToString("\n") { "configuration-$it=value" },
                            KeyboardOptions.Default,
                            {},
                            maxLines = 10,
                        ),
                        {},
                        {},
                    )
                }
            }
        }
        for (label in listOf("Cancel", "Save")) {
            val button = compose.onNode(hasText(label) and isFocusable())
            button.requestFocus()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue("$label must stay visible below the editor: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= viewportHeight)
        }
    }
}
