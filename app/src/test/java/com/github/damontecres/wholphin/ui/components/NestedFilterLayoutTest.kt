package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverMovieGenreFilter
import com.github.damontecres.wholphin.data.filter.DiscoverMovieStudiosFilter
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.GenreFilter
import com.github.damontecres.wholphin.data.filter.PlayedFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
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
class NestedFilterLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `local filter submenu replaces the parent instead of covering its labels`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.padding(120.dp)) {
                    FilterByButton(
                        listOf(PlayedFilter, GenreFilter),
                        GetItemsFilter(),
                        {},
                        { listOf(FilterValueOption("True", true), FilterValueOption("False", false)) },
                    )
                }
            }
        }
        openButton()
        compose.onNodeWithText("Genres").assertIsDisplayed()
        openEntry("Played")
        compose.onNodeWithText("Genres").assertDoesNotExist()
        compose.onNodeWithText("Played").assertIsDisplayed()
        verifyEntries(listOf("True", "False"))
    }

    @Test
    fun `request filters use the same single panel navigation`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.padding(120.dp)) {
                    DiscoverFilterByButton(
                        listOf(DiscoverMovieGenreFilter, DiscoverMovieStudiosFilter),
                        DiscoverFilter(),
                        {},
                        { listOf(FilterValueOption("Action", 1), FilterValueOption("Comedy", 2)) },
                    )
                }
            }
        }
        openButton()
        compose.onNodeWithText("Studios").assertIsDisplayed()
        openEntry("Genres")
        compose.onNodeWithText("Studios").assertDoesNotExist()
        compose.onNodeWithText("Genres").assertIsDisplayed()
        verifyEntries(listOf("Action", "Comedy"))
    }

    private fun openButton() {
        val button = compose.onNode(hasClickAction() and isFocusable())
        button.requestFocus()
        button.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun openEntry(label: String) {
        val entry = compose.onNode(hasText(label) and isFocusable())
        entry.requestFocus()
        entry.performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun verifyEntries(labels: List<String>) {
        for (label in labels) {
            val entry = compose.onNode(hasText(label) and isFocusable())
            entry.requestFocus()
            val bounds = entry.getUnclippedBoundsInRoot()
            assertTrue("$label stays inside the viewport: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
        }
    }
}
