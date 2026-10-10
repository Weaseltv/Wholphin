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
    fun `collection actions and filters retain their own focus clearance`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(800.dp)) {
                    CollectionButtons(CollectionState(), {}, {}, {}, { emptyList() }, {}, {}, {}, true, {})
                }
            }
        }
        for (label in listOf("Play", "More")) {
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(label))
            val button = compose.onNode(hasText(label) and isFocusable())
            button.requestFocus()
            compose.waitForIdle()
            val viewport = compose.onAllNodes(hasScrollAction())[0].getUnclippedBoundsInRoot()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue(
                "$label and glow must fit collection actions: $bounds in $viewport",
                bounds.top.value - viewport.top.value >= 12 && viewport.bottom.value - bounds.bottom.value >= 12,
            )
        }
        val actions = compose.onAllNodes(hasScrollAction())[0].getUnclippedBoundsInRoot()
        val filters = compose.onAllNodes(hasScrollAction())[1].getUnclippedBoundsInRoot()
        assertTrue("Actions must reserve room for sort/filter controls", actions.right <= filters.left && filters.right.value <= 800)
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        val button = compose.onNode(hasText(label) and isFocusable())
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
