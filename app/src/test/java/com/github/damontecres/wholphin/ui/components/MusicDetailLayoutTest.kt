package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.MusicServiceState
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import com.github.damontecres.wholphin.ui.detail.music.AlbumDetailsPage
import com.github.damontecres.wholphin.ui.detail.music.AlbumState
import com.github.damontecres.wholphin.ui.detail.music.AlbumViewModel
import com.github.damontecres.wholphin.ui.detail.music.ArtistDetailsPage
import com.github.damontecres.wholphin.ui.detail.music.ArtistState
import com.github.damontecres.wholphin.ui.detail.music.ArtistViewModel
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
class MusicDetailLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val songs = List(30) { item("Song $it with long descriptive title", BaseItemKind.AUDIO) }

    private fun item(
        name: String,
        kind: BaseItemKind,
    ) = BaseItem(
        BaseItemDto(
            id = UUID.randomUUID(),
            name = name,
            type = kind,
            overview = "A detailed description of this artist and recording with background information. ".repeat(8),
            artists = listOf("An artist with a long descriptive name"),
            productionYear = 2026,
        ),
    )

    @Test fun `album page retains all thirty songs and restores its action bar at enlarged text`() = checkPage(false)

    @Test fun `artist page retains all thirty songs and restores its action bar at enlarged text`() = checkPage(true)

    private fun checkPage(artist: Boolean) {
        val album = item("A recording with a long descriptive album name", BaseItemKind.MUSIC_ALBUM)
        val person = item("An artist with a long descriptive name", BaseItemKind.MUSIC_ARTIST)
        val albumVm = mockk<AlbumViewModel>(relaxed = true)
        every { albumVm.state } returns
            MutableStateFlow(AlbumState.EMPTY.copy(album = album, songs = songs, loading = LoadingState.Success))
        every { albumVm.currentMusic } returns MutableStateFlow(MusicServiceState.EMPTY)
        val artistVm = mockk<ArtistViewModel>(relaxed = true)
        every { artistVm.state } returns
            MutableStateFlow(ArtistState.EMPTY.copy(artist = person, topSongs = songs, loading = LoadingState.Success))
        every { artistVm.currentMusic } returns MutableStateFlow(MusicServiceState.EMPTY)
        val playlistVm = mockk<AddPlaylistViewModel>(relaxed = true)
        every { playlistVm.playlistState } returns MutableStateFlow(PlaylistLoadingState.Pending)
        compose.setContent {
            CompositionLocalProvider(
                LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
                LocalDensity provides Density(1f, 1.5f),
            ) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    val prefs = UserPreferences(AppPreferences.getDefaultInstance(), null)
                    if (artist) {
                        ArtistDetailsPage(prefs, person.id, Modifier.fillMaxSize(), artistVm, playlistVm)
                    } else {
                        AlbumDetailsPage(album.id, prefs, Modifier.fillMaxSize(), null, albumVm, playlistVm)
                    }
                }
            }
        }
        compose.onNode(hasText("Play") and isFocusable()).requestFocus()
        compose.waitForIdle()
        repeat(30) { index ->
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
            val f = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            assertTrue("Song $index focus fits: $f", f.top.value >= 0 && f.bottom.value <= 540)
        }
        compose.onNodeWithText(songs.last().name.orEmpty()).assertExists()
        repeat(30) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionUp)
                keyUp(Key.DirectionUp)
            }
            compose.waitForIdle()
        }
        val f = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("Music header action returns within viewport: $f", f.top.value >= 0 && f.bottom.value <= 540)
    }
}
