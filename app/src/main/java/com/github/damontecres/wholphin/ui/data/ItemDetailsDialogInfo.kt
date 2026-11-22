package com.github.damontecres.wholphin.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.bleedHorizontal
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.letNotEmpty
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.extensions.ticks

/**
 * Dialog showing metadata about an item
 */
@Composable
fun ItemDetailsDialog(
    info: ItemDetailsDialogInfo,
    showFilePath: Boolean,
    onDismissRequest: () -> Unit,
) {
    val resources = LocalResources.current
    // Extract stringResource calls outside of ScrollableDialog's non-composable lambda
    val pathLabel = stringResource(R.string.path)
    val fileSizeLabel = stringResource(R.string.file_size)
    val videoLabel = stringResource(R.string.video)
    val audioLabel = stringResource(R.string.audio)
    val subtitleLabel = stringResource(R.string.subtitle)
    val bitrateLabel = stringResource(R.string.bitrate)
    val unknown = stringResource(R.string.unknown)
    val runtimeLabel = stringResource(R.string.runtime_sort)
    val lastPlayedLabel = stringResource(R.string.last_played)

    ScrollableDialog(
        onDismissRequest = onDismissRequest,
        width = 680.dp,
        maxHeight = 440.dp,
        itemSpacing = 8.dp,
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (info.studios.isNotEmpty()) {
                    Text(
                        text = info.studios.joinToString(", "),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (info.genres.isNotEmpty()) {
                    Text(
                        text = info.genres.joinToString(", "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (info.overview.isNotNullOrBlank()) {
                    Text(
                        text = info.overview,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val lastPlayed =
                    remember(info.lastPlayed) { info.lastPlayed?.let { formatDate(it) } }
                if (lastPlayed != null) {
                    Text(
                        text = "$lastPlayedLabel: $lastPlayed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Show detailed media information for the selected source (first one if multiple)
        info.files.forEachIndexed { index, source ->
            source.mediaStreams?.letNotEmpty { mediaStreams ->
                item {
                    Spacer(Modifier.height(8.dp))
                    // Through the dialog's 16dp content padding to the panel edges
                    HorizontalDivider(Modifier.bleedHorizontal(16.dp))
                }

                // General file information
                item {
                    val containerLabel = stringResource(R.string.container)
                    MediaInfoSection(
                        title =
                            titleIndex(
                                stringResource(R.string.general),
                                index,
                                info.files.size,
                            ),
                        items =
                            buildList {
                                source.container?.let { add(containerLabel to it) }
                                if (showFilePath) {
                                    source.path?.let { add(pathLabel to it) }
                                    add("ID" to (source.id ?: unknown))
                                }
                                source.size?.let {
                                    add(fileSizeLabel to formatBytes(it))
                                }
                                source.bitrate?.let {
                                    add(
                                        bitrateLabel to formatBitrate(it),
                                    )
                                }
                                source.runTimeTicks?.let {
                                    add(runtimeLabel to resources.formatDuration(it.ticks))
                                }
                            },
                    )
                }

                // Video streams
                val videoStreams = mediaStreams.filter { it.type == MediaStreamType.VIDEO }
                itemsIndexed(videoStreams) { index, stream ->
                    MediaInfoSection(
                        title = titleIndex(videoLabel, index, videoStreams.size),
                        items = remember { buildVideoStreamInfo(resources, stream) },
                        additional = remember { buildVideoStreamInfoAdditional(resources, stream) },
                    )
                }

                // Audio streams - display multiple per row
                val audioStreams = mediaStreams.filter { it.type == MediaStreamType.AUDIO }
                itemsIndexed(audioStreams.chunked(3)) { groupIndex, streamGroup ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        streamGroup.forEachIndexed { index, stream ->
                            MediaInfoSection(
                                title =
                                    titleIndex(
                                        audioLabel,
                                        groupIndex * 3 + index,
                                        audioStreams.size,
                                    ),
                                items = buildAudioStreamInfo(resources, stream),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Fill remaining space if less than 3 items
                        repeat(3 - streamGroup.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Subtitle streams - display multiple per row
                val subtitleStreams = mediaStreams.filter { it.type == MediaStreamType.SUBTITLE }
                itemsIndexed(subtitleStreams.chunked(3)) { groupIndex, streamGroup ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        streamGroup.forEachIndexed { index, stream ->
                            MediaInfoSection(
                                title =
                                    titleIndex(
                                        subtitleLabel,
                                        groupIndex * 3 + index,
                                        subtitleStreams.size,
                                    ),
                                items = buildSubtitleStreamInfo(resources, stream, showFilePath),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Fill remaining space if less than 3 items
                        repeat(3 - streamGroup.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (index != info.files.lastIndex) {
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaInfoSection(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    additional: List<Pair<String, String>> = listOf(),
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                items.forEach { (label, value) ->
                    Row(
                        modifier = Modifier,
                    ) {
                        Text(
                            text = "$label: ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (additional.isNotEmpty()) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    additional.forEach { (label, value) ->
                        Row(
                            modifier = Modifier,
                        ) {
                            Text(
                                text = "$label: ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
