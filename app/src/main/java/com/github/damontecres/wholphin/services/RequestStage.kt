package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.data.model.DiscoverItem
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RequestStageKind { WAITING_APPROVAL, WAITING_LIBRARY, DOWNLOADING, ADDING, READY, PARTIAL, FAILED, CHECKING, UNRELEASED }

data class RequestStage(
    val kind: RequestStageKind,
    val label: String,
    val progress: Float? = null,
    val eta: Instant? = null,
    val signature: String = "-",
    val detail: String? = null,
    val notify: Boolean = true,
) {
    val ready: Boolean get() = kind == RequestStageKind.READY || kind == RequestStageKind.PARTIAL
    val terminal: Boolean get() = kind == RequestStageKind.READY || kind == RequestStageKind.FAILED
}

data class RequestStatusItem(
    val request: MediaRequest,
    val stage: RequestStage,
    val item: DiscoverItem? = null,
    val fresh: Boolean = true,
    val availabilityDate: LocalDate? = item?.releaseDate,
) {
    val title: String get() = item?.title ?: "Requested ${if (request.type == "tv") "show" else "movie"}"
    val jellyfinId: UUID? get() = request.media?.jellyfinId(request.is4k == true)
}

fun com.github.damontecres.wholphin.api.seerr.model.MediaInfo.jellyfinId(is4k: Boolean): UUID? =
    (if (is4k) jellyfinMediaId4k else jellyfinMediaId)?.toUUIDOrNull()

/** Phase 0 contract: no search/failure inference from an empty or incomplete queue. */
fun deriveRequestStage(request: MediaRequest, futureRelease: LocalDate? = null): RequestStage {
    val media = request.media
    val is4k = request.is4k == true
    val status = if (is4k) media?.status4k else media?.status
    if (request.status in listOf(3, 4) || status in listOf(6, 7)) {
        return RequestStage(RequestStageKind.FAILED, when {
            request.status == 3 -> "Request declined"
            status in listOf(6, 7) -> "Not available"
            else -> "Couldn't be added"
        }, notify = request.status in listOf(3, 4) || request.status != 5 && status == 6)
    }
    val seasons = request.seasons.orEmpty().mapNotNull { it.seasonNumber }.distinct()
    val availability = media?.seasons.orEmpty().associate { it.seasonNumber to if (is4k) it.status4k else it.status }
    if (request.type == "movie" && status == 5 || request.type == "tv" && seasons.isNotEmpty() && seasons.all { availability[it] == 5 }) {
        return RequestStage(RequestStageKind.READY, "Ready to watch")
    }
    val partial = request.type == "tv" && seasons.isNotEmpty() && seasons.any { availability[it] in listOf(4, 5) }
    if (request.status == 1 && !partial) return RequestStage(RequestStageKind.WAITING_APPROVAL, "Waiting for approval")
    if (request.type == "tv" && seasons.isEmpty()) return RequestStage(RequestStageKind.CHECKING, "Checking")
    val remaining = if (partial) seasons.filter { availability[it] != 5 } else seasons
    val queue = (if (is4k) media?.downloadStatus4k else media?.downloadStatus).orEmpty().filter {
        request.type != "tv" || it.episode?.seasonNumber == null || it.episode.seasonNumber in remaining
    }
    val active = queue.filter {
        it.status?.lowercase() in listOf("downloading", "queued") &&
            it.propertySize?.let { size -> size.isFinite() && size > 0 && it.sizeLeft?.let { left -> left.isFinite() && left in 0.0..size } == true } == true
    }
    val activeSize = active.sumOf { it.propertySize!! }
    val activeLeft = active.sumOf { it.sizeLeft!! }
    val rest = when {
        request.status == 1 -> RequestStage(RequestStageKind.WAITING_APPROVAL, "Waiting for approval")
        queue.isNotEmpty() && queue.all { it.status?.lowercase() == "completed" } -> RequestStage(RequestStageKind.ADDING, "Adding to your library")
        active.isNotEmpty() && activeSize.isFinite() && activeSize > 0 && activeLeft.isFinite() -> {
            val progress = (1.0 - activeLeft / activeSize).coerceIn(0.0, 1.0).toFloat()
            val eta = active.mapNotNull { runCatching { Instant.parse(it.estimatedCompletionTime) }.getOrNull() }.filter { it.isAfter(Instant.now()) }.maxOrNull()
            RequestStage(RequestStageKind.DOWNLOADING, "Downloading ${(progress * 100).toInt()}%", progress, eta)
        }
        queue.isNotEmpty() -> RequestStage(RequestStageKind.CHECKING, "Checking")
        request.type == "movie" && futureRelease?.isAfter(LocalDate.now()) == true -> RequestStage(RequestStageKind.UNRELEASED, "Not out yet · Coming $futureRelease")
        request.status == 2 || request.status == 5 -> RequestStage(
            RequestStageKind.WAITING_LIBRARY,
            "Request accepted",
            detail = "No download progress is available yet. We'll let you know when it's ready to watch.",
        )
        else -> RequestStage(RequestStageKind.CHECKING, "Checking")
    }
    return if (partial) {
        RequestStage(RequestStageKind.PARTIAL, "Some episodes ready", rest.progress, rest.eta,
            seasons.sorted().mapNotNull { n -> when (availability[n]) { 5 -> "$n:A"; 4 -> "$n:P"; else -> null } }.joinToString(","), rest.label)
    } else rest
}

fun RequestStatusItem.eventKey(): String? {
    if (!stage.notify) return null
    val kind = when (stage.kind) {
        RequestStageKind.READY -> "ready"
        RequestStageKind.PARTIAL -> "partial"
        RequestStageKind.FAILED -> "failed"
        else -> return null
    }
    return "v1|${request.id}|${if (request.is4k == true) "4k" else "hd"}|$kind|${stage.signature}"
}
