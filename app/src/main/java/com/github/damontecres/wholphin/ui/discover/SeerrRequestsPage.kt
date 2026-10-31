package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.services.*
import com.github.damontecres.wholphin.ui.components.keepTitledRowVisible
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SeerrRequestsViewModel @Inject constructor(
    val requests: RequestStatusRepository,
    val navigation: NavigationManager,
) : ViewModel() {
    var actionError by mutableStateOf<String?>(null)
        private set
    fun cancel(id: Int) { viewModelScope.launch { try { requests.cancel(id) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (_: Exception) { actionError = "Couldn't cancel this request. Please refresh and try again." } } }
    fun open(item: RequestStatusItem) {
        val id = item.jellyfinId
        if (item.stage.ready && id != null) navigation.navigateTo(Destination.MediaItem(id, if (item.request.type == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE))
        else {
            val metadata = item.item ?: item.request.media?.tmdbId?.let { tmdb ->
                com.github.damontecres.wholphin.data.model.DiscoverItem(
                    id = tmdb, type = com.github.damontecres.wholphin.data.model.SeerrItemType.fromString(item.request.type),
                    title = item.title, subtitle = null, overview = null,
                    availability = com.github.damontecres.wholphin.data.model.SeerrAvailability.UNKNOWN,
                    releaseDate = null, posterUrl = null, backDropUrl = null, logoUrl = null, jellyfinItemId = null,
                )
            }
            if (metadata != null) navigation.navigateTo(Destination.DiscoveredItem(metadata))
            else actionError = "Title details are unavailable. Please refresh and try again."
        }
    }
}

private fun RequestStatusItem.section(): Int = when {
    stage.ready -> 0
    stage.kind == RequestStageKind.FAILED -> 2
    else -> 1
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SeerrRequestsPage(
    focusRequesterOnEmpty: FocusRequester?,
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequestId: Int? = null,
    viewModel: SeerrRequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.requests.state.collectAsState()
    var clock by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); clock = Instant.now() } }
    DisposableEffect(Unit) { viewModel.requests.setVisible(true); onDispose { viewModel.requests.setVisible(false) } }
    val cutoff = clock.minus(Duration.ofDays(30))
    val items = state.items.filter { row ->
        row.request.id == focusRequestId || !row.stage.terminal || runCatching { Instant.parse(row.request.updatedAt).isAfter(cutoff) }.getOrDefault(true)
    }.sortedBy { it.section() }
    val list = rememberLazyListState()
    val requesters = remember(state.scope, items.map { it.request.id }) { items.associate { it.request.id to FocusRequester() } }
    var focusHandled by androidx.compose.runtime.saveable.rememberSaveable(state.scope, focusRequestId) { mutableStateOf(false) }
    val refreshFocus = remember { FocusRequester() }
    LaunchedEffect(items.map { it.request.id }, focusRequestId, state.scope) {
        if (!focusHandled && items.isNotEmpty()) {
            val index = items.indexOfFirst { it.request.id == focusRequestId }.takeIf { it >= 0 } ?: if (focusRequestId == null) 0 else return@LaunchedEffect
            list.scrollToItem(if (index == 0) 0 else index + 1)
            withFrameNanos { }
            focusHandled = requesters[items[index].request.id]?.tryRequestFocus() == true
        } else if (items.isEmpty() && !state.loading) { if (focusRequesterOnEmpty?.tryRequestFocus() != true) refreshFocus.tryRequestFocus() }
    }
    Column(modifier.padding(horizontal = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel.requests::refresh, modifier = Modifier.focusRequester(refreshFocus)) { Text(if (state.loading) "Refreshing…" else "Refresh") }
            Text(state.lastUpdated?.let { "Updated ${Duration.between(it, clock).toMinutes().coerceAtLeast(0)} min ago" } ?: if (state.loading) "Loading your requests…" else "Your requests will appear here", color = MaterialTheme.colorScheme.onSurface)
        }
        (viewModel.actionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (!state.connected) Text("Requests are reconnecting. Your last update is kept here.", modifier = Modifier.padding(12.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides KeepVisibleBringIntoViewSpec) {
        LazyColumn(state = list, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
            item("top") { if (items.isEmpty() && !state.loading) Text(
                if (state.lastUpdated != null && state.syncComplete && state.error == null && state.connected) {
                    if (state.items.isEmpty()) "You haven't requested anything yet. Find a title in Search to get started." else "No recent requests. Older completed requests are hidden."
                } else if (state.lastUpdated == null && state.error == null) "Loading your requests…" else "Your requests couldn't be loaded right now."
            ) }
            itemsIndexed(items, key = { _, row -> row.request.id }) { index, row ->
                LaunchedEffect(row.request.id, state.lastUpdated) { viewModel.requests.loadMetadata(row.request.id) }
                // Reveal the section heading and full card, rather than centering only its action button.
                Column(modifier = Modifier.keepTitledRowVisible(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index == 0 || items[index - 1].section() != row.section()) {
                        Text(listOf("Ready", "In progress", "Needs attention")[row.section()], style = MaterialTheme.typography.headlineSmall)
                    }
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        AsyncImage(model = row.item?.posterUrl, contentDescription = null, modifier = Modifier.size(90.dp, 135.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title + (row.item?.releaseDate?.year?.let { " ($it)" } ?: "") + if (row.request.is4k == true) " · 4K" else "", style = MaterialTheme.typography.titleLarge)
                            Text(row.stage.label + if (!row.fresh) " · Last known status" else "", color = if (row.stage.ready) Color(0xFF00F2D6) else MaterialTheme.colorScheme.onSurface)
                            row.stage.detail?.let { Text(it) }
                            row.stage.progress?.let { androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.width(350.dp), color = Color(0xFF00F2D6)) }
                            if (row.stage.kind == RequestStageKind.ADDING) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.width(350.dp))
                            row.stage.eta?.takeIf { it.isAfter(clock) }?.let { Text("Estimated ${Duration.between(clock, it).toMinutes().coerceAtLeast(1)} min remaining") }
                            Text("Requested ${row.request.createdAt?.take(10) ?: "recently"}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { positionCallback(1, index); viewModel.open(row) }, modifier = Modifier.focusRequester(requesters.getValue(row.request.id))) {
                                    Text(if (row.stage.ready && row.jellyfinId != null) "Watch now" else "Details")
                                }
                                if (viewModel.requests.canCancel(row.request)) Button(onClick = { viewModel.cancel(row.request.id) }) { Text("Cancel request") }
                            }
                            if (row.stage.ready && row.jellyfinId == null) Text("Ready. Find it in your library.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        }
    }
}
