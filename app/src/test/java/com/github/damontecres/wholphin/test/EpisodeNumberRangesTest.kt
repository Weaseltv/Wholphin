package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.coversEpisodeNumber
import com.github.damontecres.wholphin.services.KidHomeCache
import com.github.damontecres.wholphin.ui.detail.KidPlayLabel
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.detail.series.findIndexByNumberOrId
import com.github.damontecres.wholphin.ui.formatEpisodeNumber
import com.github.damontecres.wholphin.ui.formatEpisodeNumberShort
import com.github.damontecres.wholphin.ui.formatSeasonEpisode
import com.github.damontecres.wholphin.ui.seasonEpisode
import com.github.damontecres.wholphin.ui.seasonEpisodePadded
import com.github.damontecres.wholphin.util.BlockingList
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Metadata fixtures, not a live-server or on-device verification. */
class EpisodeNumberRangesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun episode(season: Int, number: Int, end: Int? = null, name: String = "Episode") =
        BaseItemDto(
            id = UUID.randomUUID(), type = BaseItemKind.EPISODE, seriesName = "Regular Show",
            name = name, parentIndexNumber = season, indexNumber = number, indexNumberEnd = end,
        )

    @Test
    fun `SDK decodes a range on single items and Items Episodes NextUp result envelopes`() {
        // Shape and values supplied from Regular Show S4 on the owner's server; no Fields needed.
        val item = """{"Id":"00000000000000000000000000000001","Type":"Episode","Name":"Exit 9B / Starter Pack","ParentIndexNumber":4,"IndexNumber":1,"IndexNumberEnd":2}"""
        val decoded = json.decodeFromString<BaseItemDto>(item)
        assertEquals(2, decoded.indexNumberEnd)
        val result = json.decodeFromString<BaseItemDtoQueryResult>("""{"Items":[$item],"TotalRecordCount":1,"StartIndex":0}""")
        assertEquals(decoded, result.items.single())
        assertEquals("S4 E1-2", BaseItem(result.items.single()).data.seasonEpisode)
        assertEquals(decoded, json.decodeFromString<BaseItem>(json.encodeToString(BaseItem(decoded))).data)
        val cached = KidHomeCache.displayOnly(decoded)
        assertEquals(2, cached.indexNumberEnd)
        assertEquals("S4 E1-2", json.decodeFromString<BaseItemDto>(json.encodeToString(cached)).seasonEpisode)
    }

    @Test
    fun `range formatting rejects absent equal or backwards ends`() {
        for (end in listOf(null, 1, 0)) {
            assertEquals("1", formatEpisodeNumber(1, end))
            assertEquals("E1", formatEpisodeNumberShort(1, end))
            assertEquals("S4 E1", formatSeasonEpisode(4, 1, end))
            assertEquals("S04E01", formatSeasonEpisode(4, 1, end, padded = true))
        }
        assertEquals("1-2", formatEpisodeNumber(1, 2))
        assertEquals("6-7", formatEpisodeNumber(6, 7))
        assertEquals("S04E01-02", formatSeasonEpisode(4, 1, 2, padded = true))
        assertNull(formatSeasonEpisode(null, 1, 2))
        assertNull(formatSeasonEpisode(4, null, 2))
    }

    @Test
    fun `Regular Show S4 then 3 and S3 pairs reach every shared display label`() {
        val pair = episode(4, 1, 2, "Exit 9B / Starter Pack")
        val items = listOf(pair, episode(4, 3))
        assertEquals(listOf("1-2", "3"), items.map { formatEpisodeNumber(it.indexNumber!!, it.indexNumberEnd) })
        val base = BaseItem(pair)
        assertEquals("S4E1-2", base.ui.episodeCornerText)
        assertEquals("S4E1-2", base.ui.episodeUnplayedCornerText)
        assertEquals("S4 E1-2 - Exit 9B / Starter Pack", base.subtitle)
        assertEquals("S04E01-02 - Exit 9B / Starter Pack", base.subtitleLong)
        assertTrue(base.ui.quickDetails.basic.text.contains("S4 E1-2"))
        assertEquals("S4 E1-2", KidTitlePage.episodeCode(pair))
        assertEquals(KidPlayLabel.Episode(4, 1, 2), KidTitlePage.seriesPlay(pair)!!.label)
        assertEquals("S3 E6-7", episode(3, 6, 7).seasonEpisode)
    }

    @Test
    fun `172 Paw Patrol paired-file fixtures remain 172 items and cover both numbers`() = runTest {
        // Synthetic stress fixture with 172 files, not a snapshot of the owner's library.
        val files = (1..172).map { episode(1, it * 2 - 1, it * 2).copy(seriesName = "Paw Patrol") }
        assertEquals(172, files.map(::BaseItem).size)
        val list = BlockingList.of<BaseItem?>(files.map(::BaseItem))
        files.forEachIndexed { index, file ->
            assertTrue(file.coversEpisodeNumber(file.indexNumber!!))
            assertTrue(file.coversEpisodeNumber(file.indexNumberEnd!!))
            assertEquals(index, findIndexByNumberOrId(file.indexNumberEnd, null, list, parentIndex = 1))
        }
        assertFalse(files.first().coversEpisodeNumber(3))
        // A played pair advances to the next file, and a started pair still resumes that same file.
        val first = files.first().copy(userData = UserItemDataDto(
            playbackPositionTicks = 0, playCount = 1, isFavorite = false, played = true, key = "pair", itemId = files.first().id,
        ))
        assertEquals(files[1].id, KidTitlePage.nextEpisode(emptyList(), listOf(first) + files.drop(1))!!.id)
        val started = first.copy(runTimeTicks = 600_000_000, userData = first.userData!!.copy(played = false, playbackPositionTicks = 100_000_000))
        val play = KidTitlePage.seriesPlay(KidTitlePage.nextEpisode(listOf(started), files))!!
        assertEquals(first.id, play.itemId)
        assertEquals(10_000L, play.positionMs)
    }

    @Test
    fun `an interior number finds its paired file after other episodes and ignores other seasons`() = runTest {
        val pair = episode(3, 6, 7)
        val files = listOf(episode(0, 6, 7)) + (1..5).map { episode(3, it) } + pair + episode(3, 8)
        assertEquals(6, findIndexByNumberOrId(7, null, BlockingList.of(files.map(::BaseItem)), parentIndex = 3))
        assertEquals(6, findIndexByNumberOrId(null, pair.id, BlockingList.of(files.map(::BaseItem)), parentIndex = 3))
    }

    @Test
    fun `normal Regular Show S1 fixtures keep the previous single episode presentation`() {
        for (number in 1..12) {
            val dto = episode(1, number)
            val base = BaseItem(dto)
            assertEquals(number.toString(), formatEpisodeNumber(number))
            assertEquals("S1E$number", base.ui.episodeCornerText)
            assertEquals("S1 E$number - Episode", base.subtitle)
            assertEquals("S01E${number.toString().padStart(2, '0')}", dto.seasonEpisodePadded)
            assertEquals("S1 E$number", KidTitlePage.episodeCode(dto))
        }
    }
}
