package com.github.damontecres.wholphin.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.components.DialogItem
import com.github.damontecres.wholphin.ui.components.DialogPopupContent
import com.github.damontecres.wholphin.ui.playback.overlay.StaticSeekBarImpl
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.math.abs

/** Pixel checks on the Neon menu panel and the player's seek bar, at mdpi so 1dp is 1px. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeonMenuLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Pure green never appears in the theme, so only what is drawn in the accent matches. The
     * focus scale anti-aliases a 1dp border to about 80 %, while the glow stays under 50 %.
     */
    private val accent = Color(0xFF00FF00)

    private fun PixelMap.isAccent(
        x: Int,
        y: Int,
    ): Boolean {
        val c = this[x, y]
        return c.green > .55f && c.red < .25f && c.blue < .25f
    }

    /** x positions of accent pixels in each row. */
    private fun PixelMap.accentRows(): List<List<Int>> = (0 until height).map { y -> (0 until width).filter { isAccent(it, y) } }

    @Test
    fun `menu title rule runs edge to edge and the focused first and last rows keep their borders`() {
        val items = listOf("Go To", "Play", "Play with").map { DialogItem(text = it, onClick = {}) }
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent) {
                    Box(Modifier.padding(32.dp).testTag("menu")) {
                        DialogPopupContent(
                            title = "Fall 2: Deadpoint",
                            dialogItems = items,
                            waiting = false,
                            onDismissRequest = {},
                        )
                    }
                }
            }
        }

        listOf("Go To", "Play with").forEach { label ->
            compose.onNodeWithText(label).requestFocus()
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("menu").captureToImage().toPixelMap()
            val wide = pixels.accentRows().withIndex().filter { (_, xs) -> xs.size > 300 }
            // The rule, then the focused row's top and bottom border
            assertTrue("rule plus both borders of the focused $label row: ${wide.map { it.index }}", wide.size >= 3)

            val rule = wide.first().value
            val panelWidth = 448
            assertTrue("rule starts at the panel's left edge, was ${rule.min()}", rule.min() <= 4)
            assertTrue("rule ends at the panel's right edge, was ${rule.max()}", rule.max() >= panelWidth - 5)

            // A rule can be several pixels thick. Compare the separate border
            // strokes, rather than treating its second scanline as a focus border.
            val strokes = wide.filterIndexed { index, row -> index == 0 || row.index > wide[index - 1].index + 1 }
            assertTrue("rule and two distinct focus border strokes", strokes.size >= 3)
            val (topY, top) = strokes[1]
            val (bottomY, bottom) = strokes.last()
            assertTrue("focused $label row has height", bottomY - topY > 30)
            assertEquals("top and bottom border are the same width", top.size.toFloat(), bottom.size.toFloat(), 4f)
        }
    }

    @Test
    fun `seek bar fill is drawn in the accent up to the playback position`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent) {
                    Box(Modifier.padding(24.dp).testTag("bar")) {
                        StaticSeekBarImpl(
                            progress = .5f,
                            durationMs = 60_000,
                            bufferedProgress = .75f,
                            modifier = Modifier.width(408.dp),
                        )
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("bar").captureToImage().toPixelMap()
        val filled = pixels.accentRows().filter { it.isNotEmpty() }
        assertTrue("the fill is a visible bar, found ${filled.size} rows", filled.size >= 3)
        filled.forEach { xs ->
            // 4dp side padding, then half of the 400dp track
            assertEquals("fill reaches the halfway point", 4 + 200f, xs.max() + 1f, 2f)
            assertTrue("fill starts at the track's start", abs(xs.min() - 4) <= 1)
        }
    }

    @Test
    fun `progress wears the title's type colour`() {
        fun item(kind: BaseItemKind) = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = kind))
        assertEquals(NeonSectionPalette.Movies.border, itemAccent(item(BaseItemKind.MOVIE)))
        assertEquals(NeonSectionPalette.TvShows.border, itemAccent(item(BaseItemKind.EPISODE)))
    }
}
