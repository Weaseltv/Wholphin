package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Converts play requests from outside the app (a remote "Play On" request from the server or a launch intent)
 * into a [Destination.Playback]
 */
object PlayRequestMapper {
    /**
     * Convert a server [PlayRequest] (the payload of a `Play` web socket message)
     *
     * @return the destination or null if the request has no items
     */
    fun toDestination(request: PlayRequest): Destination.Playback? =
        toDestination(
            itemIds = request.itemIds.orEmpty(),
            startIndex = request.startIndex,
            positionMs = request.startPositionTicks?.ticks?.inWholeMilliseconds ?: 0L,
            shuffle = request.playCommand == PlayCommand.PLAY_SHUFFLE,
            mediaSourceId = request.mediaSourceId,
            audioStreamIndex = request.audioStreamIndex,
            subtitleStreamIndex = request.subtitleStreamIndex,
        )

    /**
     * Build a destination from raw play parameters
     *
     * A single item plays with the usual contextual queue (eg the rest of the season). Multiple items are played
     * verbatim, in order, starting at [startIndex].
     *
     * @return the destination or null if [itemIds] is empty
     */
    fun toDestination(
        itemIds: List<UUID>,
        startIndex: Int?,
        positionMs: Long,
        shuffle: Boolean,
        mediaSourceId: String?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
    ): Destination.Playback? {
        if (itemIds.isEmpty()) {
            return null
        }
        val index = (startIndex ?: 0).coerceIn(0, itemIds.lastIndex)
        val queue = if (itemIds.size > 1) itemIds else emptyList()
        return Destination.Playback(
            itemId = itemIds[index],
            positionMs = positionMs.coerceAtLeast(0L),
            shuffle = shuffle,
            mediaSourceId = mediaSourceId?.takeIf { it.isNotBlank() },
            audioStreamIndex = audioStreamIndex?.takeIf { it >= 0 },
            subtitleStreamIndex = subtitleStreamIndex,
            itemIds = queue,
            startIndex = if (queue.isEmpty()) 0 else index,
        )
    }

    /**
     * Convert a server [PlayRequest] into the string extras of a playback launch intent that [IntentService] parses
     * back into the same [Destination.Playback] as [toDestination]
     *
     * @return the extras or null if the request has no items
     */
    fun toIntentParams(request: PlayRequest): Map<String, String>? {
        val itemIds = request.itemIds.orEmpty()
        if (itemIds.isEmpty()) {
            return null
        }
        return buildMap {
            put(IntentService.INTENT_ITEM_IDS, itemIds.joinToString(",") { it.toString() })
            request.startIndex?.let { put(IntentService.INTENT_START_INDEX, it.toString()) }
            request.startPositionTicks?.let {
                put(
                    IntentService.INTENT_POSITION,
                    it.ticks.inWholeMilliseconds
                        .coerceAtLeast(0L)
                        .toString(),
                )
            }
            request.mediaSourceId?.takeIf { it.isNotBlank() }?.let { put(IntentService.INTENT_MEDIA_SOURCE_ID, it) }
            request.audioStreamIndex?.let { put(IntentService.INTENT_AUDIO_STREAM_INDEX, it.toString()) }
            request.subtitleStreamIndex?.let { put(IntentService.INTENT_SUBTITLE_STREAM_INDEX, it.toString()) }
            if (request.playCommand == PlayCommand.PLAY_SHUFFLE) {
                put(IntentService.INTENT_SHUFFLE, "true")
            }
        }
    }

    /**
     * Parse the `Index` argument of a `SetAudioStreamIndex` or `SetSubtitleStreamIndex` command
     */
    fun parseIndex(arguments: Map<String, String?>): Int? = argument(arguments, "Index")?.toIntOrNull()

    /**
     * Parse the `Volume` argument (0-100) of a `SetVolume` command
     */
    fun parseVolume(arguments: Map<String, String?>): Int? =
        argument(arguments, "Volume")
            ?.toDoubleOrNull()
            ?.roundToInt()
            ?.coerceIn(0, 100)

    /**
     * Parse the `ItemId` argument of a `DisplayContent` command
     */
    fun parseItemId(arguments: Map<String, String?>): UUID? = argument(arguments, "ItemId")?.toUUIDOrNull()

    /**
     * Get an argument by name, ignoring case since clients are not consistent about it
     */
    private fun argument(
        arguments: Map<String, String?>,
        name: String,
    ): String? =
        (arguments[name] ?: arguments.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The media source & tracks explicitly requested by a [Destination.Playback], as an [ItemPlayback] that takes
     * precedence over the saved choices for the item
     *
     * Jellyfin uses a subtitle index of -1 to mean "no subtitles"; that maps to [TrackIndex.DISABLED].
     *
     * @return the requested choices or null if the destination does not request any
     */
    fun requestedStreams(
        destination: Destination.Playback,
        userRowId: Int,
    ): ItemPlayback? {
        if (destination.mediaSourceId == null &&
            destination.audioStreamIndex == null &&
            destination.subtitleStreamIndex == null
        ) {
            return null
        }
        val subtitleIndex =
            when (val index = destination.subtitleStreamIndex) {
                null -> TrackIndex.UNSPECIFIED
                -1 -> TrackIndex.DISABLED
                else -> if (index >= 0) index else TrackIndex.UNSPECIFIED
            }
        return ItemPlayback(
            userId = userRowId,
            itemId = destination.itemId,
            sourceId = destination.mediaSourceId?.toUUIDOrNull(),
            audioIndex = destination.audioStreamIndex?.takeIf { it >= 0 } ?: TrackIndex.UNSPECIFIED,
            subtitleIndex = subtitleIndex,
        )
    }
}
