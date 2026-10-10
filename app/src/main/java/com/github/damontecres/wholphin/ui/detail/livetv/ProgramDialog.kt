package com.github.damontecres.wholphin.ui.detail.livetv

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.FocusSafeLazyRow
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.components.keepFocusedItemVisible
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.seasonEpisode
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun ProgramDialog(
    state: DataLoadingState<BaseItem>,
    canRecord: Boolean,
    onDismissRequest: () -> Unit,
    onWatch: (BaseItem) -> Unit,
    onRecord: (BaseItem, series: Boolean) -> Unit,
    onCancelRecord: (BaseItem, series: Boolean) -> Unit,
) {
    val context = LocalContext.current
    BasicDialog(
        onDismissRequest = onDismissRequest,
    ) {
        val focusRequester = remember { FocusRequester() }
        Box(
            modifier =
                Modifier
                    .width(600.dp)
                    .heightIn(max = 440.dp)
                    .focusRequester(focusRequester),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            ) {
                when (val st = state) {
                    is DataLoadingState.Error -> {
                        ErrorMessage(st)
                    }

                    DataLoadingState.Loading,
                    DataLoadingState.Pending,
                    -> {
                        CircularProgress(
                            Modifier
                                .padding(8.dp)
                                .size(48.dp),
                        )
                    }

                    is DataLoadingState.Success<BaseItem> -> {
                        val item = st.data
                        val now = LocalDateTime.now()
                        val dto = item.data
                        val isRecording = dto.timerId.isNotNullOrBlank()
                        val isSeriesRecording = dto.seriesTimerId.isNotNullOrBlank()
                        LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                        Text(
                            text = item.name ?: "",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = if (isWeaselTv()) NeonType.dialogTitle() else MaterialTheme.typography.titleLarge,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (dto.isSeries ?: false) {
                            listOfNotNull(dto.seasonEpisode, dto.episodeTitle)
                                .joinToString(" - ")
                                .ifBlank { null }
                                ?.let {
                                    Text(
                                        text = it,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                        }
                        val start = dto.startDate
                        val end = dto.endDate
                        val time =
                            if (start != null && end != null) {
                                DateUtils.formatDateRange(
                                    context,
                                    start
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .epochSecond * 1000,
                                    end
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .epochSecond * 1000,
                                    DateUtils.FORMAT_SHOW_TIME,
                                )
                            } else {
                                ""
                            }
                        if (time.isNotBlank()) {
                            Text(
                                text = time,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        dto.overview?.let { overview ->
                            Text(
                                text = overview,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 3,
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth(),
                        ) {
                            if (start != null && end != null && now.isAfter(start) && now.isBefore(end)) {
                                TextButton(
                                    onClick = { onWatch.invoke(item) },
                                    modifier = Modifier.keepFocusedItemVisible(),
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                        )
                                        Text(
                                            text = stringResource(R.string.watch_live),
                                        )
                                    }
                                }
                            }
                            if (canRecord) {
                                val recordFocusRequester = remember { FocusRequester() }
                                FocusSafeLazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(
                                            16.dp,
                                            Alignment.CenterHorizontally,
                                        ),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .focusRestorer(recordFocusRequester),
                                ) {
                                    if (dto.isSeries ?: false) {
                                        item {
                                            TextButton(
                                                onClick = {
                                                    if (isSeriesRecording) {
                                                        onCancelRecord.invoke(item, true)
                                                    } else {
                                                        onRecord.invoke(item, true)
                                                    }
                                                },
                                                modifier =
                                                    Modifier.keepFocusedItemVisible().focusRequester(recordFocusRequester),
                                            ) {
                                                Row(
                                                    horizontalArrangement =
                                                        Arrangement.spacedBy(
                                                            4.dp,
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    if (isSeriesRecording) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = null,
                                                            tint = Color.Red,
                                                        )
                                                    }
                                                    Text(
                                                        text =
                                                            if (isSeriesRecording) {
                                                                stringResource(
                                                                    R.string.cancel_series_recording,
                                                                )
                                                            } else {
                                                                stringResource(R.string.record_series)
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (dto.endDate?.isAfter(LocalDateTime.now()) ?: true) {
                                        // Only show program specific recording button if it hasn't finished yet
                                        item {
                                            TextButton(
                                                onClick = {
                                                    if (isRecording) {
                                                        onCancelRecord.invoke(item, false)
                                                    } else {
                                                        onRecord.invoke(item, false)
                                                    }
                                                },
                                                modifier =
                                                    Modifier.keepFocusedItemVisible().ifElse(
                                                        !(dto.isSeries ?: false),
                                                        Modifier.focusRequester(
                                                            recordFocusRequester,
                                                        ),
                                                    ),
                                            ) {
                                                Row(
                                                    horizontalArrangement =
                                                        Arrangement.spacedBy(
                                                            4.dp,
                                                        ),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    if (isRecording) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = null,
                                                            tint = Color.Red,
                                                        )
                                                    }
                                                    Text(
                                                        text =
                                                            if (isRecording) {
                                                                stringResource(
                                                                    R.string.cancel_recording,
                                                                )
                                                            } else {
                                                                stringResource(
                                                                    R.string.record_program,
                                                                )
                                                            },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
