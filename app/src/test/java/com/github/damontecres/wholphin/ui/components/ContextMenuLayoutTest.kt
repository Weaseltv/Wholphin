package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
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
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextMenuLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long media titles leave space for every context menu action at enlarged text`() {
        val title =
            "The extraordinary adventures of a mysterious traveler through distant worlds and forgotten civilizations " +
                "with friends and family searching for a new home beyond the boundaries of the known universe"
        val labels = List(12) { "Menu action $it with descriptive wording" }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DialogPopupContent(title, labels.map { DialogItem(it, onClick = {}) }, false, {})
                    }
                }
            }
        }
        compose.onNodeWithText(labels.first()).requestFocus()
        compose.waitForIdle()
        for (i in labels.indices) {
            compose.onNode(isFocused()).assert(hasText(labels[i]))
            val b = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            assertTrue("Whole menu action $i fits: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.bottom.value - b.top.value >= 40)
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
    }
}
