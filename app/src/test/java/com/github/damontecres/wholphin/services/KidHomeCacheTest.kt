package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.kids.KidContentGuard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** The kids Home saved on the TV (owner, 2026-10-23: kids Home speed): one profile, server and ceiling each */
class KidHomeCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val guard = mockk<KidContentGuard>()
    private val cache by lazy { KidHomeCache(mockk<Context> { every { cacheDir } returns folder.root }, mockk(), guard) }
    private val server = UUID.randomUUID()
    private val kid = UUID.randomUUID()
    private val movie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, name = "Moana")

    private fun saved(
        userId: UUID = kid,
        rules: String = "kid-rules-v1:5",
    ) = KidHomeCache.Saved(
        serverId = server.toString(),
        userId = userId.toString(),
        rules = rules,
        savedAt = 1,
        collections = listOf(KidHomeCache.SavedCollection(UUID.randomUUID().toString(), "Cartoon Adventures")),
        rows = mapOf("watchAgain" to KidHomeCache.SavedRow(listOf(KidHomeCache.SavedItem(movie)))),
    )

    @Test
    fun `A saved Home reads back only for its own server, profile and rules`() {
        val saved = saved()
        cache.write(saved)
        assertEquals(saved, cache.read(server, kid, "kid-rules-v1:5"))
        // Another kid (or an adult) has no file of their own here, and never gets this one
        assertNull(cache.read(server, UUID.randomUUID(), "kid-rules-v1:5"))
        // The same kid under another age limit, or on another server
        assertNull(cache.read(server, kid, "kid-rules-v1:9"))
        assertNull(cache.read(UUID.randomUUID(), kid, "kid-rules-v1:5"))
    }

    @Test
    fun `Each profile has its own saved Home`() {
        val other = UUID.randomUUID()
        cache.write(saved())
        cache.write(saved(userId = other, rules = "kid-rules-v1:9"))
        assertEquals(kid.toString(), cache.read(server, kid, "kid-rules-v1:5")?.userId)
        assertEquals(other.toString(), cache.read(server, other, "kid-rules-v1:9")?.userId)
    }

    @Test
    fun `Saved items are checked again before they show, and hidden if that fails`() =
        runTest {
            val hidden = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE), false)
            val shown = BaseItem(movie, false)
            coEvery { guard.visibleIds(any(), any(), any()) } returns setOf(movie.id)
            assertEquals(listOf(shown), cache.visibleNow(listOf(hidden, shown)))
            coEvery { guard.visibleIds(any(), any(), any()) } throws RuntimeException("offline")
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
