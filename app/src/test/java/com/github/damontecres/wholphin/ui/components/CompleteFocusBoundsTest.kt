package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.preferences.SliderPreference
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompleteFocusBoundsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `automatic focus scrolling preserves the complete kicker row and remains still across cards`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides KeepVisibleBringIntoViewSpec) {
                    LazyColumn(Modifier.width(860.dp).height(340.dp), contentPadding = PaddingValues(bottom = 340.dp)) {
                        item { Box(Modifier.height(220.dp)) }
                        item {
                            ItemRow(
                                title = "Streaming Services",
                                titleKicker = "Collections",
                                items = listOf(0, 1, 2),
                                onClickItem = { _, _ -> },
                                onLongClickItem = { _, _ -> },
                                modifier = Modifier.testTag("row"),
                                cardContent = { index, _, modifier, _, _ ->
                                    Column(modifier.width(110.dp).keepFocusedItemVisible()) {
                                        Box(
                                            Modifier
                                                .height(170.dp)
                                                .width(110.dp)
                                                .testTag("card-$index")
                                                .focusable(),
                                        )
                                        Text("Caption $index", Modifier.testTag("caption-$index"))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("card-0").requestFocus()
        compose.waitForIdle()
        val title = compose.onNodeWithText("COLLECTIONS").getUnclippedBoundsInRoot()
        val caption = compose.onNodeWithTag("caption-0").getUnclippedBoundsInRoot()
        assertTrue("Kicker remains below the scroll clipping edge", title.top.value >= 0f)
        assertTrue("Full caption remains above the bottom clipping edge", caption.bottom.value <= 340f)
        val rowTop =
            compose
                .onNodeWithTag("row")
                .getUnclippedBoundsInRoot()
                .top.value
        for (index in listOf(1, 2, 1, 0)) {
            compose.onNodeWithTag("card-$index").requestFocus()
            compose.waitForIdle()
            assertEquals(
                "Horizontal focus must not move the row vertically",
                rowTop,
                compose
                    .onNodeWithTag("row")
                    .getUnclippedBoundsInRoot()
                    .top.value,
                1f,
            )
        }
    }

    @Test
    fun `focusing a slider reveals the title value and entire setting rather than only the thumb`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides KeepVisibleBringIntoViewSpec) {
                    LazyColumn(Modifier.width(350.dp).height(240.dp), contentPadding = PaddingValues(bottom = 240.dp)) {
                        item { Box(Modifier.height(230.dp)) }
                        item {
                            SliderPreference(
                                preference = AppPreference.SkipForward,
                                title = "Skip forward",
                                summary = "30 seconds",
                                value = 30L,
                                onChange = {},
                                modifier = Modifier.testTag("setting"),
                            )
                        }
                    }
                }
            }
        }
        compose.onNode(isFocusable() and hasAnyAncestor(hasTestTag("setting"))).requestFocus()
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("setting").getUnclippedBoundsInRoot()
        assertTrue("Setting title stays inside the viewport", bounds.top.value >= 0f)
        assertTrue("Setting and thumb clear the bottom edge", bounds.bottom.value < 240f)
    }
}
