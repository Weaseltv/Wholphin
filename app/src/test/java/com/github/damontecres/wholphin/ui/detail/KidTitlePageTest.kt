package com.github.damontecres.wholphin.ui.detail

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

    private fun userData(positionMinutes: Int = 0) =
        UserItemDataDto(
            playbackPositionTicks = ticks(positionMinutes),
            playCount = 0,
            isFavorite = false,
            played = false,
            key = "key",
            itemId = UUID.randomUUID(),
        )

    private fun movie(
        runtimeMinutes: Int = 98,
        positionMinutes: Int = 0,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.MOVIE,
        runTimeTicks = ticks(runtimeMinutes),
        userData = userData(positionMinutes),
    )

    private fun episode(
        season: Int?,
        number: Int?,
        name: String? = "Episode $season-$number",
        positionMinutes: Int = 0,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.EPISODE,
        parentIndexNumber = season,
        indexNumber = number,
        name = name,
        runTimeTicks = ticks(22),
        userData = userData(positionMinutes),
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

    // --- Series ---

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
