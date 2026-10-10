package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.discover.DiscoverSearchPage
import com.github.damontecres.wholphin.ui.discover.DiscoverSearchViewModel
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.ui.theme.LocalPosterCountAppearance
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.util.Clock
import com.github.damontecres.wholphin.ui.util.LocalClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
class DiscoverSearchLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `inactive search field releases horizontal focus to microphone and view options`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Row(Modifier.width(800.dp)) {
                    Button(onClick = {}) { androidx.tv.material3.Text("Microphone") }
                    SearchEditTextBox("Batman", {}, {}, Modifier.weight(1f), readOnly = true)
                    Button(onClick = {}) { androidx.tv.material3.Text("View options") }
                }
            }
        }
        compose.onNodeWithText("Batman").requestFocus()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.onNodeWithText("View options").assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.onNodeWithText("Batman").assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.onNodeWithText("Microphone").assertIsFocused()
    }

    @Test
    fun `request search header leaves space for the overlaid clock at enlarged text`() {
        val prefs = UserPreferences(AppPreferences.getDefaultInstance(), null)
        val service = mockk<UserPreferencesService>(relaxed = true)
        every { service.flow } returns MutableStateFlow(prefs)
        val vm = mockk<DiscoverSearchViewModel>(relaxed = true)
        every { vm.currentQuery } returns "Batman"
        every { vm.userPreferencesService } returns service
        every { vm.voiceInputManager.isAvailable } returns false
        every { vm.seerrResults } returns MutableStateFlow<SearchResult>(SearchResult.NoQuery)
        val clock = Clock(timeString = mutableStateOf("12:59 PM"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f), LocalClock provides clock) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.width(800.dp)) {
                        DiscoverSearchPage(prefs, { _, _ -> }, Modifier.fillMaxSize(), vm)
                        TimeDisplay()
                    }
                }
            }
        }
        val field = compose.onNodeWithText("Batman").getUnclippedBoundsInRoot()
        val time = compose.onNodeWithText("12:59 PM").getUnclippedBoundsInRoot()
        assertTrue("Search field must leave the clock unobstructed: $field vs $time", field.right < time.left)
    }

    @Test
    fun `editing search field releases vertical D-pad focus after keyboard dismissal`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Column {
                    SearchEditTextBox("Batman", {}, {}, readOnly = false)
                    Button(onClick = {}) { androidx.tv.material3.Text("Search result") }
                }
            }
        }
        compose.onNode(hasText("Batman")).requestFocus()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        compose.onNodeWithText("Search result").assertIsFocused()
    }

    @Test
    fun `D-pad down leaves the read-only request search field for loaded results`() {
        val prefs = UserPreferences(AppPreferences.getDefaultInstance(), null)
        val service = mockk<UserPreferencesService>(relaxed = true)
        every { service.flow } returns MutableStateFlow(prefs)
        val vm = mockk<DiscoverSearchViewModel>(relaxed = true)
        every { vm.currentQuery } returns "Batman"
        every { vm.userPreferencesService } returns service
        every { vm.voiceInputManager.isAvailable } returns false
        every { vm.seerrResults } returns
            MutableStateFlow<SearchResult>(
                SearchResult.SuccessSeerr(
                    List(20) {
                        DiscoverItem(
                            it,
                            SeerrItemType.TV,
                            "Batman $it",
                            null,
                            null,
                            SeerrAvailability.UNKNOWN,
                            null,
                            null,
                            null,
                            null,
                            null,
                        )
                    },
                ),
            )
        val images = mockk<ImageUrlService>(relaxed = true)
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides images) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    DiscoverSearchPage(prefs, { _, _ -> }, Modifier.fillMaxSize(), vm)
                }
            }
        }
        compose.onNode(hasText("Batman")).requestFocus()
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        compose.waitForIdle()
        assertTrue(
            "Down must enter the results, rather than remain trapped in the text field",
            compose.onAllNodes(hasText("Batman") and isFocused()).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue("A result must retain focus", compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().performKeyInput {
            keyDown(Key.MediaFastForward)
            keyUp(Key.MediaFastForward)
        }
        compose.waitForIdle()
        val last = compose.onNodeWithText("Batman 19").getUnclippedBoundsInRoot()
        assertTrue("Final request caption must remain inside the viewport: $last", last.top.value >= 0 && last.bottom.value <= 540)
    }

    @Test
    fun `request movie and show overlays use shared palette labels and insets`() {
        val type = mutableStateOf(SeerrItemType.MOVIE)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CompositionLocalProvider(
                    LocalPosterCountAppearance provides HomeCardAppearance(badgeHorizontalInsetDp = 8, badgeVerticalInsetDp = 8),
                ) {
                    Box(Modifier.width(160.dp)) {
                        DiscoverItemCard(
                            DiscoverItem(
                                1,
                                type.value,
                                "Example",
                                null,
                                null,
                                SeerrAvailability.UNKNOWN,
                                null,
                                null,
                                null,
                                null,
                                null,
                            ),
                            {
                            },
                            {},
                            width = 160.dp,
                        )
                    }
                }
            }
        }
        for ((kind, label, color) in listOf(
            Triple(SeerrItemType.MOVIE, "MOVIE", NeonSectionPalette.Movies.border),
            Triple(SeerrItemType.TV, "SHOW", NeonSectionPalette.TvShows.border),
        )) {
            compose.runOnIdle { type.value = kind }
            val badge = compose.onNodeWithText(label, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            badge.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(
                "$label must use the current library colour",
                color,
                layouts
                    .single()
                    .layoutInput.style.color,
            )
            val bounds = badge.getUnclippedBoundsInRoot()
            assertTrue("$label must keep the standard inset and padding: $bounds", bounds.left.value >= 12 && bounds.top.value >= 12)
        }
    }
}
