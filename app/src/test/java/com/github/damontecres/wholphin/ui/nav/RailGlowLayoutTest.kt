package com.github.damontecres.wholphin.ui.nav

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel checks on the nav rail's list at mdpi, so 1dp is 1px. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RailGlowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /** Pure green never appears in the theme, so only the focused row's border and glow match. */
    private val accent = Color(0xFF00FF00)

    private fun greenness(c: Color) = c.green - maxOf(c.red, c.blue)

    /** Off the image counts as no glow: a row flush with the rail's edge has nowhere to draw it. */
    private fun PixelMap.greennessAt(
        x: Int,
        y: Int,
    ) = if (y in 0 until height) greenness(this[x, y]) else 0f

    /** Rows where the focused row's 1dp accent border runs across. */
    private fun PixelMap.borderRows(): List<Int> =
        (0 until height).filter { y -> (0 until width).count { greenness(this[it, y]) > .5f } > 40 }

    @Test
    fun `focused Search and Settings keep the glow outside their borders`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                // Same shape as the rail: a header above, then the list filling the rest
                Column(Modifier.width(96.dp).height(320.dp).testTag("rail")) {
                    Spacer(Modifier.height(60.dp))
                    with(NavigationDrawerScopeImpl(false)) {
                        // fillMaxHeight as in NavDrawer, which pins Settings to the bottom
                        RailList(state = rememberLazyListState(), spacedBy = 4.dp, modifier = Modifier.fillMaxHeight()) {
                            item {
                                IconNavItem("Search", Icons.Default.Search, {}, false, false, Modifier.testTag("Search"), accent = accent)
                            }
                            item {
                                IconNavItem("Home", Icons.Default.Home, {}, false, false, accent = accent)
                            }
                            item {
                                IconNavItem(
                                    "Settings",
                                    Icons.Default.Settings,
                                    {},
                                    false,
                                    false,
                                    Modifier.testTag("Settings"),
                                    accent = accent,
                                )
                            }
                        }
                    }
                }
            }
        }

        val clipped = mutableListOf<String>()
        listOf("Search", "Settings").forEach { row ->
            compose.onNodeWithTag(row).requestFocus()
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("rail").captureToImage().toPixelMap()
            val borders = pixels.borderRows()
            assertTrue("$row border rows: $borders", borders.size >= 2)
            val top = borders.first()
            val bottom = borders.last()
            val x = pixels.width / 2
            // 6dp outside the border the glow is faint but present; a cut-off glow leaves nothing
            if (pixels.greennessAt(x, top - 6) <= .02f) clipped += "above $row"
            if (pixels.greennessAt(x, bottom + 6) <= .02f) clipped += "below $row"
        }
        assertTrue("glow cut off $clipped", clipped.isEmpty())
    }
}
