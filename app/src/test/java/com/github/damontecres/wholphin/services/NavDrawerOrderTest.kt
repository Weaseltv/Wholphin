package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class NavDrawerOrderTest {
    private val context = mockk<Context>(relaxed = true)

    private fun library(
        name: String,
        type: CollectionType,
    ): ServerNavDrawerItem {
        val id = UUID.randomUUID()
        return ServerNavDrawerItem(id, name, Destination.MediaItem(id, BaseItemKind.COLLECTION_FOLDER, type), type)
    }

    /** Libraries in the new server order, with Playlists interspersed. */
    private val serverOrder =
        listOf(
            NavDrawerItem.Favorites,
            NavDrawerItem.Discover,
            library("Collections", CollectionType.BOXSETS),
            library("Movies", CollectionType.MOVIES),
            library("Sports", CollectionType.MOVIES),
            library("Playlists", CollectionType.PLAYLISTS),
            library("Stand Up Comedy", CollectionType.MOVIES),
            library("TV Shows", CollectionType.TVSHOWS),
        )

    private fun List<NavDrawerItem>.names() = map { (it as? ServerNavDrawerItem)?.name ?: it.id }

    @Test
    fun `WeaselPlex uses the approved named library order with Playlists last`() {
        assumeTrue(BuildConfig.FLAVOR == "weaselfin")
        val sorted = serverOrder.sortedBy { defaultNavOrder(it, context) }
        assertEquals(
            listOf("a_favorites", "a_discover", "Collections", "Movies", "TV Shows", "Stand Up Comedy", "Sports", "Playlists"),
            sorted.names(),
        )
    }

    @Test
    fun `Playlists goes last for any configured order, whatever the server calls it`() {
        val renamed = library("Mixtapes", CollectionType.PLAYLISTS)
        val sorted = (listOf(renamed) + serverOrder).sortedBy { defaultNavOrder(it, context, order = "Movies") }
        assertEquals(listOf("Mixtapes", "Playlists"), sorted.names().takeLast(2))
        assertEquals(
            listOf("Movies", "Sports", "Stand Up Comedy", "a_favorites", "a_discover", "Collections", "TV Shows"),
            sorted.names().dropLast(2),
        )
    }

    @Test
    fun `Configured names rank matching libraries before unknown libraries and Playlists`() {
        val sorted = serverOrder.sortedBy { defaultNavOrder(it, context, order = "a_favorites,a_discover,TV Shows,Movies") }
        assertEquals(
            listOf("a_favorites", "a_discover", "TV Shows", "Movies", "Sports", "Stand Up Comedy", "Collections", "Playlists"),
            sorted.names(),
        )
    }

    @Test
    fun `Without a configured order the server's order is kept`() {
        val sorted = serverOrder.sortedBy { defaultNavOrder(it, context, order = "") }
        assertEquals(serverOrder.names(), sorted.names())
    }
}
