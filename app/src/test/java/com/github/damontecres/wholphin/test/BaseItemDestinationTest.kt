package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class BaseItemDestinationTest {
    /** Songs have no page: one that reaches the app opens as itself, which shows the "unsupported" text */
    @Test
    fun audio_returnsSelfAsMediaItem() {
        val songId = UUID.randomUUID()
        val item = BaseItem(song(id = songId, albumId = UUID.randomUUID()), false)

        val dest = item.destination()

        assertEquals(songId, (dest as Destination.MediaItem).itemId)
        assertEquals(BaseItemKind.AUDIO, dest.type)
    }

    @Test
    fun movie_usesStandardDestination() {
        val movieId = UUID.randomUUID()
        val item = BaseItem(movie(id = movieId), false)

        val dest = item.destination()

        assertEquals(movieId, (dest as Destination.MediaItem).itemId)
        assertEquals(BaseItemKind.MOVIE, dest.type)
    }
}
