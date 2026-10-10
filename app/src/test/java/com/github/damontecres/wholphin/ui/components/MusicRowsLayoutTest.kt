package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.damontecres.wholphin.data.model.AudioItem
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.MusicServiceState
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.PlaylistItems
import com.github.damontecres.wholphin.ui.detail.music.LyricsContent
import com.github.damontecres.wholphin.ui.detail.music.NowPlayingOverlay
import com.github.damontecres.wholphin.ui.detail.music.NowPlayingState
import com.github.damontecres.wholphin.ui.detail.music.SongListItem
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
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
class MusicRowsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long song titles and artist names retain complete first and final rows`() {
        val titles = List(30) { "Song ${it + 1} with a longer descriptive title" }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    LazyColumn(contentPadding = PaddingValues(20.dp), modifier = Modifier.fillMaxSize()) {
                        items(titles) { title ->
                            SongListItem(
                                title,
                                "Artist name with several additional words",
                                1,
                                null,
                                {},
                                {},
                                showArtist = true,
                                showMoreButton = true,
                            )
                        }
                    }
                }
            }
        }
        verify(titles.first(), titles.last())
    }

    @Test
    fun `last wrapped lyric and focused first lyric remain entirely visible`() {
        val lines = List(30) { LyricLine("Line ${it + 1}: " + "Several words in a longer lyric. ".repeat(4)) }
        val lyrics = mockk<LyricDto>(relaxed = true)
        every { lyrics.lyrics } returns lines
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    LyricsContent(true, lyrics, null, {}, {}, Modifier.fillMaxSize())
                }
            }
        }
        verify(lines.first().text, lines.last().text)
    }

    @Test
    fun `now playing final queue song and more button remain inside viewport`() {
        val songs =
            List(30) { i ->
                AudioItem(
                    id = UUID.randomUUID(),
                    albumId = null,
                    artistId = null,
                    title = "Queue song $i",
                    albumTitle = "Album",
                    artistNames = "Artist",
                    runtime = null,
                    imageUrl = null,
                    hasLyrics = false,
                )
            }
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns songs.size
        every { player.getMediaItemAt(any()) } answers {
            val i = firstArg<Int>()
            MediaItem
                .Builder()
                .setUri("https://example.test/audio/$i")
                .setTag(songs[i])
                .build()
        }
        every { player.duration } returns 3_600_000L
        every { player.currentPosition } returns 1_000L
        every { player.bufferedPosition } returns 30_000L
        val state = NowPlayingState(MusicServiceState.EMPTY.copy(queueSize = songs.size, loadingState = LoadingState.Success))
        val controller = ControllerViewState(60_000, true)
        controller.showControls()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    NowPlayingOverlay(
                        state,
                        player,
                        songs.first(),
                        controller,
                        { _, _ -> },
                        { _, _ -> },
                        {},
                        { _, _ -> },
                        { _, _ -> },
                        {},
                        FocusRequester(),
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Queue song 29"))
        val last = compose.onNode(hasText("Queue song 29") and isFocusable())
        last.requestFocus()
        compose.waitForIdle()
        val more = compose.onAllNodes(hasContentDescription("More") and isFocusable()).onLast()
        more.requestFocus()
        compose.waitForIdle()
        val bounds = more.getUnclippedBoundsInRoot()
        assertTrue("Queue More fits: $bounds", bounds.top.value >= 0 && bounds.bottom.value <= 540)
        val song = last.getUnclippedBoundsInRoot()
        assertTrue("Complete queue song fits: $song", song.top.value >= 0 && song.bottom.value <= 540)
    }

    private fun verify(vararg labels: String) {
        for (label in labels) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val row = compose.onNode(hasText(label) and isFocusable())
            row.requestFocus()
            row.assertIsDisplayed()
            val b = row.getUnclippedBoundsInRoot()
            assertTrue(
                "Complete music row inside viewport: $b",
                b.top.value >= 0 && b.bottom.value <= 540 && b.left.value >= 0 && b.right.value <= 960,
            )
        }
    }
}
