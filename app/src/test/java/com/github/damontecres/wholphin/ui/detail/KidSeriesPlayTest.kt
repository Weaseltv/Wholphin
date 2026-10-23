package com.github.damontecres.wholphin.ui.detail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The kid series page's Play button: when it looks, and how many requests that takes (review round 2) */
@OptIn(ExperimentalCoroutinesApi::class)
class KidSeriesPlayTest {
    private fun episode(
        season: Int,
        number: Int,
    ) = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, parentIndexNumber = season, indexNumber = number)

    private class FakeSource(
        var nextUp: List<BaseItemDto>,
        val all: List<BaseItemDto>,
    ) : KidEpisodeSource {
        var nextUpCalls = 0
        var pageCalls = 0

        override suspend fun nextUp(): List<BaseItemDto> {
            nextUpCalls++
            return nextUp
        }

        override suspend fun episodes(
            startIndex: Int,
            limit: Int,
        ): KidTitlePage.EpisodePage {
            pageCalls++
            return KidTitlePage.EpisodePage(all.drop(startIndex).take(limit), all.size)
        }
    }

    private val published = mutableListOf<KidPlay?>()

    private fun TestScope.play(source: FakeSource) = KidSeriesPlay(this, source, { published += it })

    @Test
    fun `first open asks once, the next up only, and the first display doesn't ask again`() =
        runTest {
            val next = episode(2, 5)
            val source = FakeSource(listOf(next), all = (1..250).map { episode(1, it) })
            val play = play(source)
            play.open()
            // The page's first resume comes with its first display
            play.resumed()
            advanceUntilIdle()
            assertEquals(1, source.nextUpCalls)
            assertEquals(0, source.pageCalls)
            assertEquals(next.id, published.single()!!.itemId)
        }

    @Test
    fun `the full scan runs only when the next up is empty, once per open`() =
        runTest {
            val all = (1..250).map { episode(1, it) }
            val source = FakeSource(emptyList(), all)
            val play = play(source)
            play.open()
            play.resumed()
            advanceUntilIdle()
            assertEquals(1, source.nextUpCalls)
            // 250 episodes: 3 pages of 100
            assertEquals(3, source.pageCalls)
            assertEquals(all.first().id, published.single()!!.itemId)
        }

    @Test
    fun `coming back from playback looks once more`() =
        runTest {
            val source = FakeSource(listOf(episode(1, 1)), all = emptyList())
            val play = play(source)
            play.open()
            play.resumed()
            advanceUntilIdle()
            val next = episode(1, 2)
            source.nextUp = listOf(next)
            play.resumed()
            advanceUntilIdle()
            assertEquals(2, source.nextUpCalls)
            assertEquals(next.id, published.last()!!.itemId)
        }

    @Test
    fun `nothing to play publishes no button`() =
        runTest {
            val play = play(FakeSource(emptyList(), emptyList()))
            play.open()
            advanceUntilIdle()
            assertNull(published.single())
        }
}
