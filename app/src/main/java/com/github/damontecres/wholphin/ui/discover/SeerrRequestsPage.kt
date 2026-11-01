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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.damontecres.wholphin.services.*
import com.github.damontecres.wholphin.ui.components.keepTitledRowVisible
import com.github.damontecres.wholphin.ui.components.rememberRequestFocusTarget
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    fun open(requestId: Int, expectedScope: String?) {
        val state = requests.state.value
        if (state.scope != expectedScope) return
        val item = state.items.firstOrNull { it.request.id == requestId } ?: return
        val progress = requests.progress.state.value.takeIf { state.connected && requests.acceptsProgress(it) }
        if (item.stage.ready || item.partlyWatchable(item.liveProgress(progress))) {
            viewModelScope.launch {
                try {
                    val id = item.jellyfinId ?: requests.libraryItemId(requestId, expectedScope)
                    if (requests.state.value.scope != expectedScope) return@launch
                    if (id != null) navigation.navigateTo(Destination.MediaItem(id, if (item.request.type == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE))
                    else actionError = "Couldn't open this title right now. Please try again."
                } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
                catch (_: Exception) { if (requests.state.value.scope == expectedScope) actionError = "Couldn't open this title right now. Please try again." }
            }
        } else {
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

private fun RequestStatusItem.liveProgress(progress: RequestProgressState?): RequestProgressRecord? =
    progress?.records?.get(request.id)?.takeIf {
        !stage.terminal && request.requestedBy?.id == progress.seerrUserId && it.matches(this)
    }

private fun RequestStatusItem.partlyWatchable(live: RequestProgressRecord?): Boolean =
    request.type == "tv" && (stage.kind == RequestStageKind.PARTIAL || live?.availability == "partial")

private fun RequestStatusItem.section(live: RequestProgressRecord?): Int = when {
    stage.ready || partlyWatchable(live) -> 0
    stage.kind == RequestStageKind.FAILED -> 2
    else -> 1
}

private fun requestPosterKey(scope: String?, url: String, width: Int, height: Int) =
    "request-poster|$scope|${width}x$height|$url"

private fun requestPoster(context: android.content.Context, scope: String?, url: String, width: Int, height: Int): ImageRequest {
    val key = requestPosterKey(scope, url, width, height)
    return ImageRequest.Builder(context).data(url).size(width, height)
        .memoryCacheKey(key).placeholderMemoryCacheKey(key).crossfade(false).build()
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
    val progressState by viewModel.requests.progress.state.collectAsState()
    val progress = progressState.takeIf { state.connected && viewModel.requests.acceptsProgress(it) }
    var clock by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); clock = Instant.now() } }
    DisposableEffect(Unit) { viewModel.requests.setVisible(true); onDispose { viewModel.requests.setVisible(false) } }
    val cutoff = clock.minus(Duration.ofDays(30))
    val items = state.items.filter { row ->
        row.request.id == focusRequestId || !row.stage.terminal || runCatching { Instant.parse(row.request.updatedAt).isAfter(cutoff) }.getOrDefault(true)
    }.sortedBy { it.section(it.liveProgress(progress)) }
    val list = rememberLazyListState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val posterScope = state.scope
    val posterWidth = with(density) { 90.dp.roundToPx() }
    val posterHeight = with(density) { 135.dp.roundToPx() }
    // Keep successful painters outside lazy rows, including when a row leaves the viewport.
    val loadedPosters = remember(posterScope) { mutableStateMapOf<String, Painter>() }
    val visibleIds by remember(list) {
        derivedStateOf { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
    }
    val warmRows = remember(items, visibleIds) {
        val wanted = items.take(2).mapTo(linkedSetOf()) { it.request.id }
        for (id in visibleIds) {
            val index = items.indexOfFirst { it.request.id == id }
            if (index >= 0) for (nearby in (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(items.lastIndex)) {
                wanted.add(items[nearby].request.id)
            }
        }
        items.filter { it.request.id in wanted }.take(8)
    }
    LaunchedEffect(posterScope, warmRows.map { it.request.id to it.item?.posterUrl }, posterWidth, posterHeight) {
        // Warm only the viewport neighbours and leading Ready rows, never all history.
        warmRows.forEach { viewModel.requests.loadMetadata(it.request.id) }
        val limit = Semaphore(2)
        coroutineScope {
            warmRows.mapNotNull { it.item?.posterUrl }.distinct().forEach { url ->
                launch {
                    limit.withPermit {
                        try {
                            context.imageLoader.execute(requestPoster(context, posterScope, url, posterWidth, posterHeight))
                        } catch (ex: kotlinx.coroutines.CancellationException) {
                            throw ex
                        } catch (_: Exception) { /* Prefetch is optional; it cannot take down the page. */ }
                    }
                }
            }
        }
    }
    val requesters = remember(state.scope) { mutableMapOf<Int, FocusRequester>() }
    items.forEach { requesters.getOrPut(it.request.id) { FocusRequester() } }
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
            Button(onClick = viewModel.requests::refreshVisible, modifier = Modifier.rememberRequestFocusTarget(refreshFocus)) { Text(if (state.loading) "Refreshing…" else "Refresh") }
            Text(
                progress?.takeIf { it.mode != ProgressMode.OFF }?.message
                    ?: state.lastUpdated?.let { "Updated ${Duration.between(it, clock).toMinutes().coerceAtLeast(0)} min ago" }
                    ?: if (state.loading) "Loading your requests…" else "Your requests will appear here",
                color = MaterialTheme.colorScheme.onSurface,
            )
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
                // Availability can move a row into Ready; percentage updates never reorder it.
                val live = row.liveProgress(progress)
                val liveStale = live != null && (live.stale || progress?.transportStale == true)
                val partial = row.partlyWatchable(live)
                val watchable = row.stage.ready || partial
                val shownLabel = when {
                    partial -> "Some episodes ready"
                    live != null -> live.label
                    else -> row.stage.label
                }
                val shownDetail = when {
                    live?.stage == "waiting" && live.detail == "no_release_found_yet" ->
                        if (partial) "The rest isn't available to download yet. It'll be added automatically when it is."
                        else "No download is available yet. It'll be added automatically when it is."
                    live?.stage == "waiting" && live.detail == "not_released_yet" ->
                        if (partial) "The rest hasn't been released yet. It'll be added automatically when it is." else null
                    live?.stage == "waiting" && live.detail != "not_released_yet" -> "No download is active yet. Updates appear automatically."
                    live?.stage in setOf("confirming_availability", "ready") -> "Confirming it's ready to watch."
                    live?.detail == "download_failed_retrying" -> "The previous download had a problem. Waiting for its replacement."
                    live != null && partial -> live.label
                    live == null -> row.stage.detail
                    else -> null
                }
                val bar = if (live != null) live.fraction else row.stage.progress
                LaunchedEffect(row.request.id, state.lastUpdated) { viewModel.requests.loadMetadata(row.request.id) }
                // Reveal the section heading and full card, rather than centering only its action button.
                Column(modifier = Modifier.keepTitledRowVisible(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index == 0 || items[index - 1].let { it.section(it.liveProgress(progress)) } != row.section(live)) {
                        Text(listOf("Ready", "In progress", "Needs attention")[row.section(live)], style = MaterialTheme.typography.headlineSmall)
                    }
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        val url = row.item?.posterUrl
                        val posterKey = url?.let { requestPosterKey(posterScope, it, posterWidth, posterHeight) }
                        val poster = remember(context, posterKey) {
                            url?.let { requestPoster(context, posterScope, it, posterWidth, posterHeight) }
                        }
                        val previousPoster = posterKey?.let { loadedPosters[it] }
                        AsyncImage(
                            model = poster, placeholder = previousPoster, error = previousPoster,
                            contentDescription = null, modifier = Modifier.size(90.dp, 135.dp),
                            onSuccess = { success ->
                                if (posterKey != null && loadedPosters[posterKey] !== success.painter) {
                                    if (posterKey !in loadedPosters && loadedPosters.size >= 24) loadedPosters.keys.firstOrNull()?.let { loadedPosters.remove(it) }
                                    loadedPosters[posterKey] = success.painter
                                }
                            },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.title + (row.item?.releaseDate?.year?.let { " ($it)" } ?: "") + if (row.request.is4k == true) " · 4K" else "", style = MaterialTheme.typography.titleLarge)
                            Text(shownLabel + if (!row.fresh || liveStale) " · Last known status" else "", color = if (watchable) Color(0xFF00F2D6) else MaterialTheme.colorScheme.onSurface)
                            shownDetail?.let { Text(it) }
                            bar?.let { androidx.compose.material3.LinearProgressIndicator(progress = { it }, modifier = Modifier.width(350.dp), color = Color(0xFF00F2D6)) }
                            if (live == null && row.stage.kind == RequestStageKind.ADDING) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.width(350.dp))
                            if (live != null) {
                                live.etaSeconds?.takeIf { it > 0 && live.stage == "downloading" && !liveStale }?.let {
                                    val seconds = kotlin.math.ceil(it).toLong()
                                    Text(if (seconds < 60) "Estimated under a minute remaining" else "Estimated ${seconds / 60 + if (seconds % 60 > 0) 1 else 0} min remaining")
                                }
                                live.episodes?.let { episodes ->
                                    val total = episodes.total
                                    val imported = episodes.imported
                                    if (total != null && total > 0 && imported != null) Text("$imported of $total episodes imported")
                                }
                            } else row.stage.eta?.takeIf { it.isAfter(clock) }?.let { Text("Estimated ${Duration.between(clock, it).toMinutes().coerceAtLeast(1)} min remaining") }
                            Text("Requested ${row.request.createdAt?.take(10) ?: "recently"}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { positionCallback(1, index); viewModel.open(row.request.id, state.scope) }, modifier = Modifier.rememberRequestFocusTarget(requesters.getValue(row.request.id))) {
                                    Text(if (watchable) "Watch now" else "Details")
                                }
                                if (viewModel.requests.canCancel(row.request)) Button(onClick = { viewModel.cancel(row.request.id) }, modifier = Modifier.rememberRequestFocusTarget()) { Text("Cancel request") }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
