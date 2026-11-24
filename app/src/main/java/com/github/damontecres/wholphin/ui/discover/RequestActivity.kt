package com.github.damontecres.wholphin.ui.discover

import com.github.damontecres.wholphin.services.RequestProgressRecord
import com.github.damontecres.wholphin.services.RequestProgressState
import com.github.damontecres.wholphin.services.RequestStageKind
import com.github.damontecres.wholphin.services.RequestStatusItem
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/*
 * What each row of the Requests ACTIVITY queue shows, worked out from a request and its live progress (plain Kotlin,
 * moved out of SeerrRequestsPage.kt for the no-Compose UI; the Compose page and the View page share it).
 */

internal fun RequestStatusItem.liveProgress(progress: RequestProgressState?): RequestProgressRecord? =
    progress?.records?.get(request.id)?.takeIf {
        !stage.terminal && request.requestedBy?.id == progress.seerrUserId && it.matches(this)
    }

internal fun RequestStatusItem.partlyWatchable(live: RequestProgressRecord?): Boolean =
    request.type == "tv" && (stage.kind == RequestStageKind.PARTIAL || live?.availability == "partial")

/** The three ACTIVITY sections, in page order. */
internal enum class ActivitySection(
    val title: String,
) {
    ACTIVE("Active"),
    WAITING("Waiting"),
    READY("Ready to watch"),
}

internal sealed interface ActivityProgress {
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
internal data class ActivityRow(
    val row: RequestStatusItem,
    val section: ActivitySection,
    val status: String,
    val detail: String?,
    val progress: ActivityProgress,
    val watchable: Boolean,
)

internal fun etaText(seconds: Long): String =
    if (seconds < 60) "Under a minute remaining" else "${seconds / 60 + if (seconds % 60 > 0) 1 else 0} min remaining"

internal fun shortDate(
    date: LocalDate,
    today: LocalDate,
): String = date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy", Locale.getDefault()))

internal fun requestedAt(row: RequestStatusItem): Instant? = row.request.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * A show part-way through its season with nothing missing: every aired episode is imported and the
 * rest haven't aired yet. Everything here comes from the progress service, which reads Sonarr:
 * "nothing missing" is waiting / not released yet with all reported episodes imported, and the next
 * episode, its air time and the season counts come from its `schedule`. Without a schedule (or a
 * next air time) the row keeps "Some episodes ready".
 */
internal fun airingRow(
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

internal fun activityRow(
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

    // The progress service says "ready" when every requested episode is in the library even though Seerr still calls
    // the show partial: Seerr wants its episode count to equal TMDB's, and double episodes numbered as two by Sonarr
    // (Parks and Recreation, 125 / 125) never match.
    if (row.stage.kind == RequestStageKind.READY || live?.stage == "ready") {
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

/**
 * The queue's rows in page order: the sections in order, each keeping the request order, except Ready, which lists
 * the newest request first. A live percentage never moves a row, only a change of section does.
 */
internal fun orderedActivityRows(rows: List<ActivityRow>): List<ActivityRow> =
    ActivitySection.entries.flatMap { section ->
        val inSection = rows.filter { it.section == section }
        if (section == ActivitySection.READY) inSection.sortedByDescending { requestedAt(it.row) ?: Instant.EPOCH } else inSection
    }
