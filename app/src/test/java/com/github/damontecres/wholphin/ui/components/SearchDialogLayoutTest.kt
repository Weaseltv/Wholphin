package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.search.SearchTypeOptionsDialog
import com.github.damontecres.wholphin.ui.search.SearchViewOptionsDialog
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.jellyfin.sdk.model.api.BaseItemKind
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
class SearchDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `search settings and type menu keep focused text visible with larger system text`() {
        val types = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    if (types.value) {
                        SearchTypeOptionsDialog(
                            {
                            },
                            listOf(
                                BaseItemKind.MOVIE,
                                BaseItemKind.SERIES,
                                BaseItemKind.EPISODE,
                                BaseItemKind.PERSON,
                            ),
                            emptyList(),
                            true,
                            true,
                            {},
                            {},
                        )
                    } else {
                        SearchViewOptionsDialog(false, {}, true, {}, { types.value = true }, {})
                    }
                }
            }
        }
        for (title in listOf("Combined search results", "Show voice search button", "Include types")) {
            val entry = compose.onNode(hasText(title) and isFocusable())
            entry.requestFocus()
            compose.onNodeWithText(title).assertIsDisplayed()
            val bounds = entry.getUnclippedBoundsInRoot()
            assertTrue("$title clears both edges: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
        }
        compose.onNode(hasText("Include types") and isFocusable()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        for (title in listOf("Movies", "People")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
            val entry = compose.onNode(hasText(title) and isFocusable())
            entry.requestFocus()
            val bounds = entry.getUnclippedBoundsInRoot()
            assertTrue("$title clears both edges: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
        }
    }
}
