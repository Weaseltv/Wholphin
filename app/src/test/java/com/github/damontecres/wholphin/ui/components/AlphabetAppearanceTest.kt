package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.detail.AlphabetButtons
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
@OptIn(ExperimentalTestApi::class)
class AlphabetAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `alphabet hover uses each page color and glow while inactive letters stay white`() {
        val accent = mutableStateOf(NeonBoard.Orange)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent.value) {
                    Box(Modifier.fillMaxSize()) { AlphabetButtons("#AB", 'A', {}, Modifier.heightIn(max = 500.dp)) }
                }
            }
        }
        for (color in listOf(NeonBoard.Orange, NeonBoard.Cyan, NeonBoard.Red, NeonBoard.Violet, NeonBoard.Fuchsia, NeonBoard.Yellow)) {
            compose.runOnIdle { accent.value = color }
            compose.onRoot().performMouseInput { moveTo(Offset(200f, 200f)) }
            compose.waitForIdle()
            assertEquals(Color.White, layout("B").layoutInput.style.color)
            compose.onNodeWithText("B").performMouseInput { moveTo(center) }
            compose.waitForIdle()
            assertEquals(color, layout("B").layoutInput.style.color)
            assertNotNull(layout("B").layoutInput.style.shadow)
            assertEquals(color, layout("A").layoutInput.style.color)
            compose.onNodeWithText("B").performMouseInput { exit() }
            compose.waitForIdle()
        }
    }

    @Test fun `alphabet glyphs fit their cells at enlarged text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize()) { AlphabetButtons("#AMW", 'A', {}, Modifier.heightIn(max = 500.dp)) }
                }
            }
        }
        for (letter in listOf("#", "A", "M", "W")) {
            val result = layout(letter)
            val b = compose.onNodeWithText(letter).getUnclippedBoundsInRoot()
            assertTrue("Full $letter glyph fits vertically", result.getLineBottom(0) <= b.bottom.value - b.top.value + .5f)
            assertTrue("Full $letter glyph fits horizontally", result.getLineRight(0) <= b.right.value - b.left.value + .5f)
        }
    }

    private fun layout(letter: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(letter).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `full alphabet scrolls to complete final letter at enlarged text`() {
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize()) {
                        AlphabetButtons("#ABCDEFGHIJKLMNOPQRSTUVWXYZ", '#', {}, Modifier.heightIn(max = 300.dp))
                    }
                }
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNode(hasText("#") and isFocusable()).requestFocus()
        compose.onNode(hasText("#") and isFocusable()).assertIsFocused()
        repeat(26) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = compose.onNode(hasText("Z") and isFocusable())
        last.assertIsFocused()
        val b = last.getUnclippedBoundsInRoot()
        assertTrue("Complete final letter fits: $b", b.top.value >= 0 && b.bottom.value <= 300)
    }
}
