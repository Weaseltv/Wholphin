package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidContentGuard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/** The kids Home saved on the TV (owner, 2026-10-23: kids Home speed): one profile incarnation, server and ceiling each */
class KidHomeCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val guard = mockk<KidContentGuard>()
    private val context by lazy { mockk<Context> { every { cacheDir } returns folder.root } }
    private val cache by lazy { KidHomeCache(context, mockk(), guard).also { it.clock = { now } } }
    private var now = 10 * 24 * 60 * 60 * 1000L
    private val server = UUID.randomUUID()
    private val kid = JellyfinUser(rowId = 7, id = UUID.randomUUID(), name = "Kai", serverId = server, accessToken = "t")
    private val movie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, name = "Moana")

    private fun saved(
        user: JellyfinUser = kid,
        rules: String = "kid-rules-v1:5",
        rows: Map<String, KidHomeCache.SavedRow> = mapOf("watchAgain" to KidHomeCache.SavedRow(listOf(KidHomeCache.SavedItem(movie)))),
        savedAt: Long = now,
    ) = KidHomeCache.Saved(
        serverId = user.serverId.toString(),
        userId = user.id.toString(),
        profileRowId = user.rowId,
        rules = rules,
        savedAt = savedAt,
        collections = listOf(KidHomeCache.SavedCollection(UUID.randomUUID().toString(), "Cartoon Adventures")),
        rows = rows,
    )

    private fun files() = File(folder.root, "kid-home").listFiles()?.toList().orEmpty()

    @Test
    fun `A saved Home reads back only for its own server, profile and rules`() {
        val saved = saved()
        cache.write(saved)
        assertEquals(saved, cache.read(kid, "kid-rules-v1:5"))
        // Another kid (or an adult) has no file of their own here, and never gets this one
        assertNull(cache.read(kid.copy(id = UUID.randomUUID()), "kid-rules-v1:5"))
        // The same kid under another age limit, or on another server
        assertNull(cache.read(kid, "kid-rules-v1:9"))
        assertNull(cache.read(kid.copy(serverId = UUID.randomUUID()), "kid-rules-v1:5"))
    }

    @Test
    fun `A profile removed and added again with the same ids starts without the old one's Home`() {
        cache.write(saved())
        // Same server, same user id, same ceiling: a new saved row
        assertNull(cache.read(kid.copy(rowId = 8), "kid-rules-v1:5"))
    }

    @Test
    fun `Removing a profile or its server removes its saved Home`() {
        val other = kid.copy(rowId = 9, id = UUID.randomUUID())
        val elsewhere = kid.copy(rowId = 10, id = UUID.randomUUID(), serverId = UUID.randomUUID())
        listOf(kid, other, elsewhere).forEach { cache.write(saved(it)) }
        KidHomeCache.purgeUser(context, kid.serverId, kid.id)
        assertNull(cache.read(kid, "kid-rules-v1:5"))
        assertTrue(cache.read(other, "kid-rules-v1:5") != null)
        KidHomeCache.purgeServer(context, server)
        assertNull(cache.read(other, "kid-rules-v1:5"))
        assertTrue(cache.read(elsewhere, "kid-rules-v1:5") != null)
    }

    @Test
    fun `A saved Home that can't be read is a miss, and removed`() {
        val file = File(folder.root, "kid-home/${server}_${kid.id}.json").apply { parentFile?.mkdirs() }
        file.writeText("{ not json")
        assertNull(cache.read(kid, "kid-rules-v1:5"))
        assertFalse(file.exists())
    }

    @Test
    fun `A saved Home expires after a week`() {
        cache.write(saved(savedAt = now - KidHomeCache.MAX_AGE.inWholeMilliseconds - 1))
        assertNull(cache.read(kid, "kid-rules-v1:5"))
        assertTrue(files().isEmpty())
    }

    @Test
    fun `Only what a card shows is saved, within the bounds`() {
        val big =
            movie.copy(
                overview = "x".repeat(10_000),
                genres = listOf("Animation"),
                people = emptyList(),
            )
        val many = (0 until 100).map { KidHomeCache.SavedItem(big.copy(id = UUID.randomUUID())) }
        val rows = (0 until 30).associate { "collection:$it" to KidHomeCache.SavedRow(many) }
        cache.write(saved(rows = rows))
        val read = cache.read(kid, "kid-rules-v1:5")!!
        assertTrue(read.rows.size <= KidHomeCache.MAX_ROWS + 2)
        assertTrue(read.rows.values.all { it.items.size <= KidHomeCache.MAX_ITEMS_PER_ROW })
        val item = read.rows.values.first().items.first().dto
        assertNull(item.overview)
        assertNull(item.genres)
        assertEquals(big.name, item.name)
        assertTrue(files().single().length() <= KidHomeCache.MAX_FILE_BYTES)
    }

    @Test
    fun `Too many saved Homes keep only the newest`() {
        (0 until KidHomeCache.MAX_FILES + 5).forEach { index ->
            cache.write(saved(kid.copy(rowId = index, id = UUID.randomUUID())))
            files().forEach { if (it.lastModified() == 0L) it.setLastModified(1) }
        }
        assertTrue(files().size <= KidHomeCache.MAX_FILES)
    }

    @Test
    fun `Saved items are checked again with fresh facts before they show, and hidden if that fails`() =
        runTest {
            val hidden = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE), false)
            val shown = BaseItem(movie, false)
            coEvery { guard.visibleIds(any(), any(), true) } returns setOf(movie.id)
            assertEquals(listOf(shown), cache.visibleNow(listOf(hidden, shown)))
            coVerify(exactly = 0) { guard.visibleIds(any(), any(), false) }
            coEvery { guard.visibleIds(any(), any(), true) } throws RuntimeException("offline")
            assertEquals(emptyList<BaseItem>(), cache.visibleNow(listOf(shown)))
        }

    @Test
    fun `An adult or an unknown ceiling has no rules, so nothing is saved or read for it`() =
        runTest {
            coEvery { guard.currentPolicy() } returns null
            assertNull(cache.rules())
            coEvery { guard.currentPolicy() } returns KidContentGuard.Policy("s:u", null)
            assertNull(cache.rules())
            coEvery { guard.currentPolicy() } returns KidContentGuard.Policy("s:u", 5)
            assertEquals("kid-rules-v1:5", cache.rules())
        }

    @Test
    fun `Rows are saved by kind and collection`() {
        val id = UUID.randomUUID()
        assertEquals("continue", KidHomeCache.rowKey(HomeRowConfig.ContinueWatchingCombined()))
        assertEquals("watchAgain", KidHomeCache.rowKey(KidHomeCollections.watchAgainRow("Watch again")))
        assertEquals("collection:$id", KidHomeCache.rowKey(KidHomeCollections.collectionRow(id)))
    }
}
