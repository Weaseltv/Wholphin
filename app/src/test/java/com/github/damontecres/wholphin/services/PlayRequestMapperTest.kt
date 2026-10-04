package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PlayRequestMapperTest {
    private val controllingUserId = UUID.randomUUID()

    private fun request(
        itemIds: List<java.util.UUID>,
        playCommand: PlayCommand = PlayCommand.PLAY_NOW,
        startPositionTicks: Long? = null,
        startIndex: Int? = null,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
    ) = PlayRequest(
        itemIds = itemIds,
        startPositionTicks = startPositionTicks,
        playCommand = playCommand,
        controllingUserId = controllingUserId,
        subtitleStreamIndex = subtitleStreamIndex,
        audioStreamIndex = audioStreamIndex,
        mediaSourceId = mediaSourceId,
        startIndex = startIndex,
    )

    @Test
    fun `Request without items maps to nothing`() {
        assertNull(PlayRequestMapper.toDestination(request(emptyList())))
    }

    @Test
    fun `Single item keeps the contextual queue and maps position and streams`() {
        val itemId = UUID.randomUUID()
        val destination =
            PlayRequestMapper.toDestination(
                request(
                    itemIds = listOf(itemId),
                    playCommand = PlayCommand.PLAY_SHUFFLE,
                    startPositionTicks = 90.seconds.inWholeTicks,
                    mediaSourceId = "abc",
                    audioStreamIndex = 2,
                    subtitleStreamIndex = -1,
                ),
            )
        assertNotNull(destination)
        destination!!
        assertEquals(itemId, destination.itemId)
        assertEquals(90_000L, destination.positionMs)
        assertTrue(destination.shuffle)
        assertEquals("abc", destination.mediaSourceId)
        assertEquals(2, destination.audioStreamIndex)
        assertEquals(-1, destination.subtitleStreamIndex)
        assertTrue(destination.itemIds.isEmpty())
        assertEquals(0, destination.startIndex)
    }

    @Test
    fun `Multiple items are kept verbatim and the start index is clamped`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val destination = PlayRequestMapper.toDestination(request(itemIds = ids, startIndex = 7))!!
        assertEquals(ids, destination.itemIds)
        assertEquals(2, destination.startIndex)
        assertEquals(ids[2], destination.itemId)
        assertEquals(0L, destination.positionMs)

        val fromStart = PlayRequestMapper.toDestination(request(itemIds = ids))!!
        assertEquals(0, fromStart.startIndex)
        assertEquals(ids[0], fromStart.itemId)
    }

    @Test
    fun `Negative position and audio index are ignored`() {
        val itemId = UUID.randomUUID()
        val destination =
            PlayRequestMapper.toDestination(
                request(itemIds = listOf(itemId), startPositionTicks = -5L, audioStreamIndex = -1),
            )!!
        assertEquals(0L, destination.positionMs)
        assertNull(destination.audioStreamIndex)
    }

    @Test
    fun `Intent params carry every field and shuffle only when requested`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        val params =
            PlayRequestMapper.toIntentParams(
                request(
                    itemIds = ids,
                    playCommand = PlayCommand.PLAY_SHUFFLE,
                    startPositionTicks = 90.seconds.inWholeTicks,
                    startIndex = 1,
                    mediaSourceId = "abc",
                    audioStreamIndex = 2,
                    subtitleStreamIndex = -1,
                ),
            )!!
        assertEquals(ids.joinToString(",") { it.toString() }, params[IntentService.INTENT_ITEM_IDS])
        assertEquals("1", params[IntentService.INTENT_START_INDEX])
        assertEquals("90000", params[IntentService.INTENT_POSITION])
        assertEquals("abc", params[IntentService.INTENT_MEDIA_SOURCE_ID])
        assertEquals("2", params[IntentService.INTENT_AUDIO_STREAM_INDEX])
        assertEquals("-1", params[IntentService.INTENT_SUBTITLE_STREAM_INDEX])
        assertEquals("true", params[IntentService.INTENT_SHUFFLE])

        val minimal = PlayRequestMapper.toIntentParams(request(itemIds = ids.take(1)))!!
        assertEquals(setOf(IntentService.INTENT_ITEM_IDS), minimal.keys)

        assertNull(PlayRequestMapper.toIntentParams(request(emptyList())))
    }

    @Test
    fun `General command arguments are parsed case insensitively`() {
        assertEquals(2, PlayRequestMapper.parseIndex(mapOf("Index" to "2")))
        assertEquals(-1, PlayRequestMapper.parseIndex(mapOf("index" to " -1 ")))
        assertNull(PlayRequestMapper.parseIndex(mapOf("Index" to "abc")))
        assertNull(PlayRequestMapper.parseIndex(emptyMap()))

        assertEquals(50, PlayRequestMapper.parseVolume(mapOf("Volume" to "50")))
        assertEquals(100, PlayRequestMapper.parseVolume(mapOf("volume" to "150")))
        assertEquals(0, PlayRequestMapper.parseVolume(mapOf("Volume" to "-5")))
        assertEquals(33, PlayRequestMapper.parseVolume(mapOf("Volume" to "33.3")))
        assertNull(PlayRequestMapper.parseVolume(mapOf("Volume" to "")))

        val itemId = UUID.randomUUID()
        assertEquals(itemId, PlayRequestMapper.parseItemId(mapOf("ItemId" to itemId.toString().replace("-", ""))))
        assertNull(PlayRequestMapper.parseItemId(mapOf("ItemId" to "nope")))
    }

    @Test
    fun `No requested streams means no override`() {
        assertNull(PlayRequestMapper.requestedStreams(Destination.Playback(UUID.randomUUID(), 0L), 1))
    }

    @Test
    fun `Requested streams map Jellyfin indices to track indices`() {
        val itemId = UUID.randomUUID()
        val sourceId = UUID.randomUUID()
        val destination =
            Destination.Playback(
                itemId = itemId,
                positionMs = 0L,
                mediaSourceId = sourceId.toString().replace("-", ""),
                audioStreamIndex = null,
                subtitleStreamIndex = -1,
            )
        val itemPlayback = PlayRequestMapper.requestedStreams(destination, 7)!!
        assertEquals(7, itemPlayback.userId)
        assertEquals(itemId, itemPlayback.itemId)
        assertEquals(sourceId, itemPlayback.sourceId)
        assertEquals(TrackIndex.UNSPECIFIED, itemPlayback.audioIndex)
        assertEquals(TrackIndex.DISABLED, itemPlayback.subtitleIndex)

        val withTracks =
            PlayRequestMapper.requestedStreams(
                Destination.Playback(itemId, 0L, audioStreamIndex = 2, subtitleStreamIndex = 3),
                7,
            )!!
        assertNull(withTracks.sourceId)
        assertEquals(2, withTracks.audioIndex)
        assertEquals(3, withTracks.subtitleIndex)
    }
}
