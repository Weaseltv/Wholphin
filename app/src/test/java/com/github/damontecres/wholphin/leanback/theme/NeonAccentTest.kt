package com.github.damontecres.wholphin.leanback.theme

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
 * carry their type color, named comedy and sports libraries retain their accents.
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
                sectionAccent(library("Sports", CollectionType.MOVIES)),
                sectionAccent(library("Collections", CollectionType.BOXSETS)),
                RailAccents.SEARCH,
                RailAccents.HOME,
                RailAccents.SETTINGS,
            )
        assertEquals(accents.size, accents.toSet().size)
    }

    @Test
    fun `4K libraries share their regular counterpart's colour`() {
        assertEquals(NeonSections.Movies.border, sectionAccent(library("4K Movies (LAN)", CollectionType.MOVIES)))
        assertEquals(NeonSections.TvShows.border, sectionAccent(library("4K TV Shows (LAN)", CollectionType.TVSHOWS)))
        assertEquals(NeonColors.VOLT, sectionAccent(library("Photos", CollectionType.PHOTOS)))
        assertEquals(sectionAccent(library("ufc", CollectionType.MOVIES)), sectionAccent(library("  UFC ", CollectionType.TVSHOWS)))
    }

    @Test
    fun `items carry their type color`() {
        assertEquals(NeonSections.Movies.border, typeAccent(BaseItemKind.MOVIE))
        assertEquals(NeonSections.TvShows.border, typeAccent(BaseItemKind.SERIES))
        assertEquals(NeonSections.TvShows.border, typeAccent(BaseItemKind.SEASON))
        assertEquals(NeonSections.TvShows.border, typeAccent(BaseItemKind.EPISODE))
        assertEquals(NeonColors.VOLT, typeAccent(BaseItemKind.PHOTO))
        assertEquals(NeonColors.VOLT, typeAccent(null))
    }

    @Test
    fun `tints composite the accent over the panel colors`() {
        val chip = NeonColors.chipOn(NeonColors.VOLT)
        assertEquals(0xFF, chip ushr 24)

        fun green(color: Int) = (color shr 8) and 0xFF
        // A 14 % volt over card is brighter than card but nowhere near the accent.
        assert(green(chip) > green(NeonColors.CARD) && green(chip) < green(NeonColors.VOLT))
    }
}
