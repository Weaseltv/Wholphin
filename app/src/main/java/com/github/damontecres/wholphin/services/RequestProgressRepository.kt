package com.github.damontecres.wholphin.services

import android.os.SystemClock
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.data.ServerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random

private const val PROGRESS_REFRESH_INTERVAL_MS = 1_000L

@Serializable
data class ProgressSeason(val n: Int, val availability: String)

@Serializable
data class ProgressEpisodes(val total: Int? = null, val imported: Int? = null, val active: Int? = null)

@Serializable
data class RequestProgressRecord(
    val requestId: Int,
    val mediaType: String,
    val tmdbId: Int,
    val is4k: Boolean,
    val seasons: List<ProgressSeason> = emptyList(),
    val seerrStatus: Int? = null,
    val availability: String,
    val stage: String,
    val stageProgress: Double? = null,
    val bytesLeft: Double? = null,
    val etaSeconds: Double? = null,
    val detail: String? = null,
    val episodes: ProgressEpisodes? = null,
    val stale: Boolean = false,
    val staleSince: String? = null,
    val observedAt: String,
    val updatedAt: String,
) {
    val fraction: Float? get() = stageProgress?.takeIf { it.isFinite() && it in 0.0..1.0 && stage in setOf("downloading", "unpacking") }?.toFloat()
    val label: String get() {
        val base = when (stage) {
            "pending_approval" -> "Waiting for approval"
            "approved" -> "Request accepted"
            "waiting" -> if (detail == "not_released_yet") "Waiting for release" else "Waiting for a download"
            "searching" -> "Searching for a release"
            "queued" -> "Queued for download"
            "downloading" -> "Downloading"
            "checking" -> "Checking download"
            "unpacking" -> "Unpacking"
            "importing" -> "Importing"
            "adding_to_library" -> "Adding to your library"
            "confirming_availability", "ready" -> "Finishing up"
            "paused" -> "Download paused"
            else -> "Checking request status"
        }
        return base + (fraction?.let { " ${(it * 100).toInt()}%" } ?: "")
    }

    fun matches(item: RequestStatusItem): Boolean = requestId == item.request.id &&
        mediaType == item.request.type && tmdbId == item.request.media?.tmdbId && is4k == (item.request.is4k == true) &&
        (mediaType != "tv" || seasons.map { it.n }.distinct().sorted() == item.request.seasons.orEmpty().mapNotNull { it.seasonNumber }.distinct().sorted())

    fun valid(): Boolean = requestId > 0 && tmdbId > 0 && mediaType in setOf("movie", "tv") &&
        availability in setOf("none", "partial", "full") && seasons.all { it.n >= 0 && it.availability in setOf("none", "partial", "full") } &&
        stageProgress?.let { it.isFinite() && it in 0.0..1.0 } != false && bytesLeft?.let { it.isFinite() && it >= 0 } != false &&
        etaSeconds?.let { it.isFinite() && it >= 0 } != false && episodes?.let { e ->
            listOfNotNull(e.total, e.imported, e.active).all { it >= 0 }
        } != false && runCatching { Instant.parse(observedAt); Instant.parse(updatedAt) }.isSuccess
}

@Serializable
private data class ProgressEnvelope(
    val v: Int,
    val boot: String,
    val rev: Long,
    val observedAt: String,
    val requests: List<RequestProgressRecord>? = null,
    val request: RequestProgressRecord? = null,
    val requestId: Int? = null,
    val reason: String? = null,
    val auth: String? = null,
)

enum class ProgressMode { OFF, CONNECTING, LIVE, POLLING, RECONNECTING, REVALIDATING, UNAVAILABLE, AUTH_REQUIRED }

data class RequestProgressState(
    internal val client: SeerrApiClient? = null,
    val ownerRowId: Int? = null,
    val seerrUserId: Int? = null,
    val records: Map<Int, RequestProgressRecord> = emptyMap(),
    val mode: ProgressMode = ProgressMode.OFF,
    val boot: String? = null,
    val rev: Long = -1,
    val transportStale: Boolean = true,
    val lastReceived: Instant? = null,
) {
    val message: String get() = when (mode) {
        ProgressMode.LIVE -> "Live updates"
        ProgressMode.POLLING -> "Updating automatically"
        ProgressMode.CONNECTING -> "Connecting live updates…"
        ProgressMode.RECONNECTING -> "Reconnecting live updates…"
        ProgressMode.REVALIDATING, ProgressMode.AUTH_REQUIRED -> "Reconnecting request updates…"
        ProgressMode.UNAVAILABLE -> "Live updates unavailable. Regular automatic checks continue."
        ProgressMode.OFF -> ""
    }
}

/** Ephemeral, personal progress overlay. Never writes request history, badges or notification storage. */
@Singleton
class RequestProgressRepository @Inject constructor(
    private val connections: SeerrServerRepository,
    private val service: SeerrService,
    private val servers: ServerRepository,
) {
    private val visible = MutableStateFlow(false)
    private val allowed = MutableStateFlow(false)
    private val restart = MutableStateFlow(0L)
    private val generation = AtomicLong()
    private val foregroundMutex = Mutex()
    private val allowedHosts = java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
    private val _state = MutableStateFlow(RequestProgressState())
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var deniedClient: SeerrApiClient? = null
    @Volatile private var deniedUntil = 0L

    fun setVisible(value: Boolean) { visible.value = value }
    fun setAllowed(host: Any, value: Boolean) { allowedHosts[host] = value; allowed.value = allowedHosts.values.any { it } }
    fun removeHost(host: Any) { allowedHosts.remove(host); allowed.value = allowedHosts.values.any { it } }
    fun refresh(allowRetry: Boolean = false) {
        if (allowRetry) { deniedClient = null; deniedUntil = 0 }
        restart.update { it + 1 }
    }
    fun forget(id: Int) { _state.update { it.copy(records = it.records - id) } }

    private class Capture(val current: CurrentSeerr, val client: SeerrApiClient, val root: HttpUrl) {
        override fun equals(other: Any?): Boolean = other is Capture && current === other.current && client === other.client && root == other.root
        override fun hashCode(): Int = System.identityHashCode(current) * 31 + System.identityHashCode(client)
    }
    private data class Selection(val capture: Capture?, val restart: Long)
    private data class Result(val code: Int = 0, val retry: Long = 0, val unsupported: Boolean = false)
    private sealed interface StreamMessage {
        data class Event(val type: String?, val data: String) : StreamMessage
        data class End(val result: Result) : StreamMessage
    }
    private class Cursor(var boot: String? = null, var rev: Long = -1, var snapshot: Boolean = false, var paused: Boolean = false)

    private fun capture(): Capture? {
        val c = (connections.connection.value as? SeerrConnectionStatus.Success)?.current ?: return null
        if (servers.currentUser?.rowId != c.user.jellyfinUserRowId) return null
        val root = c.server.url.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build() ?: return null
        val pinned = BuildConfig.DEFAULT_SEERR_URL.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()
        if (root.scheme != "https" || root != pinned) return null
        val client = runCatching { service.api }.getOrNull() ?: return null
        return if (client.hasProgressSession) Capture(c, client, root) else null
    }

    suspend fun runForeground(
        onRecord: (RequestProgressRecord) -> Unit,
        onDeleted: (Int) -> Unit,
        onAuthIssue: (SeerrApiClient) -> Unit,
    ) = foregroundMutex.withLock {
        val captures = flow {
            while (currentCoroutineContext().isActive) {
                emit(capture())
                delay(1_000)
            }
        }.distinctUntilChanged()
        combine(visible, allowed, captures, restart) { shown, safe, account, kick -> Selection(account.takeIf { shown && safe }, kick) }
            .distinctUntilChanged().collectLatest { selection ->
                val token = generation.incrementAndGet()
                val c = selection.capture
                _state.value = if (c == null) RequestProgressState() else RequestProgressState(
                    client = c.client, ownerRowId = c.current.user.jellyfinUserRowId, seerrUserId = c.current.config.id, mode = ProgressMode.CONNECTING,
                )
                try {
                    if (c != null) runConnection(c, token, onRecord, onDeleted, onAuthIssue)
                } finally {
                    if (generation.compareAndSet(token, token + 1)) {
                        _state.update { if (generation.get() == token + 1) RequestProgressState() else it }
                    }
                }
            }
    }

    private fun current(c: Capture, token: Long): Boolean = generation.get() == token &&
        servers.currentUser?.rowId == c.current.user.jellyfinUserRowId &&
        (connections.connection.value as? SeerrConnectionStatus.Success)?.current === c.current &&
        runCatching { service.api === c.client }.getOrDefault(false)

    private fun publish(c: Capture, token: Long, change: (RequestProgressState) -> RequestProgressState) {
        _state.update { if (current(c, token)) change(it) else it }
    }

    private fun apply(
        c: Capture, token: Long, type: String, data: String, cursor: Cursor, mode: ProgressMode,
        onRecord: (RequestProgressRecord) -> Unit, onDeleted: (Int) -> Unit,
    ): Boolean {
        if (!current(c, token)) return false
        require(data.length <= 2_000_000)
        val e = json.decodeFromString<ProgressEnvelope>(data)
        require(e.v == 1 && e.boot.isNotBlank() && e.rev >= 0)
        Instant.parse(e.observedAt)
        if (type == "snapshot") {
            if (cursor.paused) return false
            val rows = requireNotNull(e.requests)
            require(rows.size <= 1000 && rows.all { it.valid() } && rows.map { it.requestId }.distinct().size == rows.size)
            val previous = _state.value
            if (previous.boot == e.boot && e.rev < previous.rev) return false
            cursor.boot = e.boot; cursor.rev = e.rev; cursor.snapshot = true
            publish(c, token) { it.copy(records = rows.associateBy { r -> r.requestId }, boot = e.boot, rev = e.rev,
                mode = mode, transportStale = false, lastReceived = Instant.now()) }
            if (current(c, token)) rows.forEach(onRecord)
            return false
        }
        if (!cursor.snapshot || e.boot != cursor.boot || e.rev <= cursor.rev) return false
        cursor.rev = e.rev
        when (type) {
            "status" -> when (e.auth) {
                "revalidating" -> { cursor.paused = true; publish(c, token) { it.copy(mode = ProgressMode.REVALIDATING, transportStale = true, rev = e.rev) } }
                "ok" -> { cursor.paused = false; cursor.snapshot = false; publish(c, token) { it.copy(mode = ProgressMode.CONNECTING, transportStale = true, rev = e.rev) } }
            }
            "auth_expired" -> return true
            "update" -> if (!cursor.paused) {
                val row = requireNotNull(e.request); require(row.valid())
                val prior = _state.value.records[row.requestId]
                publish(c, token) { it.copy(records = it.records + (row.requestId to row), rev = e.rev, mode = mode,
                    transportStale = false, lastReceived = Instant.now()) }
                if (prior == null || row.availability != prior.availability || row.seasons != prior.seasons || row.seerrStatus != prior.seerrStatus || prior.stale && !row.stale) { if (current(c, token)) onRecord(row) }
            }
            "remove" -> if (!cursor.paused) {
                val id = requireNotNull(e.requestId)
                publish(c, token) { it.copy(records = it.records - id, rev = e.rev, lastReceived = Instant.now()) }
                if (e.reason == "deleted" && current(c, token)) onDeleted(id)
            }
        }
        return false
    }

    private fun request(c: Capture, path: String) = Request.Builder()
        .url(c.root.newBuilder().encodedPath("/progress/v1/$path").build())
        .header("Cache-Control", "no-cache").build()

    private suspend fun stream(
        c: Capture, token: Long, onRecord: (RequestProgressRecord) -> Unit, onDeleted: (Int) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        // Parse/apply both transports on this single actor. An audit snapshot can advance
        // the shared cursor without an older queued stream event overwriting it.
        val messages = Channel<StreamMessage>(64)
        val cursor = Cursor()
        var auditFailures = 0
        var nextAuditAt = 0L
        fun offer(message: StreamMessage) {
            if (!messages.trySend(message).isSuccess) messages.close()
        }
        val opened = EventSources.createFactory(c.client.progressStreamClient).newEventSource(request(c, "stream"), object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (!current(c, token)) return
                if (data.length > 2_000_000) offer(StreamMessage.End(Result(unsupported = true)))
                else offer(StreamMessage.Event(type, data))
            }
            override fun onClosed(eventSource: EventSource) { offer(StreamMessage.End(Result())) }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                offer(StreamMessage.End(Result(code = response?.code ?: 0, retry = retryAfter(response), unsupported = response?.code in setOf(200, 301, 302, 404, 405, 501))))
            }
        })
        try {
            while (currentCoroutineContext().isActive && current(c, token)) {
                val wait = if (!cursor.snapshot) 15_000L else maxOf(PROGRESS_REFRESH_INTERVAL_MS, nextAuditAt - SystemClock.uptimeMillis())
                val incoming = withTimeoutOrNull(wait) { messages.receiveCatching() }
                if (incoming == null) {
                    if (!cursor.snapshot) return@withContext Result()
                    // Heartbeats prove only that the socket is open. On a quiet stream,
                    // check the service's shared JSON cache at a one-second cadence.
                    val auditCursor = Cursor()
                    val result = snapshot(c, token, onRecord, onDeleted, auditCursor, ProgressMode.LIVE)
                    if (result.code in setOf(401, 403)) return@withContext result
                    if (result.code != 200) {
                        // A failed backup read does not mean the live socket failed.
                        // Back off audits while continuing to receive live messages.
                        auditFailures = (auditFailures + 1).coerceAtMost(6)
                        nextAuditAt = SystemClock.uptimeMillis() + maxOf(backoff(auditFailures), result.retry, if (result.unsupported) 60_000L else 0L)
                        publish(c, token) { it.copy(transportStale = true) }
                        continue
                    }
                    auditFailures = 0
                    nextAuditAt = 0
                    if (!auditCursor.snapshot) continue
                    cursor.boot = auditCursor.boot
                    cursor.rev = auditCursor.rev
                    cursor.snapshot = true
                    cursor.paused = false
                    // A snapshot can win a normal race with the next live event. Keep
                    // the socket open; the advanced cursor rejects older queued events.
                    continue
                }
                val message = incoming.getOrNull() ?: return@withContext Result()
                when (message) {
                    is StreamMessage.End -> return@withContext message.result
                    is StreamMessage.Event -> if (message.type in setOf("snapshot", "update", "remove", "status", "auth_expired")) {
                        try {
                            if (apply(c, token, message.type!!, message.data, cursor, ProgressMode.LIVE, onRecord, onDeleted)) return@withContext Result(code = 401)
                        } catch (ex: Exception) {
                            // Never log the body, cookie, account identity or parser exception text.
                            Timber.w("Request progress: rejected %s event (%s)", message.type, ex.javaClass.simpleName)
                            return@withContext Result(unsupported = true)
                        }
                    }
                }
            }
            Result()
        } finally {
            opened.cancel()
            messages.close()
        }
    }

    private suspend fun snapshot(
        c: Capture, token: Long, onRecord: (RequestProgressRecord) -> Unit, onDeleted: (Int) -> Unit,
        cursor: Cursor = Cursor(), mode: ProgressMode = ProgressMode.POLLING,
    ): Result = suspendCancellableCoroutine { continuation ->
        val call = c.client.progressSnapshotClient.newCall(request(c, "snapshot"))
        val done = AtomicBoolean(false)
        fun finish(result: Result) {
            if (done.compareAndSet(false, true)) continuation.resume(result)
        }
        continuation.invokeOnCancellation { done.set(true); call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { finish(Result()) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        finish(Result(response.code, retryAfter(response), response.code in setOf(301, 302, 404, 405, 501)))
                        return
                    }
                    try {
                        val source = response.body.source()
                        source.request(2_000_001)
                        if (source.buffer.size > 2_000_000) { finish(Result(unsupported = true)); return }
                        if (continuation.isActive) apply(c, token, "snapshot", source.readUtf8(), cursor, mode, onRecord, onDeleted)
                        finish(Result(code = 200))
                    } catch (_: IllegalArgumentException) { finish(Result(unsupported = true)) }
                    catch (_: Exception) { finish(Result()) }
                }
            }
        })
    }

    private fun retryAfter(response: Response?): Long {
        val value = response?.header("Retry-After") ?: return 0
        return (value.toLongOrNull()?.coerceIn(0L, 300L)?.times(1_000) ?: runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()
        }.getOrDefault(0)).coerceIn(0L, 300_000L)
    }
    private fun backoff(failures: Int) = (2_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(60_000L) + Random.nextLong(0, 400)

    private suspend fun runConnection(c: Capture, token: Long, onRecord: (RequestProgressRecord) -> Unit, onDeleted: (Int) -> Unit, onAuthIssue: (SeerrApiClient) -> Unit) {
        var streamFailures = 0
        var snapshotFailures = 0
        var nextStream = 0L
        var useStream = true
        while (currentCoroutineContext().isActive && current(c, token)) {
            if (deniedClient === c.client && SystemClock.uptimeMillis() < deniedUntil) {
                publish(c, token) { it.copy(mode = ProgressMode.AUTH_REQUIRED, transportStale = true) }
                delay(1_000); continue
            }
            val wasStream = useStream && SystemClock.uptimeMillis() >= nextStream
            val result = if (wasStream) stream(c, token, onRecord, onDeleted) else snapshot(c, token, onRecord, onDeleted)
            if (!current(c, token)) return
            if (result.code in setOf(401, 403)) {
                deniedClient = c.client; deniedUntil = SystemClock.uptimeMillis() + 900_000
                publish(c, token) { it.copy(mode = ProgressMode.AUTH_REQUIRED, transportStale = true) }
                onAuthIssue(c.client)
                continue
            }
            if (result.unsupported) {
                publish(c, token) { it.copy(mode = ProgressMode.UNAVAILABLE, transportStale = true) }
                if (wasStream) {
                    // A proxy may support JSON but not streaming. Try the cached snapshot first.
                    useStream = false; nextStream = SystemClock.uptimeMillis() + 60_000
                    delay(2_000)
                } else {
                    useStream = true; nextStream = 0
                    delay(60_000)
                }
                continue
            }
            if (wasStream) {
                streamFailures = (streamFailures + 1).coerceAtMost(6)
                nextStream = SystemClock.uptimeMillis() + maxOf(backoff(streamFailures), result.retry)
                publish(c, token) { it.copy(mode = ProgressMode.RECONNECTING, transportStale = true) }
                useStream = false
                delay(maxOf(2_000, result.retry))
            } else if (result.code == 200) {
                snapshotFailures = 0
                delay(PROGRESS_REFRESH_INTERVAL_MS)
                useStream = true
            } else {
                snapshotFailures = (snapshotFailures + 1).coerceAtMost(6)
                publish(c, token) { it.copy(mode = ProgressMode.RECONNECTING, transportStale = true) }
                delay(maxOf(backoff(snapshotFailures), result.retry))
                useStream = true
            }
        }
    }
}
