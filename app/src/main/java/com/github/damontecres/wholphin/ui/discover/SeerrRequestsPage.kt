package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RequestProgressRecord
import com.github.damontecres.wholphin.services.RequestProgressState
import com.github.damontecres.wholphin.services.RequestStageKind
import com.github.damontecres.wholphin.services.RequestStatusItem
import com.github.damontecres.wholphin.services.RequestStatusRepository
import com.github.damontecres.wholphin.ui.components.keepTitledRowVisible
import com.github.damontecres.wholphin.ui.components.rememberRequestFocusTarget
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonFonts
import com.github.damontecres.wholphin.ui.theme.RequestActivityPalette
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil

@HiltViewModel
class SeerrRequestsViewModel
    @Inject
    constructor(
        val requests: RequestStatusRepository,
        val navigation: NavigationManager,
    ) : ViewModel() {
        var actionError by mutableStateOf<String?>(null)
            private set

        fun open(
            requestId: Int,
            expectedScope: String?,
        ) {
            val state = requests.state.value
            if (state.scope != expectedScope) return
            val item = state.items.firstOrNull { it.request.id == requestId } ?: return
            val progress =
                requests.progress.state.value
                    .takeIf { state.connected && requests.acceptsProgress(it) }
            if (item.stage.ready || item.partlyWatchable(item.liveProgress(progress))) {
                viewModelScope.launch {
                    try {
                        val id = item.jellyfinId ?: requests.libraryItemId(requestId, expectedScope)
                        if (requests.state.value.scope != expectedScope) return@launch
                        if (id !=
                            null
                        ) {
                            navigation.navigateTo(
                                Destination.MediaItem(
                                    id,
                                    if (item.request.type ==
                                        "tv"
                                    ) {
                                        BaseItemKind.SERIES
                                    } else {
                                        BaseItemKind.MOVIE
                                    },
                                ),
                            )
                        } else {
                            actionError = "Couldn't open this title right now. Please try again."
                        }
                    } catch (ex: kotlinx.coroutines.CancellationException) {
                        throw ex
                    } catch (_: Exception) {
                        if (requests.state.value.scope ==
                            expectedScope
                        ) {
                            actionError = "Couldn't open this title right now. Please try again."
                        }
                    }
                }
            } else {
                val metadata =
                    item.item ?: item.request.media?.tmdbId?.let { tmdb ->
                        com.github.damontecres.wholphin.data.model.DiscoverItem(
                            id = tmdb,
                            type =
                                com.github.damontecres.wholphin.data.model.SeerrItemType
                                    .fromString(item.request.type),
                            title = item.title,
                            subtitle = null,
                            overview = null,
                            availability = com.github.damontecres.wholphin.data.model.SeerrAvailability.UNKNOWN,
                            releaseDate = null,
                            posterUrl = null,
                            backDropUrl = null,
                            logoUrl = null,
                            jellyfinItemId = null,
                        )
                    }
                if (metadata != null) {
                    navigation.navigateTo(Destination.DiscoveredItem(metadata))
                } else {
                    actionError = "Title details are unavailable. Please try again."
                }
            }
        }
    }

private fun RequestStatusItem.liveProgress(progress: RequestProgressState?): RequestProgressRecord? =
    progress?.records?.get(request.id)?.takeIf {
        !stage.terminal && request.requestedBy?.id == progress.seerrUserId && it.matches(this)
    }

private fun RequestStatusItem.partlyWatchable(live: RequestProgressRecord?): Boolean =
    request.type == "tv" && (stage.kind == RequestStageKind.PARTIAL || live?.availability == "partial")

private fun requestPosterKey(
    scope: String?,
    url: String,
    width: Int,
    height: Int,
) = "request-poster|$scope|${width}x$height|$url"

private fun requestPoster(
    context: android.content.Context,
    scope: String?,
    url: String,
    width: Int,
    height: Int,
): ImageRequest {
    val key = requestPosterKey(scope, url, width, height)
    return ImageRequest
        .Builder(context)
        .data(url)
        .size(width, height)
        .memoryCacheKey(key)
        .placeholderMemoryCacheKey(key)
        .crossfade(false)
        .build()
}

/** The three ACTIVITY sections, in page order. */
private enum class ActivitySection(
    val title: String,
    val color: Color,
) {
    ACTIVE("Active", RequestActivityPalette.Active),
    WAITING("Waiting", RequestActivityPalette.Waiting),
    READY("Ready to watch", RequestActivityPalette.Ready),
}

private sealed interface ActivityProgress {
    /** Bar plus percentage, in the title's type color. */
    data class Bar(
        val fraction: Float,
    ) : ActivityProgress

    /** Plain text, such as "6 / 6 imported" or "Requested Nov 3". */
    data class Note(
        val text: String,
        val emphasized: Boolean,
    ) : ActivityProgress

    /** Nothing to report yet: a centered dash. */
    data object None : ActivityProgress
}

/** Everything one table row shows, derived from the request and its live progress. */
private data class ActivityRow(
    val row: RequestStatusItem,
    val section: ActivitySection,
    val status: String,
    val detail: String?,
    val progress: ActivityProgress,
    val watchable: Boolean,
)

private fun etaText(seconds: Long): String =
    if (seconds < 60) "Under a minute remaining" else "${seconds / 60 + if (seconds % 60 > 0) 1 else 0} min remaining"

private fun shortDate(
    date: LocalDate,
    today: LocalDate,
): String = date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy", Locale.getDefault()))

private fun requestedAt(row: RequestStatusItem): Instant? = row.request.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * A show part-way through its season with nothing missing: every aired episode is imported and the
 * rest haven't aired yet. Everything here comes from the progress service, which reads Sonarr:
 * "nothing missing" is waiting / not released yet with all reported episodes imported, and the next
 * episode, its air time and the season counts come from its `schedule`. Without a schedule (or a
 * next air time) the row keeps "Some episodes ready".
 */
private fun airingRow(
    row: RequestStatusItem,
    live: RequestProgressRecord?,
    episodes: com.github.damontecres.wholphin.services.ProgressEpisodes?,
    clock: Instant,
    formatTime: (Instant) -> String,
): Triple<String, String, ActivityProgress>? {
    val schedule = live?.schedule ?: return null
    if (live.availability != "partial" || live.stage != "waiting" || live.detail != "not_released_yet") return null
    val imported = episodes?.imported ?: return null
    val total = episodes.total ?: return null
    if (imported < total) return null
    val nextAt = schedule.nextAiring?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    // Once that time has passed the episode has aired; until it is imported it is missing, not "airing".
    if (nextAt.isBefore(clock)) return null
    val requested =
        row.request.seasons
            .orEmpty()
            .mapNotNull { it.seasonNumber }
    val season =
        schedule.next?.season
            ?: schedule.seasons.firstOrNull { s -> s.nextAiring?.let { runCatching { Instant.parse(it) }.getOrNull() } == nextAt }?.n
            ?: return null
    if (season !in requested) return null
    val counts = schedule.seasons.firstOrNull { it.n == season }
    val zone = ZoneId.systemDefault()
    val local = nextAt.atZone(zone)
    val sameYear = local.year == clock.atZone(zone).year
    val day = local.format(DateTimeFormatter.ofPattern(if (sameYear) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.getDefault()))
    val date = "$day · ${formatTime(nextAt)}"
    val progress =
        if (counts != null && counts.total > 0 && counts.aired in 0..counts.total) {
            ActivityProgress.Note("${counts.aired} of ${counts.total} episodes out", emphasized = true)
        } else {
            ActivityProgress.Note("$imported / $total imported", emphasized = true)
        }
    return if (counts?.aired == 0) {
        Triple("Season $season coming", "First episode $date", progress)
    } else {
        Triple("Season $season airing", "Next episode $date", progress)
    }
}

private fun activityRow(
    row: RequestStatusItem,
    live: RequestProgressRecord?,
    liveStale: Boolean,
    clock: Instant,
    formatTime: (Instant) -> String,
): ActivityRow {
    val today = clock.atZone(ZoneId.systemDefault()).toLocalDate()
    val stale = !row.fresh || liveStale

    fun withStale(text: String?): String? =
        when {
            !stale -> text
            text == null -> "Last known status"
            else -> "$text · Last known status"
        }
    val isTv = row.request.type == "tv"
    val partial = row.partlyWatchable(live)
    val episodes = live?.episodes?.takeIf { (it.total ?: 0) > 0 && it.imported != null }

    if (row.stage.kind == RequestStageKind.READY) {
        val requested =
            requestedAt(row)?.let { "Requested ${shortDate(it.atZone(ZoneId.systemDefault()).toLocalDate(), today)}" }
                ?: "Requested recently"
        return ActivityRow(
            row,
            ActivitySection.READY,
            "Ready to watch",
            withStale(null),
            ActivityProgress.Note(requested, emphasized = false),
            watchable = true,
        )
    }

    if (partial) {
        airingRow(row, live, episodes, clock, formatTime)?.let { (status, detail, progress) ->
            return ActivityRow(row, ActivitySection.WAITING, status, withStale(detail), progress, watchable = true)
        }
        val rest =
            when {
                live?.stage == "waiting" && live.detail == "not_released_yet" -> "The rest is not out yet. Added automatically."
                live?.stage == "waiting" -> "The rest isn't available yet. Added automatically."
                live?.stage == "downloading" -> "The rest is downloading."
                else -> "The rest will be added automatically."
            }
        val progress =
            episodes?.let { ActivityProgress.Note("${it.imported} / ${it.total} imported", emphasized = true) } ?: ActivityProgress.None
        return ActivityRow(row, ActivitySection.WAITING, "Some episodes ready", withStale(rest), progress, watchable = true)
    }

    if (row.stage.kind == RequestStageKind.FAILED) {
        val why =
            when (row.stage.label) {
                "Request declined" -> "This request wasn't approved."
                "Not available" -> "This title can't be added right now."
                else -> "Something went wrong adding it."
            }
        return ActivityRow(row, ActivitySection.WAITING, row.stage.label, withStale(why), ActivityProgress.None, watchable = false)
    }

    if (live?.stage == "waiting") {
        val (status, why) =
            when (live.detail) {
                "not_released_yet" -> "Waiting for release" to "Not released yet. Starts when it is."
                "no_release_found_yet" -> "Waiting for release" to "No release found yet. Starts when one appears."
                else -> "Waiting for a download" to "No download is active yet. Updates appear automatically."
            }
        return ActivityRow(row, ActivitySection.WAITING, status, withStale(why), ActivityProgress.None, watchable = false)
    }

    if (live == null && row.stage.kind == RequestStageKind.UNRELEASED) {
        val why =
            row.availabilityDate?.let { "Coming ${shortDate(it, today)}. Starts when it's released." }
                ?: "Not released yet. Starts when it is."
        return ActivityRow(row, ActivitySection.WAITING, "Waiting for release", withStale(why), ActivityProgress.None, watchable = false)
    }

    // Everything still moving through the pipeline: accepted, searching, queued, downloading, importing…
    val status =
        when {
            live != null -> live.stageLabel
            row.stage.kind == RequestStageKind.DOWNLOADING -> "Downloading"
            else -> row.stage.label
        }
    // TV requests intentionally show no ETA (owner policy); movies show one while downloading.
    val eta =
        when {
            isTv -> {
                null
            }

            live != null -> {
                live.etaSeconds?.takeIf { it > 0 && live.stage == "downloading" && !liveStale }?.let { etaText(ceil(it).toLong()) }
            }

            else -> {
                row.stage.eta
                    ?.takeIf { it.isAfter(clock) }
                    ?.let { etaText(Duration.between(clock, it).seconds) }
            }
        }
    val detail =
        when {
            isTv && episodes != null -> "${episodes.imported} of ${episodes.total} episodes imported"
            eta != null -> eta
            live?.stage in setOf("confirming_availability", "ready") -> "Confirming it's ready to watch."
            live?.detail == "download_failed_retrying" -> "The previous download had a problem. Waiting for its replacement."
            live == null -> row.stage.detail
            else -> null
        }
    val fraction = if (live != null) live.fraction else row.stage.progress
    return ActivityRow(
        row,
        ActivitySection.ACTIVE,
        status,
        withStale(detail),
        fraction?.let { ActivityProgress.Bar(it) } ?: ActivityProgress.None,
        watchable = false,
    )
}

// Sizes for the 1080p reference frame (owner mockups 2026-11-03; mockup px / 2). The table
// spans the content width, and its five columns share it in these proportions.
private const val TITLE_WEIGHT = 4.7f
private const val TYPE_WEIGHT = .85f
private const val STATUS_WEIGHT = 1.95f
private const val PROGRESS_WEIGHT = 1.45f
private const val ACTION_WEIGHT = 1.35f
private val PosterWidth = 28.dp
private val PosterHeight = 42.dp
private val PosterGap = 11.dp
private val RowHeight = 49.dp
private val RowGap = 6.dp
private val RowBorder = 1.dp
private val RowVerticalInset = 3.5.dp
private val RowStartInset = 10.dp
private val RowEndInset = 8.dp
private val BarWidth = 75.dp
private val BarHeight = 6.dp
private val FadeHeight = 40.dp

/**
 * Space kept above a focused row: the 6dp row gap plus 2dp of the row above, so its focus border
 * never meets the list's top edge and the row above stays laid out for the next Up press.
 */
private val FocusTopClearance = RowGap + 2.dp

private data class ActivityFonts(
    val body: FontFamily,
    val display: FontFamily,
)

/**
 * Scroll only as far as needed, but keep [topPx] clear above the focused row and keep it above
 * the bottom fade ([bottomPx]).
 */
@OptIn(ExperimentalFoundationApi::class)
private class FocusClearanceBringIntoViewSpec(
    private val topPx: Float,
    private val bottomPx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val viewport = (containerSize - bottomPx).coerceAtLeast(size)
        val top = topPx.coerceAtMost((viewport - size).coerceAtLeast(0f))
        return KeepVisibleBringIntoViewSpec.calculateScrollDistance(offset - top, size + top, viewport)
    }
}

/**
 * Moves focus one row at a time on Up and Down. The default focus search only sees rows that are
 * laid out, so with Up held, a row at the top edge found the search bar before the next row and
 * focus left the queue part-way down. Here the next row is scrolled in first when needed, and Up
 * leaves the queue only from its first row.
 */
private class RowNavigator(
    private val list: LazyListState,
    private val scope: CoroutineScope,
) {
    var ids: List<Int> = emptyList()
    var requesters: Map<Int, FocusRequester> = emptyMap()
    var stepPx = 0f
    private var job: Job? = null
    private var target = -1

    fun onKey(
        index: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val delta =
            when (event.key) {
                Key.DirectionUp -> -1
                Key.DirectionDown -> 1
                else -> return false
            }
        val moving = job?.isActive == true
        val next = (if (moving) target else index) + delta
        // Up from the first row goes on to the search bar; nothing else leaves the queue.
        if (next !in ids.indices) return moving || delta > 0
        job?.cancel()
        target = next
        val requester = requesters[ids[next]] ?: return true
        if (placed(next) && requester.tryRequestFocus()) return true
        job =
            scope.launch {
                repeat(12) {
                    list.scrollBy(delta * stepPx)
                    withFrameNanos { }
                    if (placed(next) && requester.tryRequestFocus()) return@launch
                }
            }
        return true
    }

    private fun placed(index: Int) = list.layoutInfo.visibleItemsInfo.any { it.index == index }
}

/** The ACTIVITY queue: Active, Waiting and Ready to watch, or the empty state. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SeerrRequestsPage(
    modifier: Modifier = Modifier,
    focusRequestId: Int? = null,
    viewModel: SeerrRequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.requests.state.collectAsState()
    val progressState by viewModel.requests.progress.state
        .collectAsState()
    val progress = progressState.takeIf { state.connected && viewModel.requests.acceptsProgress(it) }
    var clock by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            clock = Instant.now()
        }
    }
    DisposableEffect(Unit) {
        viewModel.requests.setVisible(true)
        onDispose { viewModel.requests.setVisible(false) }
    }
    val context = LocalContext.current
    val fonts = remember(context) { NeonFonts.families(context).let { (body, display) -> ActivityFonts(body, display) } }
    // Air times follow the TV's 12/24-hour setting, like the on-screen clock.
    val timeFormat =
        remember(context) {
            android.text.format.DateFormat
                .getTimeFormat(context)
        }
    val formatAiringTime: (Instant) -> String = { timeFormat.format(java.util.Date.from(it)) }

    val rows =
        state.items.map { row ->
            val live = row.liveProgress(progress)
            activityRow(row, live, live != null && (live.stale || progress?.transportStale == true), clock, formatAiringTime)
        }
    // Sections keep the request order; Ready lists the newest request first. A live
    // percentage never moves a row, only a change of section does.
    val ordered =
        ActivitySection.entries.flatMap { section ->
            val inSection = rows.filter { it.section == section }
            if (section == ActivitySection.READY) inSection.sortedByDescending { requestedAt(it.row) ?: Instant.EPOCH } else inSection
        }
    val counts = ordered.groupingBy { it.section }.eachCount()

    Column(modifier) {
        (viewModel.actionError ?: state.error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (!state.connected) {
            Text(
                "Requests are reconnecting. Your last update is kept here.",
                color = RequestActivityPalette.Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        when {
            ordered.isNotEmpty() -> {
                ActivityTable(
                    rows = ordered,
                    counts = counts,
                    scope = state.scope,
                    focusRequestId = focusRequestId,
                    fonts = fonts,
                    loadMetadata = viewModel.requests::loadMetadata,
                    lastUpdated = state.lastUpdated,
                    onOpen = { id -> viewModel.open(id, state.scope) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            state.lastUpdated != null && state.syncComplete && state.error == null && state.connected -> {
                ActivityEmptyState(fonts, Modifier.fillMaxWidth().weight(1f))
            }

            else -> {
                Text(
                    if (state.lastUpdated == null &&
                        state.error == null
                    ) {
                        "Loading your requests…"
                    } else {
                        "Your requests couldn't be loaded right now."
                    },
                    color = RequestActivityPalette.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ActivityTable(
    rows: List<ActivityRow>,
    counts: Map<ActivitySection, Int>,
    scope: String?,
    focusRequestId: Int?,
    fonts: ActivityFonts,
    loadMetadata: (Int) -> Unit,
    lastUpdated: Instant?,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val posterWidth = with(density) { PosterWidth.roundToPx() }
    val posterHeight = with(density) { PosterHeight.roundToPx() }
    val fadePx = with(density) { FadeHeight.toPx() }
    val topClearancePx = with(density) { FocusTopClearance.toPx() }
    // Keep successful painters outside lazy rows, including when a row leaves the viewport.
    val loadedPosters = remember(scope) { mutableStateMapOf<String, Painter>() }
    val visibleIds by remember(list) {
        derivedStateOf { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
    }
    val warmRows =
        remember(rows, visibleIds) {
            val wanted = rows.take(2).mapTo(linkedSetOf()) { it.row.request.id }
            for (id in visibleIds) {
                val index = rows.indexOfFirst { it.row.request.id == id }
                if (index >= 0) {
                    for (nearby in (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(rows.lastIndex)) {
                        wanted.add(rows[nearby].row.request.id)
                    }
                }
            }
            rows.filter { it.row.request.id in wanted }.take(8)
        }
    LaunchedEffect(scope, warmRows.map { it.row.request.id to it.row.item?.posterUrl }, posterWidth, posterHeight) {
        // Warm only the viewport neighbours and leading rows, never all history.
        warmRows.forEach { loadMetadata(it.row.request.id) }
        val limit = Semaphore(2)
        coroutineScope {
            warmRows.mapNotNull { it.row.item?.posterUrl }.distinct().forEach { url ->
                launch {
                    limit.withPermit {
                        try {
                            context.imageLoader.execute(requestPoster(context, scope, url, posterWidth, posterHeight))
                        } catch (ex: kotlinx.coroutines.CancellationException) {
                            throw ex
                        } catch (_: Exception) {
                            // Prefetch is optional; it cannot take down the page.
                        }
                    }
                }
            }
        }
    }
    val requesters = remember(scope) { mutableMapOf<Int, FocusRequester>() }
    rows.forEach { requesters.getOrPut(it.row.request.id) { FocusRequester() } }
    // Arriving from a new request (or a notification) focuses that request's row once.
    var focusHandled by rememberSaveable(scope, focusRequestId) { mutableStateOf(focusRequestId == null) }
    var listFocused by remember { mutableStateOf(false) }
    val order = rows.map { it.row.request.id }
    LaunchedEffect(order, focusRequestId, scope) {
        if (!focusHandled) {
            val index = order.indexOf(focusRequestId)
            if (index >= 0) {
                list.scrollToItem(index)
                withFrameNanos { }
                focusHandled = requesters[order[index]]?.tryRequestFocus() == true
                return@LaunchedEffect
            }
        }
        // Otherwise the queue starts at the top: on opening the page, on coming back to it, and
        // whenever rows change section while focus is elsewhere. (A keyed list would follow its
        // first row down into another section and open part-way through the queue.)
        if (!listFocused) list.scrollToItem(0)
    }
    val tableScope = rememberCoroutineScope()
    val navigator = remember(list, tableScope) { RowNavigator(list, tableScope) }
    val stepPx = with(density) { (RowHeight + RowGap).toPx() }
    SideEffect {
        navigator.ids = order
        navigator.requesters = requesters
        navigator.stepPx = stepPx
    }

    Column(modifier) {
        ColumnHeadings(fonts, Modifier.padding(top = 18.dp, bottom = 4.dp))
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides
                remember(topClearancePx, fadePx) {
                    FocusClearanceBringIntoViewSpec(topClearancePx, fadePx)
                },
        ) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 8.dp),
                // Rows never touch: the focus border sits inside its own row, clear of its neighbors.
                verticalArrangement = Arrangement.spacedBy(RowGap),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onFocusChanged { listFocused = it.hasFocus }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Soft fade where the list continues below the screen edge.
                            if (list.canScrollForward) {
                                val top = size.height - fadePx
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            listOf(Color.Black, Color.Transparent),
                                            startY = top,
                                            endY = size.height,
                                        ),
                                    topLeft = Offset(0f, top),
                                    size = Size(size.width, fadePx),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        },
            ) {
                itemsIndexed(rows, key = { _, row -> row.row.request.id }) { index, entry ->
                    LaunchedEffect(entry.row.request.id, lastUpdated) { loadMetadata(entry.row.request.id) }
                    // A section's first row brings its heading into view with it.
                    Column(Modifier.keepTitledRowVisible().onPreviewKeyEvent { navigator.onKey(index, it) }) {
                        if (index == 0 || rows[index - 1].section != entry.section) {
                            SectionHeading(entry.section, counts[entry.section] ?: 0, fonts, first = index == 0)
                        }
                        val url = entry.row.item?.posterUrl
                        val posterKey = url?.let { requestPosterKey(scope, it, posterWidth, posterHeight) }
                        val poster =
                            remember(context, posterKey) { url?.let { requestPoster(context, scope, it, posterWidth, posterHeight) } }
                        ActivityRowItem(
                            entry = entry,
                            fonts = fonts,
                            poster = poster,
                            previousPoster = posterKey?.let { loadedPosters[it] },
                            onPosterLoaded = { painter ->
                                if (posterKey != null && loadedPosters[posterKey] !== painter) {
                                    if (posterKey !in loadedPosters &&
                                        loadedPosters.size >= 24
                                    ) {
                                        loadedPosters.keys.firstOrNull()?.let { loadedPosters.remove(it) }
                                    }
                                    loadedPosters[posterKey] = painter
                                }
                            },
                            focusRequester = requesters.getValue(entry.row.request.id),
                            onClick = { onOpen(entry.row.request.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnHeadings(
    fonts: ActivityFonts,
    modifier: Modifier = Modifier,
) {
    val style =
        TextStyle(
            fontFamily = fonts.body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = .2.em,
            color = RequestActivityPalette.Muted,
        )
    Row(modifier.fillMaxWidth().padding(start = RowStartInset, end = RowEndInset), verticalAlignment = Alignment.CenterVertically) {
        Text("TITLE", style = style, modifier = Modifier.weight(TITLE_WEIGHT).padding(start = PosterWidth + PosterGap))
        Text("TYPE", style = style, modifier = Modifier.weight(TYPE_WEIGHT))
        Text("STATUS", style = style, modifier = Modifier.weight(STATUS_WEIGHT))
        Text("PROGRESS", style = style, modifier = Modifier.weight(PROGRESS_WEIGHT))
        Spacer(Modifier.weight(ACTION_WEIGHT))
    }
}

@Composable
private fun SectionHeading(
    section: ActivitySection,
    count: Int,
    fonts: ActivityFonts,
    first: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (first) 4.dp else 5.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(section.color))
        Text(
            "${section.title.uppercase()} [$count]",
            color = section.color,
            style = TextStyle(fontFamily = fonts.display, fontSize = 13.sp, letterSpacing = .1.em),
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp, end = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(RequestActivityPalette.Divider))
    }
}

@Composable
private fun ActivityRowItem(
    entry: ActivityRow,
    fonts: ActivityFonts,
    poster: ImageRequest?,
    previousPoster: Painter?,
    onPosterLoaded: (Painter) -> Unit,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val row = entry.row
    val accent = entry.section.color
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val isTv = row.request.type == "tv"
    val typeColor = if (isTv) RequestActivityPalette.Series else RequestActivityPalette.Movie
    val ring = Border(BorderStroke(RowBorder, accent), shape = RectangleShape)
    // The whole row is the one focus stop: OK on it is the row's action (Watch now, or the
    // title's details page), so the D-pad moves straight up and down the table.
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().rememberRequestFocusTarget(focusRequester),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = NeonBoard.Text,
                focusedContainerColor = NeonBoard.chipOn(accent),
                focusedContentColor = NeonBoard.Text,
                pressedContainerColor = NeonBoard.chipOn(accent),
                pressedContentColor = NeonBoard.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = ring, pressedBorder = ring),
        glow = ClickableSurfaceDefaults.glow(),
    ) {
        Row(
            // The poster is centered in a 49dp row; the 1dp focus border is drawn inside these insets.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = RowHeight)
                    .padding(start = RowStartInset, end = RowEndInset, top = RowVerticalInset, bottom = RowVerticalInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(TITLE_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(PosterWidth, PosterHeight).background(NeonBoard.Card2)) {
                    AsyncImage(
                        model = poster,
                        placeholder = previousPoster,
                        error = previousPoster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                        onSuccess = { onPosterLoaded(it.painter) },
                    )
                }
                Column(Modifier.weight(1f).padding(start = PosterGap, end = 8.dp)) {
                    // Always one line: a title too long for the column steps its size down instead
                    // of wrapping or cutting off.
                    BasicText(
                        row.title.uppercase(),
                        style = TextStyle(fontFamily = fonts.display, lineHeight = 14.sp, letterSpacing = .08.em, color = NeonBoard.Text),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = .25.sp),
                    )
                    val year =
                        listOfNotNull(
                            row.item
                                ?.releaseDate
                                ?.year
                                ?.toString(),
                            "4K".takeIf { row.request.is4k == true },
                        ).joinToString(" · ")
                    if (year.isNotEmpty()) {
                        Text(
                            year,
                            style =
                                TextStyle(
                                    fontFamily = fonts.body,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    color = RequestActivityPalette.Muted,
                                ),
                        )
                    }
                }
            }
            Text(
                if (isTv) "Series" else "Movie",
                style = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = typeColor),
                modifier = Modifier.weight(TYPE_WEIGHT),
            )
            Column(Modifier.weight(STATUS_WEIGHT).padding(end = 8.dp)) {
                val statusColor = if (entry.section == ActivitySection.ACTIVE) NeonBoard.Text else accent
                Text(
                    entry.status,
                    style =
                        TextStyle(
                            fontFamily = fonts.body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = statusColor,
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.detail?.let {
                    Text(
                        it,
                        style =
                            TextStyle(
                                fontFamily = fonts.body,
                                fontSize = 10.sp,
                                lineHeight = 12.5.sp,
                                color = RequestActivityPalette.Muted,
                            ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.weight(PROGRESS_WEIGHT), contentAlignment = Alignment.CenterStart) {
                ProgressCell(entry.progress, typeColor, fonts)
            }
            // Watch now stays against the table's right edge.
            Box(Modifier.weight(ACTION_WEIGHT), contentAlignment = Alignment.CenterEnd) {
                if (entry.watchable) {
                    WatchNowPill(
                        filled = entry.section == ActivitySection.READY,
                        focused = focused,
                        accent = accent,
                        fonts = fonts,
                    )
                }
            }
        }
        // The row's divider runs along its bottom edge, under the focus border.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(Dp.Hairline)
                .background(RequestActivityPalette.Divider),
        )
    }
}

@Composable
private fun ProgressCell(
    progress: ActivityProgress,
    typeColor: Color,
    fonts: ActivityFonts,
) {
    when (progress) {
        is ActivityProgress.Bar -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(BarWidth)
                        .height(BarHeight)
                        .clip(RoundedCornerShape(50))
                        .background(RequestActivityPalette.Track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                progress.fraction.coerceIn(0f, 1f),
                            ).fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(typeColor),
                    )
                }
                Text(
                    "${(progress.fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = TextStyle(fontFamily = fonts.body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = typeColor),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        is ActivityProgress.Note -> {
            Text(
                progress.text,
                style =
                    TextStyle(
                        fontFamily = fonts.body,
                        fontWeight = if (progress.emphasized) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = if (progress.emphasized) 12.sp else 11.sp,
                        color = if (progress.emphasized) NeonBoard.Text else RequestActivityPalette.Muted,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ActivityProgress.None -> {
            Box(Modifier.width(BarWidth), contentAlignment = Alignment.Center) {
                Text("—", style = TextStyle(fontFamily = fonts.body, fontSize = 12.sp, color = RequestActivityPalette.Muted))
            }
        }
    }
}

/** Drawn inside the row (not its own focus stop): white filled for Ready, outlined for Waiting. */
@Composable
private fun WatchNowPill(
    filled: Boolean,
    focused: Boolean,
    accent: Color,
    fonts: ActivityFonts,
) {
    val shape = RoundedCornerShape(50)
    val solid = filled || focused
    val content = if (solid) NeonBoard.OnAccent else NeonBoard.Text
    val border =
        when {
            focused -> BorderStroke(2.dp, accent)
            filled -> null
            else -> BorderStroke(1.dp, NeonBoard.Low)
        }
    Row(
        modifier =
            Modifier
                .widthIn(min = 108.dp)
                .height(24.dp)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .clip(shape)
                .background(if (solid) NeonBoard.Text else Color.Transparent)
                .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
        Text(
            "Watch now",
            style =
                TextStyle(
                    fontFamily = fonts.body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = .02.em,
                    color = content,
                ),
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ActivityEmptyState(
    fonts: ActivityFonts,
    modifier: Modifier = Modifier,
) {
    val glow = RequestActivityPalette.EmptyGlow
    Column(modifier.padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .size(95.dp)
                    .drawBehind {
                        // Soft teal glow around the circle.
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    0.55f to glow.copy(alpha = .28f),
                                    1f to Color.Transparent,
                                    center = center,
                                    radius = size.minDimension * .8f,
                                ),
                            radius = size.minDimension * .8f,
                        )
                    }.clip(CircleShape)
                    .background(NeonBoard.chipOn(RequestActivityPalette.EmptyBorder))
                    .border(1.5.dp, RequestActivityPalette.EmptyBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = glow, modifier = Modifier.size(46.dp))
        }
        Text(
            "NOTHING REQUESTED YET",
            style = TextStyle(fontFamily = fonts.display, fontSize = 23.sp, letterSpacing = .08.em, color = NeonBoard.Text),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 26.dp),
        )
        val body =
            TextStyle(
                fontFamily = fonts.body,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = NeonBoard.Text.copy(alpha = .78f),
                textAlign = TextAlign.Center,
            )
        Text("Search for a movie or show above, or press the mic and say its name.", style = body, modifier = Modifier.padding(top = 16.dp))
        Text("Once you request something, you can follow it here.", style = body, modifier = Modifier.padding(top = 2.dp))
        Row(
            modifier = Modifier.padding(top = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(ActivitySection.ACTIVE, "downloading now", fonts)
            LegendItem(ActivitySection.WAITING, "not released yet", fonts)
            LegendItem(ActivitySection.READY, "ready to play", fonts)
        }
    }
}

@Composable
private fun LegendItem(
    section: ActivitySection,
    note: String,
    fonts: ActivityFonts,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(section.color))
        Text(
            section.title.uppercase(),
            style = TextStyle(fontFamily = fonts.display, fontSize = 12.sp, letterSpacing = .1.em, color = section.color),
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            note,
            style = TextStyle(fontFamily = fonts.body, fontSize = 11.sp, color = RequestActivityPalette.Muted),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
