package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.main.homeRowAccent
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.util.Clock
import com.github.damontecres.wholphin.ui.util.LocalClock
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderTitleLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long grid headings retain whole text without overlapping the page clock`() {
        val title = mutableStateOf("Recently Added in TV Shows")
        val fontScale = mutableStateOf(1f)
        val clock = Clock(timeString = mutableStateOf("12:59 PM"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value), LocalClock provides clock) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.width(800.dp)) {
                        GridTitle(title.value, modifier = Modifier.padding(top = 27.dp))
                        TimeDisplay()
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.5f)) {
            for (label in listOf(
                "Recently Added in TV Shows",
                "Recently Added in Stand Up Comedy",
                "Top Rated Unwatched",
                "Recently Added in Sports",
            )) {
                compose.runOnIdle {
                    title.value = label
                    fontScale.value = scale
                }
                val node = compose.onNodeWithText(label.uppercase())
                val result = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                assertFalse("$label at $scale", result.single().hasVisualOverflow)
                val bounds = node.getUnclippedBoundsInRoot()
                val timeBounds = compose.onNodeWithText("12:59 PM").getUnclippedBoundsInRoot()
                assertTrue("$label overlaps clock: $bounds vs $timeBounds", bounds.right <= timeBounds.left)
                assertTrue("Grid heading consumes the card viewport: $bounds", bounds.bottom.value <= 220)
            }
        }
    }

    @Test
    fun `view more uses the same source library accent as its home row`() {
        val libraries =
            listOf(
                "Movies" to CollectionType.MOVIES,
                "TV Shows" to CollectionType.TVSHOWS,
                "Stand Up Comedy" to CollectionType.MOVIES,
                "Sports" to CollectionType.MOVIES,
            ).map { (name, type) -> Library(UUID.randomUUID(), name, BaseItemKind.COLLECTION_FOLDER, type, false) }
        val config = mutableStateOf<HomeRowConfig>(HomeRowConfig.RecentlyAdded(libraries.first().itemId))
        var actual = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                ProvideNeonAccent(NeonSectionPalette.Home.border) {
                    actual = homeRowAccent(config.value, libraries)
                }
            }
        }
        for ((index, palette) in listOf(
            NeonSectionPalette.Movies,
            NeonSectionPalette.TvShows,
            NeonSectionPalette.StandUpComedy,
            NeonSectionPalette.Sports,
        ).withIndex()) {
            for (row in listOf(
                HomeRowConfig.RecentlyAdded(libraries[index].itemId),
                HomeRowConfig.RecentlyReleased(libraries[index].itemId),
                HomeRowConfig.Suggestions(libraries[index].itemId),
            )) {
                compose.runOnIdle { config.value = row }
                compose.runOnIdle { assertEquals(palette.border, actual) }
            }
        }
    }

    @Test
    fun `request person heading shares the page kicker and leaves its cards below complete text`() {
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Column(Modifier.width(800.dp)) {
                    GridTitle("Matthew Lillard", eyebrow = "Requests", modifier = Modifier.padding(top = 27.dp))
                }
            }
        }
        val kicker = compose.onNodeWithText("REQUESTS").getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText("MATTHEW LILLARD").getUnclippedBoundsInRoot()
        assertTrue("Kicker needs a top gutter: $kicker", kicker.top.value >= 27)
        assertTrue(
            "The complete person title must follow the kicker: $name",
            name.top.value >= kicker.bottom.value && name.bottom.value <= 180,
        )
    }

    @Test
    fun `missing logos retain whole words and complete collection names`() {
        val title = mutableStateOf("Paramount+")
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.width(300.dp)) { TitleOrLogo(title.value, null, false) }
            }
        }
        for (label in listOf("Paramount+", "Crunchyroll", "Clear Your Evening", "Ripped From the Headlines")) {
            compose.runOnIdle { title.value = label }
            val result = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label.uppercase()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            val layout = result.single()
            assertFalse(label, layout.hasVisualOverflow)
            if (!label.contains(' ')) assertEquals(label, 1, layout.lineCount)
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line, visibleEnd = true)
                assertTrue("$label broke inside a word at $end", label[end].isWhitespace())
            }
        }
    }
}
