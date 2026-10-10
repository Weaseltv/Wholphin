package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptions
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptionsDialog
import com.github.damontecres.wholphin.ui.detail.livetv.LiveTvViewOptionsDialog
import com.github.damontecres.wholphin.ui.detail.music.MusicViewOptionsDialog
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
class PreferenceOptionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `library grid list and dense list panels retain final preferences at enlarged text`() {
        val type = mutableStateOf(ViewOptionsType.GRID)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    ViewOptionsDialog(ViewOptions(type = type.value), {}, {})
                }
            }
        }
        for (mode in listOf(ViewOptionsType.GRID, ViewOptionsType.LIST, ViewOptionsType.DENSE_LIST)) {
            compose.runOnIdle { type.value = mode }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset"))
            val entry = compose.onNode(hasText("Reset") and isFocusable())
            entry.requestFocus()
            compose.waitForIdle()
            val bounds = entry.getUnclippedBoundsInRoot()
            val header = compose.onNodeWithText("VIEW OPTIONS").getUnclippedBoundsInRoot()
            assertTrue(
                "$mode final preference must clear header and viewport: $bounds",
                bounds.top >= header.bottom && bounds.bottom.value <= 540,
            )
        }
    }

    @Test
    fun `mixed and separate collection panels retain heading and final reset at enlarged text`() {
        val separate = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    CollectionViewOptionsDialog(CollectionViewOptions(separateTypes = separate.value), {}, {})
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { separate.value = mode }
            for (label in listOf("Separate types", "Reset")) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                val entry = compose.onNode(hasText(label) and isFocusable())
                entry.requestFocus()
                compose.waitForIdle()
                val bounds = entry.getUnclippedBoundsInRoot()
                val header = compose.onNodeWithText("VIEW OPTIONS").getUnclippedBoundsInRoot()
                assertTrue(
                    "$label must clear heading and lower panel edge: $bounds / $header",
                    bounds.top >= header.bottom && bounds.bottom.value <= 540,
                )
            }
        }
    }

    @Test
    fun `music and guide panels preserve their heading and each focused preference`() {
        val music = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    if (music.value) {
                        MusicViewOptionsDialog(AppPreferences.getDefaultInstance(), {}, {}, {})
                    } else {
                        LiveTvViewOptionsDialog(AppPreferences.getDefaultInstance(), {}, {})
                    }
                }
            }
        }
        for (labels in listOf(
            listOf("Show album cover", "Show visualizer", "Show backdrop", "Show lyrics"),
            listOf("Show details", "Show favorite channels first", "Sort channels by recently watched", "Color-code programs"),
        )) {
            for (label in labels) {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                val entry = compose.onNode(hasText(label) and isFocusable())
                entry.requestFocus()
                val bounds = entry.getUnclippedBoundsInRoot()
                assertTrue("$label clears the panel edges: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
                val header = compose.onNodeWithText("VIEW OPTIONS")
                header.assertIsDisplayed()
                assertTrue("Header and list must not overlap", header.getUnclippedBoundsInRoot().bottom <= bounds.top)
            }
            compose.runOnIdle { music.value = false }
        }
    }
}
