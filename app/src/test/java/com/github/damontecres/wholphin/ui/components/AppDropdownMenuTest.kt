package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.theme.colors.WeaselTvThemeColors
import org.junit.Assert.assertEquals
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
class AppDropdownMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `elevated panels remain neutral rather than using the yellow primary`() {
        for (elevation in listOf(1, 3, 6, 16, 24)) {
            val color = WeaselTvThemeColors.darkScheme.surfaceColorAtElevation(elevation.dp)
            assertTrue("panel $elevation must not acquire a yellow cast", color.blue >= color.red && color.blue >= color.green)
        }
        assertEquals(NeonBoard.Card, WeaselTvThemeColors.darkSchemeMaterial.surfaceTint)
    }

    @Test
    fun `dropdown uses dark panel and current page accent rather than theme primary`() {
        val accent = Color.Cyan
        val interactions = MutableInteractionSource()
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(accent) {
                    Box {
                        AppDropdownMenu(true, {}, Modifier.width(240.dp).testTag("menu")) {
                            TvDropdownMenuItem(
                                text = { Text("Name") },
                                onClick = {},
                                modifier = Modifier.testTag("name"),
                                interactionSource = interactions,
                            )
                            TvDropdownMenuItem(text = { Text("Year") }, onClick = {}, modifier = Modifier.testTag("year"))
                        }
                    }
                }
            }
        }
        compose.runOnIdle { interactions.tryEmit(FocusInteraction.Focus()) }
        compose.waitForIdle()
        val idle = compose.onNodeWithTag("year").captureToImage().toPixelMap()
        assertEquals(NeonBoard.Card, idle[2, 2])
        val focused = compose.onNodeWithTag("name").captureToImage().toPixelMap()
        val cyan =
            (0 until focused.width).count { x ->
                val c = focused[x, 0]
                c.red < .1f && c.green > .9f && c.blue > .9f
            }
        assertTrue("page-coloured border spans the focused row", cyan > focused.width - 4)
    }
}
