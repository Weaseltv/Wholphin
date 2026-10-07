package com.github.damontecres.wholphin.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.cards.ItemRowTitle
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.FocusableItemRow
import com.github.damontecres.wholphin.ui.components.GridTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render real headers inside the rail gutter and clipped scrolling lists, at one pixel per dp. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeonSectionRuleLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val accent = Color(0xFF00FF00)

    private fun page(
        listPadding: Int = 0,
        endPadding: Int = 8,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent) {
                    Box(Modifier.width(400.dp).height(200.dp).testTag("page")) {
                        // 72dp rail, followed by the existing 24dp page gutter.
                        Column(Modifier.padding(start = 96.dp, end = endPadding.dp)) {
                            InsetNeonSectionRules(start = 24.dp, end = endPadding.dp) {
                                InsetNeonSectionRules(start = listPadding.dp) {
                                    LazyColumn(contentPadding = PaddingValues(horizontal = listPadding.dp)) {
                                        item { content() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertRule(
        tag: String = "page",
        start: Int = 72,
        end: Int = 399,
        expected: Color = accent,
    ) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val rule =
            (0 until pixels.height)
                .map { y ->
                    (0 until pixels.width).filter { x ->
                        val color = pixels[x, y]
                        kotlin.math.abs(color.red - expected.red) < .05f &&
                            kotlin.math.abs(color.green - expected.green) < .05f &&
                            kotlin.math.abs(color.blue - expected.blue) < .05f
                    }
                }.maxBy { it.size }
        assertTrue("a section rule is visible", rule.size > 200)
        assertEquals("rule starts at the content edge", start, rule.first())
        assertEquals("rule ends at the content edge", end, rule.last())
    }

    @Test
    fun `Home row rules reach the rail while labels keep their inset`() {
        page(endPadding = 0) { ItemRowTitle("Movies", count = 25) }
        assertRule()
        assertEquals(
            104f,
            compose
                .onNodeWithText("MOVIES")
                .getUnclippedBoundsInRoot()
                .left.value,
            .1f,
        )
    }

    @Test
    fun `search and discovery rules span both nested list padding and page gutters`() {
        page(listPadding = 16) { ItemRowTitle("Results") }
        assertRule()
        assertEquals(
            120f,
            compose
                .onNodeWithText("RESULTS")
                .getUnclippedBoundsInRoot()
                .left.value,
            .1f,
        )
    }

    @Test
    fun `loading rows have the same full width rule`() {
        page { FocusableItemRow("Movies", "Loading") }
        assertRule()
    }

    @Test
    fun `error rows keep their red rule across the full content width`() {
        page { FocusableItemRow("Movies", "Could not load", isError = true) }
        assertRule(expected = NeonBoard.Red)
    }

    @Test
    fun `grid title rule reaches both edges while its title keeps the gutter`() {
        page { GridTitle("Movies") }
        assertRule()
        assertEquals(
            112f,
            compose
                .onNodeWithText("MOVIES")
                .getUnclippedBoundsInRoot()
                .left.value,
            .1f,
        )
    }

    @Test
    fun `dialogs reset the surrounding page gutter`() {
        var panelInsets: NeonRuleInsets? = null
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent) {
                    InsetNeonSectionRules(start = 24.dp, end = 8.dp) {
                        BasicDialog(onDismissRequest = {}) {
                            panelInsets = LocalNeonRuleInsets.current
                            Box(Modifier.width(300.dp).testTag("panel")) {
                                ItemRowTitle("Suggestions")
                            }
                        }
                    }
                }
            }
        }
        // Dialogs render in a separate window; check their inset scope directly.
        compose.runOnIdle { assertEquals(NeonRuleInsets(), panelInsets) }
    }
}
