package com.github.damontecres.wholphin.ui.detail

import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/** The kid title page's decisions (owner mockup kid-title-page.html, 2026-10-23) */
class KidTitlePageTest {
    private fun ticks(minutes: Int) = minutes * 60L * 10_000_000L

    private fun userData(
        positionMinutes: Int = 0,
        played: Boolean = false,
    ) = UserItemDataDto(
        playbackPositionTicks = ticks(positionMinutes),
        playCount = 0,
        isFavorite = false,
        played = played,
        key = "key",
        itemId = UUID.randomUUID(),
    )

    private fun movie(
        runtimeMinutes: Int? = 98,
        positionMinutes: Int = 0,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.MOVIE,
        runTimeTicks = runtimeMinutes?.let { ticks(it) },
        userData = userData(positionMinutes),
    )

    private fun episode(
        season: Int?,
        number: Int?,
        name: String? = "Episode $season-$number",
        positionMinutes: Int = 0,
        played: Boolean = false,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.EPISODE,
        parentIndexNumber = season,
        indexNumber = number,
        name = name,
        runTimeTicks = ticks(22),
        userData = userData(positionMinutes, played),
    )

    // --- Movie ---

    @Test
    fun `an unwatched movie says Play from the start, with Watchlist only`() {
        val play = KidTitlePage.moviePlay(movie())
        assertEquals(KidPlayLabel.Play, play.label)
        assertEquals(0L, play.positionMs)
        assertNull(play.progress)
        assertNull(play.remaining)
        assertEquals(listOf(KidAction.WATCHLIST), KidTitlePage.movieActions(play))
    }

    @Test
    fun `a partly watched movie says Keep watching from where it was, with progress, time left and Start over`() {
        val dto = movie(runtimeMinutes = 100, positionMinutes = 48)
        val play = KidTitlePage.moviePlay(dto)
        assertEquals(KidPlayLabel.KeepWatching, play.label)
        assertEquals(48.minutes.inWholeMilliseconds, play.positionMs)
        assertEquals(.48f, play.progress!!, .001f)
        assertEquals(52.minutes, play.remaining)
        assertEquals(dto.id, play.itemId)
        assertEquals(listOf(KidAction.START_OVER, KidAction.WATCHLIST), KidTitlePage.movieActions(play))
    }

    @Test
    fun `a saved position without a runtime still resumes, without progress or time left`() {
        val play = KidTitlePage.moviePlay(movie(runtimeMinutes = null, positionMinutes = 10))
        assertEquals(KidPlayLabel.KeepWatching, play.label)
        assertEquals(10.minutes.inWholeMilliseconds, play.positionMs)
        assertNull(play.progress)
        assertNull(play.remaining)
        assertEquals(listOf(KidAction.START_OVER, KidAction.WATCHLIST), KidTitlePage.movieActions(play))
    }

    @Test
    fun `a stale position at or past the end plays from the start`() {
        for (position in listOf(100, 130)) {
            val play = KidTitlePage.moviePlay(movie(runtimeMinutes = 100, positionMinutes = position))
            assertEquals(KidPlayLabel.Play, play.label)
            assertEquals(0L, play.positionMs)
            assertNull(play.progress)
            assertNull(play.remaining)
            assertEquals(listOf(KidAction.WATCHLIST), KidTitlePage.movieActions(play))
            // An episode too: its runtime is 22 minutes
            assertEquals(0L, KidTitlePage.seriesPlay(episode(1, 1, positionMinutes = position))!!.positionMs)
        }
        assertEquals(0L, KidTitlePage.seriesPlay(episode(1, 1, positionMinutes = 22))!!.positionMs)
        assertEquals(21.minutes.inWholeMilliseconds, KidTitlePage.seriesPlay(episode(1, 1, positionMinutes = 21))!!.positionMs)
    }

    // --- Series ---

    @Test
    fun `seasons in order with specials interleaved give the next regular episode, across seasons`() {
        // As the server orders them: specials can sit between seasons' episodes
        val s1e1 = episode(1, 1, played = true)
        val s0e1 = episode(0, 1)
        val s1e2 = episode(1, 2, played = true)
        val s0e2 = episode(0, 2)
        val s2e1 = episode(2, 1)
        val s2e2 = episode(2, 2)
        assertEquals(s2e1, KidTitlePage.nextEpisode(emptyList(), listOf(s1e1, s0e1, s1e2, s0e2, s2e1, s2e2)))
    }

    @Test
    fun `a started episode before the last watched one is resumed when nothing follows`() {
        val started = episode(1, 1, positionMinutes = 9)
        val watched = episode(1, 2, played = true)
        val chosen = KidTitlePage.nextEpisode(emptyList(), listOf(started, watched))
        assertEquals(started, chosen)
        assertEquals(9.minutes.inWholeMilliseconds, KidTitlePage.seriesPlay(chosen)!!.positionMs)
    }

    @Test
    fun `a hidden next-up never sends Play backward`() {
        // S1E1 watched, S1E2 above the kid's level (filtered out with the server's next up), S1E3 allowed
        val e1 = episode(1, 1, played = true)
        val e3 = episode(1, 3)
        val e4 = episode(1, 4)
        assertEquals(e3, KidTitlePage.nextEpisode(emptyList(), listOf(e1, e3, e4)))
        // A started one counts as one to watch, resumed where it was
        val started = episode(1, 3, positionMinutes = 5)
        assertEquals(started, KidTitlePage.nextEpisode(emptyList(), listOf(e1, started, e4)))
    }

    @Test
    fun `nothing left after the last watched one plays an earlier unwatched one, or the first to watch again`() {
        val e1 = episode(1, 1)
        val e3 = episode(1, 3, played = true)
        assertEquals(e1, KidTitlePage.nextEpisode(emptyList(), listOf(e1, e3)))
        val watched = listOf(episode(1, 1, played = true), episode(1, 2, played = true))
        assertEquals(watched.first(), KidTitlePage.nextEpisode(emptyList(), watched))
    }

    @Test
    fun `episodes are paged until the series ends, past pages the filter emptied`() =
        runTest {
            // 150 episodes; the filter removed all of the first 100 (and lowered that page's count by as many)
            val allowed = (101..150).map { episode(2, it) }
            val asked = mutableListOf<Int>()
            val episodes =
                KidTitlePage.allowedEpisodes { startIndex, limit ->
                    asked += startIndex
                    when (startIndex) {
                        0 -> KidTitlePage.EpisodePage(emptyList(), 150 - limit)
                        100 -> KidTitlePage.EpisodePage(allowed, 150)
                        else -> error("asked past the end: $startIndex")
                    }
                }
            assertEquals(listOf(0, 100), asked)
            assertEquals(allowed, episodes)
            assertEquals(allowed.first(), KidTitlePage.nextEpisode(emptyList(), episodes))
        }

    @Test
    fun `paging stops at the end, on an empty page without a count, and only for a server that never ends`() =
        runTest {
            val one = listOf(episode(1, 1))
            var pages = 0
            assertEquals(one, KidTitlePage.allowedEpisodes { _, _ -> KidTitlePage.EpisodePage(one, 1).also { pages++ } })
            assertEquals(1, pages)
            // Without a count, a partly filtered page goes on; an empty one ends it
            pages = 0
            val noCount =
                KidTitlePage.allowedEpisodes { startIndex, _ ->
                    pages++
                    KidTitlePage.EpisodePage(if (startIndex == 0) one else emptyList(), null)
                }
            assertEquals(one, noCount)
            assertEquals(2, pages)
            pages = 0
            KidTitlePage.allowedEpisodes { _, _ -> KidTitlePage.EpisodePage(emptyList(), Int.MAX_VALUE).also { pages++ } }
            assertEquals(KidTitlePage.SAFETY_PAGES, pages)
        }

    @Test
    fun `episodes past the first 2000 are found`() =
        runTest {
            // 2500 episodes; this kid may watch only the last 100 (the filter lowers each page's count by what it removed)
            val server = 2500
            val allowed = (2401..2500).map { episode(30, it) }
            val episodes =
                KidTitlePage.allowedEpisodes { startIndex, limit ->
                    if (startIndex >= 2400) {
                        KidTitlePage.EpisodePage(allowed.drop(startIndex - 2400).take(limit), server)
                    } else {
                        KidTitlePage.EpisodePage(emptyList(), server - minOf(limit, server - startIndex))
                    }
                }
            assertEquals(allowed, episodes)
            assertEquals(allowed.first(), KidTitlePage.nextEpisode(emptyList(), episodes))
        }

    @Test
    fun `a series has Watchlist and Shuffle, never Start over`() {
        assertEquals(listOf(KidAction.WATCHLIST, KidAction.SHUFFLE), KidTitlePage.seriesActions)
    }

    @Test
    fun `the next-up episode plays first, resumed where it was`() {
        val next = episode(2, 5, "The Sneezing Cloud", positionMinutes = 7)
        val chosen = KidTitlePage.nextEpisode(listOf(next), listOf(episode(1, 1)))
        assertEquals(next, chosen)
        val play = KidTitlePage.seriesPlay(chosen)!!
        assertEquals(KidPlayLabel.Episode(2, 5), play.label)
        assertEquals("The Sneezing Cloud", play.episodeTitle)
        assertEquals(7.minutes.inWholeMilliseconds, play.positionMs)
    }

    @Test
    fun `nothing watched plays the first regular episode, after any specials`() {
        val special = episode(0, 1)
        val first = episode(1, 1)
        val chosen = KidTitlePage.nextEpisode(emptyList(), listOf(special, first, episode(1, 2)))
        assertEquals(first, chosen)
        assertEquals(KidPlayLabel.Episode(1, 1), KidTitlePage.seriesPlay(chosen)!!.label)
        // Only specials: the first of them
        assertEquals(special, KidTitlePage.nextEpisode(emptyList(), listOf(special)))
    }

    @Test
    fun `no episode this kid may watch means no Play button`() {
        // The kid content filter left nothing in either list
        assertNull(KidTitlePage.nextEpisode(emptyList(), emptyList()))
        assertNull(KidTitlePage.seriesPlay(null))
        // Anything that isn't an episode is never offered
        val notAnEpisode = movie()
        assertNull(KidTitlePage.nextEpisode(listOf(notAnEpisode), listOf(notAnEpisode)))
    }

    @Test
    fun `an episode without numbers just says Play`() {
        val play = KidTitlePage.seriesPlay(episode(null, null, name = " "))!!
        assertEquals(KidPlayLabel.Play, play.label)
        assertNull(play.episodeTitle)
    }

    @Test
    fun `the description shows up to 8 lines, 6 when the header is compact`() {
        assertEquals(8, KidTitlePage.overviewLines(compact = false))
        assertEquals(6, KidTitlePage.overviewLines(compact = true))
    }
}
