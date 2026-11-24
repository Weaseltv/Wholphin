package com.github.damontecres.wholphin.leanback.widget.dialog

import android.content.res.Resources
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.ui.components.resourceFor
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.playback.SimpleMediaStream
import com.github.damontecres.wholphin.ui.roundMinutes
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/*
 * The version and track choosers of Dialogs.kt (`chooseVersionParams`, `chooseStream`) as [MenuParams] for a
 * [MenuDialog]: same rows, order, selected dots and callbacks.
 */

/**
 * "Choose version": one row per source with an id: name (or path, or id), the video title and bitrate below, the
 * rounded runtime at the end, a dot on [chosenSourceId]. [onClick] gets the row index.
 */
fun chooseVersionParams(
    resources: Resources,
    sources: List<MediaSourceInfo>,
    chosenSourceId: UUID?,
    onClick: (Int) -> Unit,
): MenuParams =
    MenuParams(
        fromLongClick = false,
        title = resources.getString(R.string.choose_stream, resources.getString(R.string.version)),
        items =
            sources.filter { it.id.isNotNullOrBlank() }.mapIndexed { index, source ->
                val uuid = source.id?.toUUIDOrNull()
                val videoStream = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
                MenuItem(
                    headline = source.name ?: source.path ?: source.id ?: "",
                    leading = ListLeading.SelectedDot(uuid != null && uuid == chosenSourceId),
                    supporting =
                        buildList {
                            videoStream?.displayTitle?.let(::add)
                            source.bitrate?.let(::formatBitrate)?.let(::add)
                        }.joinToString(", "),
                    trailing =
                        source.runTimeTicks
                            ?.ticks
                            ?.roundMinutes
                            .toString(),
                    onClick = { onClick.invoke(index) },
                )
            },
    )

/**
 * "Choose audio / subtitles": for subtitles first None and Only forced (then a divider when there are streams), then
 * the streams of [type] (subtitles in the preferred language first), the current one selected with a dot. [onClick]
 * gets the stream index ([TrackIndex.DISABLED] / [TrackIndex.ONLY_FORCED] for the first two).
 */
fun chooseStreamParams(
    resources: Resources,
    streams: List<MediaStream>,
    currentIndex: Int?,
    type: MediaStreamType,
    preferredSubtitleLanguage: String?,
    onClick: (Int) -> Unit,
): MenuParams {
    val filteredStreams = streams.filter { it.type == type }
    return MenuParams(
        fromLongClick = false,
        title = resources.getString(R.string.choose_stream, resources.getString(resourceFor(type))),
        items =
            buildList {
                if (type == MediaStreamType.SUBTITLE) {
                    add(
                        MenuItem(
                            headline = resources.getString(R.string.none),
                            selected = currentIndex == null,
                            leading = ListLeading.SelectedDot(currentIndex == null),
                            reserveSupportingLine = true,
                            onClick = { onClick.invoke(TrackIndex.DISABLED) },
                        ),
                    )
                    add(
                        MenuItem(
                            headline = resources.getString(R.string.only_forced_subtitles),
                            leading = ListLeading.SelectedDot(currentIndex == TrackIndex.ONLY_FORCED),
                            reserveSupportingLine = true,
                            onClick = { onClick.invoke(TrackIndex.ONLY_FORCED) },
                        ),
                    )
                    if (filteredStreams.isNotEmpty()) add(MenuDivider)
                }
                addAll(
                    filteredStreams
                        .let { list ->
                            if (type == MediaStreamType.SUBTITLE && preferredSubtitleLanguage.isNotNullOrBlank()) {
                                list.sortedByDescending { it.language != null && it.language == preferredSubtitleLanguage }
                            } else {
                                list
                            }
                        }.map { stream ->
                            val simpleStream = SimpleMediaStream.from(resources, stream, true)
                            MenuItem(
                                headline = simpleStream.streamTitle ?: simpleStream.displayTitle,
                                selected = currentIndex == stream.index,
                                leading = ListLeading.SelectedDot(currentIndex == stream.index),
                                supporting = if (simpleStream.streamTitle != null) simpleStream.displayTitle else null,
                                reserveSupportingLine = true,
                                onClick = { onClick.invoke(stream.index) },
                            )
                        },
                )
            },
    )
}
