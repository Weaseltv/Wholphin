package com.github.damontecres.wholphin.ui.theme

import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The Neon Board color rules (README § Colors on this app): one neon per section, items
 * carry their type color, Boxing and UFC are cyan by name, LIVE is green.
 */
class NeonAccentTest {
    private fun library(
        name: String,
        type: CollectionType,
    ) = ServerNavDrawerItem(
        itemId = UUID.randomUUID(),
        name = name,
        destination = Destination.Favorites,
        type = type,
    )

    @Test
    fun `every rail item has its own neon`() {
        val accents =
            listOf(
                sectionAccent(NavDrawerItem.Favorites),
                sectionAccent(NavDrawerItem.Discover),
                sectionAccent(library("Movies", CollectionType.MOVIES)),
                sectionAccent(library("TV Shows", CollectionType.TVSHOWS)),
                sectionAccent(library("Stand Up Comedy", CollectionType.MOVIES)),
                sectionAccent(library("Boxing", CollectionType.MOVIES)),
                sectionAccent(library("UFC", CollectionType.TVSHOWS)),
                sectionAccent(library("Live TV", CollectionType.LIVETV)),
                RailAccents.Search,
                RailAccents.Home,
                RailAccents.Settings,
            )
        assertEquals(accents.size, accents.toSet().size)
    }

    @Test
    fun `4K libraries share their regular counterpart's colour`() {
        assertEquals(NeonBoard.Orange, sectionAccent(library("4K Movies (LAN)", CollectionType.MOVIES)))
        assertEquals(NeonBoard.Yellow, sectionAccent(library("4K TV Shows (LAN)", CollectionType.TVSHOWS)))
        assertEquals(NeonBoard.Green, sectionAccent(library("Live TV", CollectionType.LIVETV)))
        assertEquals(NeonBoard.Volt, sectionAccent(library("Music", CollectionType.MUSIC)))
        assertEquals(sectionAccent(library("ufc", CollectionType.MOVIES)), sectionAccent(library("  UFC ", CollectionType.TVSHOWS)))
    }

    @Test
    fun `items carry their type color`() {
        assertEquals(NeonBoard.Orange, typeAccent(BaseItemKind.MOVIE))
        assertEquals(NeonBoard.Yellow, typeAccent(BaseItemKind.SERIES))
        assertEquals(NeonBoard.Yellow, typeAccent(BaseItemKind.SEASON))
        assertEquals(NeonBoard.Yellow, typeAccent(BaseItemKind.EPISODE))
        assertEquals(NeonBoard.Green, typeAccent(BaseItemKind.TV_CHANNEL))
        assertEquals(NeonBoard.Green, typeAccent(BaseItemKind.RECORDING))
        assertEquals(NeonBoard.Cyan, typeAccent(BaseItemKind.TV_PROGRAM))
        assertEquals(NeonBoard.Volt, typeAccent(BaseItemKind.MUSIC_ALBUM))
        assertEquals(NeonBoard.Volt, typeAccent(null))
    }

    @Test
    fun `tints composite the accent over the panel colors`() {
        val chip = NeonBoard.chipOn(NeonBoard.Volt)
        val now = NeonBoard.nowFill(NeonBoard.Cyan)
        assertEquals(1f, chip.alpha)
        assertEquals(1f, now.alpha)
        // A 14 % volt over card is brighter than card but nowhere near the accent.
        assert(chip.green > NeonBoard.Card.green && chip.green < NeonBoard.Volt.green)
        assert(now.blue > NeonBoard.Stage.blue && now.blue < NeonBoard.Cyan.blue)
    }
}
