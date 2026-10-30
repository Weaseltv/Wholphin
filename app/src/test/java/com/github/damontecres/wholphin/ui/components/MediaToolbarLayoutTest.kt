package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.detail.collection.CollectionButtons
import com.github.damontecres.wholphin.ui.detail.collection.CollectionState
import com.github.damontecres.wholphin.ui.detail.discover.ExpandableDiscoverButtons
import com.github.damontecres.wholphin.ui.detail.music.MusicButtonActions
import com.github.damontecres.wholphin.ui.detail.music.MusicExpandableButtons
import com.github.damontecres.wholphin.ui.slideshow.ImageControlsOverlay
import com.github.damontecres.wholphin.ui.slideshow.SlideshowControls
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
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
class MediaToolbarLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `music action bar reserves vertical room for focused button glow`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(800.dp)) {
                    MusicExpandableButtons("Album", MusicButtonActions({}, {}, {}, {}, {}), false, false, {})
                }
            }
        }
        checkClearance("Play")
        checkClearance("More")
    }

    @Test
    fun `photo action bar reserves vertical room for focused button glow`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(800.dp)) {
                    ImageControlsOverlay(
                        false,
                        object : SlideshowControls {
                            override fun startSlideshow() {}

                            override fun stopSlideshow() {}
                        },
                        {},
                        false,
                        {},
                        {},
                        {},
                        {},
                        false,
                        {},
                        {},
                        null,
                    )
                }
            }
        }
        checkClearance("Play slideshow")
        checkClearance("Filter")
    }

    @Test
    fun `collection actions stay clear of Random, sort and filter`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                // A collection page on a 960dp TV: closed rail 72dp, start gutter 24dp, end gutter 8dp
                Box(Modifier.width(856.dp)) {
                    CollectionButtons(
                        state = CollectionState(),
                        onSortChange = {},
                        onClickRandom = {},
                        onQuickListChange = {},
                        onFilterChange = {},
                        getPossibleFilterValues = { emptyList() },
                        onClickViewOptions = {},
                        favoriteOnClick = {},
                        onConfirmDelete = {},
                        // Delete shows only with media management on, which is off by default
                        canDelete = false,
                        moreOnClick = {},
                    )
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Application>()
        val random = compose.onNode(hasText(context.getString(R.string.fa_dice)) and isFocusable())
        // Focused, Random shows its label: the widest the right-hand group gets
        random.requestFocus()
        compose.waitForIdle()
        val all = compose.onNode(hasText("All", ignoreCase = true) and isFocusable()).getUnclippedBoundsInRoot()
        val more = compose.onNode(hasText("More", ignoreCase = true) and isFocusable()).getUnclippedBoundsInRoot()
        val randomBounds = random.getUnclippedBoundsInRoot()
        val filter =
            compose.onNode(hasText(context.getString(R.string.fa_filter)) and isFocusable()).getUnclippedBoundsInRoot()
        assertTrue("ALL / MOVIES / SHOWS must sit above the actions: $all, $more", all.bottom <= more.top)
        assertTrue("Actions must leave room for Random: $more, $randomBounds", more.right <= randomBounds.left)
        assertTrue("Filter must fit the row: $filter", filter.right.value <= 856)
    }

    @Test
    fun `request detail action bar retains focus clearance at both ends`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(800.dp)) {
                    ExpandableDiscoverButtons(true, true, SeerrAvailability.PARTIALLY_AVAILABLE, null, {}, {}, {}, {}, {}, {})
                }
            }
        }
        checkClearance("Go To")
        checkClearance("Cancel")
    }

    private fun checkClearance(label: String) {
        // Labels are uppercase on WeaselPlex
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label, ignoreCase = true))
        val button = compose.onNode(hasText(label, ignoreCase = true) and isFocusable())
        button.requestFocus()
        compose.waitForIdle()
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue(
            "Focused $label top glow must clear the clipping edge: $bounds in $viewport",
            bounds.top.value - viewport.top.value >= 12,
        )
        assertTrue(
            "Focused $label bottom glow must clear the clipping edge: $bounds in $viewport",
            viewport.bottom.value - bounds.bottom.value >= 12,
        )
    }
}
