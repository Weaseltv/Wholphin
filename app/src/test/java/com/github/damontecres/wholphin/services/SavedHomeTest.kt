package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidContentGuard
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.test.TestHomeRowSamples
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** An adult's Home saved on the TV (speed plan step 17): one profile incarnation, server and layout each, never a kid's */
class SavedHomeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val guard = mockk<KidContentGuard>()
    private val context by lazy { mockk<Context> { every { cacheDir } returns folder.root } }
    private val store by lazy { SavedHome(context, guard).also { it.clock = { now } } }
    private var now = 10 * 24 * 60 * 60 * 1000L
    private val server = UUID.randomUUID()
    private val adult = JellyfinUser(rowId = 3, id = UUID.randomUUID(), name = "Ana", serverId = server, accessToken = "t")
    private val library = Library(UUID.randomUUID(), "Movies", BaseItemKind.COLLECTION_FOLDER, CollectionType.MOVIES)
    private val movie =
        BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.MOVIE,
                name = "Heat",
                overview = "A crew and a detective.",
                userData =
                    UserItemDataDto(
                        playbackPositionTicks = 42,
                        playCount = 1,
                        isFavorite = true,
                        played = false,
                        key = "k",
                        itemId = UUID.randomUUID(),
                    ),
            ),
            libraryId = library.itemId,
        )

    /** One loaded row per kind of Home row the app can save, so every row type is known to survive the round trip */
    private fun loadedRows(): List<HomeRowLoadingState> =
        TestHomeRowSamples.SAMPLES.mapIndexed { index, config ->
            HomeRowLoadingState.Success(
                title = if (index % 2 == 0) StringStringProvider("Row $index") else ResStringProvider(R.string.unknown),
                items = listOf(movie, null),
                viewOptions = HomeRowViewOptions(),
                rowType = config,
                showViewMore = index % 2 == 0,
            )
        }

    private fun saved(
        user: JellyfinUser = adult,
        layout: String = "layout-a",
        savedAt: Long = now,
    ) = SavedHome.Saved(
        serverId = user.serverId.toString(),
        userId = user.id.toString(),
        profileRowId = user.rowId,
        layout = layout,
        savedAt = savedAt,
        libraries = listOf(library),
        rows = store.rowsToSave(loadedRows())!!,
    )

    @Test
    fun `A saved Home reads back as the rows it was saved from`() {
        val rows = loadedRows()
        store.write(saved()) { true }
        val read = store.read(adult, "layout-a")!!
        assertEquals(listOf(library), read.libraries)
        val shown = read.rows.map { it.toRow() }
        assertEquals(rows.size, shown.size)
        rows.zip(shown).forEach { (before, after) ->
            before as HomeRowLoadingState.Success
            after as HomeRowLoadingState.Success
            assertEquals(before.title, after.title)
            assertEquals(before.rowType, after.rowType)
            assertEquals(before.viewOptions, after.viewOptions)
            assertEquals(before.showViewMore, after.showViewMore)
            // Cards not yet loaded aren't kept; the ones that were come back whole, library included
            assertEquals(listOf(movie), after.items)
            assertEquals(library.itemId, after.items.single()?.libraryId)
        }
    }

    @Test
    fun `A saved Home reads back only for its own server, profile, saved row and layout`() {
        store.write(saved()) { true }
        assertTrue(store.read(adult, "layout-a") != null)
        assertNull(store.read(adult.copy(id = UUID.randomUUID()), "layout-a"))
        assertNull(store.read(adult.copy(serverId = UUID.randomUUID()), "layout-a"))
        // A profile removed and added again with the same ids has a new saved row
        assertNull(store.read(adult.copy(rowId = 4), "layout-a"))
        // Other rows, other Home settings or another app version
        assertNull(store.read(adult, "layout-b"))
    }

    @Test
    fun `The layout key follows the Home settings`() {
        val settings = HomePageResolvedSettings(emptyList())
        val few = HomePagePreferences.newBuilder().setMaxItemsPerRow(10).build()
        val many = HomePagePreferences.newBuilder().setMaxItemsPerRow(25).build()
        assertEquals(SavedHome.layoutKey(settings, few), SavedHome.layoutKey(settings, few))
        assertNotEquals(SavedHome.layoutKey(settings, few), SavedHome.layoutKey(settings, many))
    }

    @Test
    fun `A Home that isn't fully loaded isn't saved`() {
        val rows = loadedRows()
        assertNull(store.rowsToSave(emptyList()))
        assertNull(store.rowsToSave(rows + HomeRowLoadingState.Loading(StringStringProvider("Loading"))))
        assertNull(store.rowsToSave(rows + HomeRowLoadingState.Error(StringStringProvider("Failed"))))
    }

    @Test
    fun `A Home whose session moved on isn't written`() {
        store.write(saved()) { false }
        assertNull(store.read(adult, "layout-a"))
    }

    @Test
    fun `An old saved Home is a miss, and removed`() {
        store.write(saved()) { true }
        now += SavedHome.MAX_AGE.inWholeMilliseconds + 1
        assertNull(store.read(adult, "layout-a"))
        assertFalse(File(folder.root, "saved-home/${server}_${adult.id}.json").exists())
    }

    @Test
    fun `A saved Home that can't be read is a miss, and removed`() {
        val file = File(folder.root, "saved-home/${server}_${adult.id}.json").apply { parentFile?.mkdirs() }
        file.writeText("{ not json")
        assertNull(store.read(adult, "layout-a"))
        assertFalse(file.exists())
    }

    @Test
    fun `Removing a profile or its server removes its saved Home`() {
        val other = adult.copy(rowId = 9, id = UUID.randomUUID())
        val elsewhere = adult.copy(rowId = 10, id = UUID.randomUUID(), serverId = UUID.randomUUID())
        listOf(adult, other, elsewhere).forEach { user -> store.write(saved(user)) { true } }
        SavedHome.purgeUser(context, adult.serverId, adult.id)
        assertNull(store.read(adult, "layout-a"))
        assertTrue(store.read(other, "layout-a") != null)
        SavedHome.purgeServer(context, server)
        assertNull(store.read(other, "layout-a"))
        assertTrue(store.read(elsewhere, "layout-a") != null)
        store.delete(elsewhere.serverId, elsewhere.id)
        assertNull(store.read(elsewhere, "layout-a"))
    }

    @Test
    fun `Only a session without kid content rules counts as an adult's`() =
        runTest {
            coEvery { guard.currentPolicy() } returns null
            assertTrue(store.adultSession())
            // A kid, or an unknown profile on a protected TV
            coEvery { guard.currentPolicy() } returns KidContentGuard.Policy("unknown", null)
            assertFalse(store.adultSession())
        }
}
