package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.PlaylistInfo
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.MusicServiceState
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.detail.PlaylistItems
import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
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
class PlaylistLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `playlist picker keeps search heading and last result visible at enlarged text`() {
        val playlists =
            List(30) { PlaylistInfo(UUID.randomUUID(), "Playlist ${it + 1} with a long descriptive name", 100, MediaType.VIDEO) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PlaylistDialog("Add to playlist", PlaylistLoadingState.Success(playlists, ""), {}, {}, {}, true, {})
                }
            }
        }
        for (label in listOf(playlists.first().name, playlists.last().name, "Create new playlist")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val entry = compose.onNode(hasText(label) and isFocusable())
            entry.requestFocus()
            compose.waitForIdle()
            val bounds = entry.getUnclippedBoundsInRoot()
            val header = compose.onNodeWithText("Add to playlist").getUnclippedBoundsInRoot()
            assertTrue(
                "$label must clear heading and viewport: $bounds / $header",
                bounds.top >= header.bottom && bounds.bottom.value <= 540,
            )
        }
    }

    @Test
    fun `first and final playlist rows retain complete text and move controls`() {
        val items =
            List(30) {
                BaseItem(
                    BaseItemDto(id = UUID.randomUUID(), name = "Playlist entry ${it + 1} with a long title", type = BaseItemKind.MOVIE),
                    false,
                )
            }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1.5f),
                LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
            ) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PlaylistItems(
                        LoadingState.Success,
                        items,
                        MusicServiceState.EMPTY,
                        FocusRequester(),
                        true,
                        { _, _, _ -> },
                        { _, _ -> },
                        { _, _ -> },
                        { _, _, _ -> },
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        for (index in listOf(0, 29)) {
            val label = requireNotNull(items[index].title)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val row = compose.onNode(hasText(label) and isFocusable())
            row.requestFocus()
            row.assertIsDisplayed()
            val b = row.getUnclippedBoundsInRoot()
            assertTrue(
                "Full playlist row inside viewport: $b",
                b.top.value >= 0 && b.bottom.value <= 508 && b.left.value >= 0 && b.right.value <= 960,
            )
            val text = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Title inside row: $text, $b", text.top >= b.top && text.bottom <= b.bottom)
        }
    }
}
