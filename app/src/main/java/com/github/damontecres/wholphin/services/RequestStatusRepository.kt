package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.RequestApi
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.api.seerr.model.MediaRequest
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.SeerrPermission
import com.github.damontecres.wholphin.data.model.hasPermission
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RequestEvent(
    val key: String,
    val requestId: Int,
    val scopeKey: String? = null,
)

/** Season signatures can change without a new user-facing milestone. Keep the wire key intact. */
internal fun String.requestPresentationKey(): String {
    val parts = split('|', limit = 5)
    return if (parts.size == 5 && parts[0] == "v1" && parts[3] == "partial") {
        parts.take(4).joinToString("|")
    } else {
        this
    }
}

internal fun RequestEvent.presentationKey(): String = key.requestPresentationKey()

/** Transient submission feedback; never saved or counted as a ready notification. */
data class RequestSubmission(
    val item: RequestStatusItem,
    val updated: Boolean,
    val createdAt: Long = android.os.SystemClock.uptimeMillis(),
)

@Serializable
data class RequestNotificationStore(
    val enabled: Boolean = true,
    val initialized: Boolean = false,
    val awaitingBaseline: Set<Int> = emptySet(),
    val baselined: Set<Int> = emptySet(),
    val seeded: Set<Int> = emptySet(),
    val delivered: Set<String> = emptySet(),
    val pending: List<RequestEvent> = emptyList(),
    val unread: Set<Int> = emptySet(),
    val downloadObserved: Set<Int> = emptySet(),
)

data class RequestsState(
    val scope: String? = null,
    val ownerRowId: Int? = null,
    val items: List<RequestStatusItem> = emptyList(),
    val connected: Boolean = false,
    val loading: Boolean = false,
    val lastUpdated: Instant? = null,
    val error: String? = null,
    val syncComplete: Boolean = false,
    val notifications: RequestNotificationStore = RequestNotificationStore(),
    val submission: RequestSubmission? = null,
) {
    val badgeCount: Int get() = items.count { it.stage.ready && it.request.id in notifications.unread }
}

/** One foreground coordinator, one captured client/user per generation, one durable notification store. */
@Singleton
class RequestStatusRepository
    @Inject
    constructor(
        val progress: RequestProgressRepository,
        private val service: SeerrService,
        private val connections: SeerrServerRepository,
        private val servers: ServerRepository,
        private val keyValue: KeyValueService,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
    ) {
        private data class Session(
            val current: CurrentSeerr,
            val client: SeerrApiClient,
            val key: String,
            val generation: Long,
        )

        private val mutex = Mutex()
        private val probeMutex = Mutex()
        private val liveHints = Channel<Unit>(Channel.CONFLATED)
        private val liveRows = java.util.concurrent.ConcurrentHashMap<Int, Session>()

        @Volatile private var session: Session? = null

        @Volatile private var generation = 0L
        private var listedCurrent: CurrentSeerr? = null
        private val probeTimes = mutableMapOf<String, Long>()
        private val missing = mutableMapOf<Int, Int>()
        private val metadataCache =
            java.util.concurrent.ConcurrentHashMap<
                Pair<String?, Int>,
                Pair<com.github.damontecres.wholphin.data.model.DiscoverItem, java.time.LocalDate?>,
            >()
        private val metadataInFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val _state = MutableStateFlow(RequestsState())
        val state: StateFlow<RequestsState> = _state.asStateFlow()
        private val visiblePages =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val myRequestsVisible: Boolean get() = visiblePages.get() > 0

        init {
            scope.launch {
                connections.connection.collect { connection ->
                    if (connections.connection.value !== connection) return@collect
                    if (connection !is SeerrConnectionStatus.Success) {
                        mutex.withLock {
                            if (connections.connection.value !== connection) return@withLock
                            generation++
                            session = null
                            _state.update {
                                if (connection is SeerrConnectionStatus.NotConfigured) {
                                    RequestsState()
                                } else {
                                    it.copy(
                                        connected = false,
                                        loading = false,
                                    )
                                }
                            }
                        }
                    }
                    refresh()
                }
            }
        }

        private fun current(s: Session): Boolean =
            session === s && generation == s.generation &&
                servers.currentUser?.rowId == s.current.user.jellyfinUserRowId &&
                service.api === s.client &&
                (connections.connection.value as? SeerrConnectionStatus.Success)?.current === s.current

        private fun key(c: CurrentSeerr): String {
            val url =
                c.server.url
                    .trimEnd('/')
                    .removeSuffix("/api/v1")
            val pinned = BuildConfig.DEFAULT_SEERR_URL.trimEnd('/').removeSuffix("/api/v1")
            return "request_notifications_${if (url == pinned) "weaselplex" else url}|${c.config.id}"
        }

        suspend fun runForeground(): Unit =
            coroutineScope {
                launch {
                    for (hint in liveHints) {
                        delay(100) // Coalesce a snapshot's changed requests without refreshing history.
                        val changed =
                            liveRows.entries.mapNotNull { (id, owner) ->
                                if (liveRows.remove(id, owner)) id to owner else null
                            }
                        for ((id, owner) in changed) refreshRequest(owner, id)
                        delay(2_000)
                    }
                }
                launch {
                    progress.runForeground(
                        onRecord = ::onLiveRecord,
                        onDeleted = { requestCanonicalRefresh(it) },
                        onAuthIssue = { client ->
                            scope.launch {
                                session?.takeIf { it.client === client && current(it) }?.let { probeSession(it) }
                            }
                        },
                    )
                }
                var previous: Session? = null
                var failures = 0
                var resumed = true
                while (currentCoroutineContext().isActive) {
                    val s =
                        mutex.withLock {
                            val c = (connections.connection.value as? SeerrConnectionStatus.Success)?.current
                            if (c == null || servers.currentUser?.rowId != c.user.jellyfinUserRowId) {
                                if (session != null) {
                                    generation++
                                    session = null
                                }
                                if (c != null) _state.value = RequestsState()
                                return@withLock null
                            }
                            val client = service.api
                            val existing = session
                            if (!resumed && existing != null && current(existing)) return@withLock existing
                            val k = key(c)
                            val store = readStore(k)
                            // A reconnect/profile change can finish while the journal is being read.
                            if ((connections.connection.value as? SeerrConnectionStatus.Success)?.current !== c ||
                                service.api !== client || servers.currentUser?.rowId != c.user.jellyfinUserRowId
                            ) {
                                return@withLock null
                            }
                            generation++
                            val oldState = _state.value
                            Session(c, client, k, generation).also {
                                session = it
                                if (oldState.scope != k) {
                                    missing.clear()
                                    metadataInFlight.clear()
                                    metadataCache.clear()
                                }
                                _state.value =
                                    if (oldState.scope ==
                                        k
                                    ) {
                                        oldState.copy(connected = true, notifications = store, ownerRowId = c.user.jellyfinUserRowId)
                                    } else {
                                        RequestsState(
                                            scope = k,
                                            connected = true,
                                            notifications = store,
                                            ownerRowId = c.user.jellyfinUserRowId,
                                        )
                                    }
                            }
                        }
                    resumed = false
                    val waitMillis =
                        if (s == null) {
                            failures = 0
                            60_000L
                        } else {
                            if (s !== previous) failures = 0
                            previous = s
                            val ok = sync(s)
                            // Never leave the foreground worker parked on a superseded client/session.
                            // StateFlow may conflate a reconnect back to an equal account value.
                            if (!current(s)) continue
                            failures = if (ok) 0 else (failures + 1).coerceAtMost(4)
                            when {
                                failures > 0 -> listOf(60_000L, 120_000L, 300_000L, 600_000L)[failures - 1]

                                // Also discover requests created elsewhere while the known list is idle.
                                else -> 60_000L
                            }
                        }
                    withTimeoutOrNull(waitMillis) { wake.receive() }
                }
            }

        fun refresh() {
            wake.trySend(Unit)
        }

        fun acceptsProgress(value: RequestProgressState): Boolean {
            val s = session ?: return false
            return current(s) && value.client === s.client && value.seerrUserId == s.current.config.id &&
                value.ownerRowId == s.current.user.jellyfinUserRowId
        }

        fun refreshVisible() {
            progress.refresh(allowRetry = true)
            refresh()
        }

        fun setVisible(visible: Boolean) {
            visiblePages.updateAndGet { (it + if (visible) 1 else -1).coerceAtLeast(0) }
            progress.setVisible(myRequestsVisible)
            if (visible) {
                refresh()
                markOpened()
            }
        }

        private fun requestCanonicalRefresh(id: Int) {
            val owner = session?.takeIf { current(it) } ?: return
            liveRows[id] = owner
            liveHints.trySend(Unit)
        }

        private fun onLiveRecord(record: RequestProgressRecord) {
            val row = _state.value.items.firstOrNull { it.request.id == record.requestId }
            if (row == null) {
                requestCanonicalRefresh(record.requestId)
                return
            }
            if (!record.matches(row) || record.stale) return
            val availability =
                when (row.stage.kind) {
                    RequestStageKind.READY -> "full"
                    RequestStageKind.PARTIAL -> "partial"
                    else -> "none"
                }
            val seasonsChanged =
                record.seasons.any { season ->
                    val old =
                        row.request.media
                            ?.seasons
                            ?.firstOrNull { it.seasonNumber == season.n }
                    val oldStatus = if (record.is4k) old?.status4k else old?.status
                    val oldAvailability =
                        when (oldStatus) {
                            5 -> "full"
                            4 -> "partial"
                            else -> "none"
                        }
                    season.availability != oldAvailability
                }
            if (!row.fresh || record.availability != availability || seasonsChanged ||
                record.seerrStatus?.let { it != row.request.status } == true
            ) {
                requestCanonicalRefresh(record.requestId)
            }
        }

        /** Progress records omit library IDs and ownership: verify only the affected request. */
        private suspend fun refreshRequest(
            s: Session,
            id: Int,
        ) = mutex.withLock {
            if (!current(s)) return@withLock
            val prior = _state.value.items.firstOrNull { it.request.id == id }
            if (prior == null || !_state.value.notifications.initialized) {
                refresh()
                return@withLock
            }
            try {
                val detail = s.client.requestApi.requestRequestIdGet(id.toString())
                if (!current(s)) return@withLock
                if (detail.id != id || detail.requestedBy?.id != s.current.config.id ||
                    detail.type != prior.request.type || detail.media?.tmdbId != prior.request.media?.tmdbId ||
                    (detail.is4k == true) != (prior.request.is4k == true)
                ) {
                    refresh()
                    return@withLock
                }
                // Incomplete season evidence cannot replace a good row or produce a ready event.
                if (detail.type == "tv" && detail.media?.seasons == null) return@withLock
                val cached = metadataCache[detail.type to (detail.media?.tmdbId ?: return@withLock)]
                val metadata = prior.item ?: cached?.first
                val availabilityDate = prior.availabilityDate ?: cached?.second
                val next =
                    RequestStatusItem(
                        detail,
                        deriveRequestStage(
                            detail,
                            availabilityDate,
                            prior.stage.downloadObserved || id in _state.value.notifications.downloadObserved,
                        ),
                        metadata,
                        true,
                        availabilityDate,
                    )
                val items =
                    _state.value.items
                        .map { if (it.request.id == id) next else it }
                        .sortedByDescending { it.request.updatedAt ?: "" }
                val store = trackEvents(_state.value.notifications, items, s.key, emptySet())
                if (store != _state.value.notifications) keyValue.save(s.key, store)
                if (!current(s)) return@withLock
                missing.remove(id)
                // Keep every other row, its metadata and images on screen throughout this read.
                _state.update { it.copy(items = items, notifications = store, lastUpdated = Instant.now()) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (!current(s)) return@withLock
                Timber.w("Request detail refresh paused: %s", ex.javaClass.simpleName)
                val code = (ex as? ClientException)?.statusCode
                if (code == 404) {
                    refresh() // Existing full-sync confirmation owns deletion/history.
                } else if (code == 401 || (code == 403 && listedCurrent === s.current)) {
                    probeSession(s)
                }
            }
        }

        private suspend fun sync(s: Session): Boolean =
            mutex.withLock {
                if (!current(s)) return@withLock false
                _state.update { it.copy(loading = true) }
                try {
                    val rows = linkedMapOf<Int, MediaRequest>()
                    var skip = 0
                    var complete = false
                    while (skip < 1000) {
                        val page =
                            s.client.requestApi.requestGet(
                                take = 50,
                                skip = skip,
                                filter = RequestApi.FilterRequestGet.ALL,
                                sort = RequestApi.SortRequestGet.ADDED,
                                requestedBy = s.current.config.id,
                            )
                        if (!current(s)) return@withLock false
                        val results = page.results.orEmpty()
                        results.filter { it.requestedBy?.id == s.current.config.id }.forEach { rows[it.id] = it }
                        val pageInfo = page.pageInfo
                        skip += results.size
                        if ((pageInfo?.page != null && pageInfo.pages != null && pageInfo.page >= pageInfo.pages) || results.isEmpty() ||
                            results.size < 50
                        ) {
                            complete = true
                            break
                        }
                    }
                    listedCurrent = s.current
                    if (!complete) Timber.w("Request sync reached 1,000-row cap")
                    val old = _state.value.items.associateBy { it.request.id }
                    val detailFailures = mutableSetOf<Int>()
                    val semaphore = Semaphore(4)
                    val tvDetails =
                        coroutineScope {
                            rows.values
                                .filter { it.type == "tv" }
                                .groupBy { it.media?.tmdbId ?: -it.id }
                                .values
                                .map { group ->
                                    async {
                                        semaphore.withPermit {
                                            val first = group.first()
                                            val unchanged =
                                                group.all { row ->
                                                    old[row.id]?.let { prior ->
                                                        prior.fresh && prior.stage.kind == RequestStageKind.READY &&
                                                            prior.request.updatedAt == row.updatedAt &&
                                                            prior.request.media?.updatedAt == row.media?.updatedAt &&
                                                            prior.request.media?.status == row.media?.status &&
                                                            prior.request.media?.status4k == row.media?.status4k &&
                                                            prior.request.media?.seasons != null
                                                    } == true
                                                }
                                            if (unchanged) {
                                                return@withPermit (first.media?.tmdbId ?: -first.id) to
                                                    old[first.id]?.request?.media?.seasons
                                            }
                                            try {
                                                val detail = s.client.requestApi.requestRequestIdGet(first.id.toString())
                                                if (detail.requestedBy?.id !=
                                                    s.current.config.id
                                                ) {
                                                    null
                                                } else {
                                                    (first.media?.tmdbId ?: -first.id) to detail.media?.seasons
                                                }
                                            } catch (ex: CancellationException) {
                                                throw ex
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                    }
                                }.awaitAll()
                                .filterNotNull()
                                .toMap()
                        }
                    if (!current(s)) return@withLock false
                    val items =
                        rows.values
                            .map { row ->
                                val prior = old[row.id]
                                val hydrated =
                                    if (row.type == "tv") {
                                        val seasons = tvDetails[row.media?.tmdbId ?: -row.id]
                                        if (seasons ==
                                            null
                                        ) {
                                            detailFailures.add(row.id)
                                            row.copy(media = row.media?.copy(seasons = prior?.request?.media?.seasons))
                                        } else {
                                            row.copy(media = row.media?.copy(seasons = seasons))
                                        }
                                    } else {
                                        row
                                    }
                                val fresh = row.id !in detailFailures
                                val downloadSeen =
                                    row.id in _state.value.notifications.downloadObserved || prior?.stage?.downloadObserved == true
                                RequestStatusItem(
                                    hydrated,
                                    if (fresh) {
                                        deriveRequestStage(hydrated, prior?.availabilityDate, downloadSeen)
                                    } else {
                                        prior?.stage
                                            ?: RequestStage(
                                                RequestStageKind.CHECKING,
                                                "Checking",
                                                detail = "Season status couldn't be refreshed",
                                            )
                                    },
                                    prior?.item,
                                    fresh,
                                    prior?.availabilityDate,
                                )
                            }.toMutableList()
                    val confirmedGone = mutableSetOf<Int>()
                    val trackedIds =
                        old.keys + _state.value.notifications.baselined +
                            _state.value.notifications.pending
                                .map { it.requestId }
                    for (id in trackedIds) {
                        if (id in rows) {
                            missing.remove(id)
                            continue
                        }
                        val prior = old[id]
                        if (!complete) {
                            if (prior != null) items.add(prior.copy(fresh = false))
                            continue
                        }
                        try {
                            val detail = s.client.requestApi.requestRequestIdGet(id.toString())
                            if (detail.requestedBy?.id == s.current.config.id) {
                                val fresh = detail.type != "tv" || detail.media?.seasons != null
                                if (!fresh) detailFailures.add(id)
                                items.add(
                                    RequestStatusItem(
                                        detail,
                                        if (fresh) {
                                            deriveRequestStage(
                                                detail,
                                                prior?.availabilityDate,
                                                id in _state.value.notifications.downloadObserved || prior?.stage?.downloadObserved == true,
                                            )
                                        } else {
                                            prior?.stage
                                                ?: RequestStage(RequestStageKind.CHECKING, "Checking")
                                        },
                                        prior?.item,
                                        fresh,
                                        prior?.availabilityDate,
                                    ),
                                )
                                missing.remove(id)
                            }
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            val count = if ((ex as? ClientException)?.statusCode == 404) (missing[id] ?: 0) + 1 else 0
                            missing[id] = count
                            if (count < 2 && prior != null) {
                                items.add(prior.copy(fresh = false))
                            } else if (count >= 2) {
                                confirmedGone.add(id)
                            }
                        }
                    }
                    if (!current(s)) return@withLock false
                    val store = trackEvents(_state.value.notifications, items, s.key, confirmedGone)
                    if (store != _state.value.notifications) keyValue.save(s.key, store)
                    if (!current(s)) return@withLock false
                    _state.value =
                        _state.value.copy(
                            items = items.sortedByDescending { it.request.updatedAt ?: "" },
                            loading = false,
                            lastUpdated = Instant.now(),
                            syncComplete =
                                complete && detailFailures.isEmpty(),
                            error =
                                when {
                                    !complete -> "Showing a limited request history. Refresh to try again."
                                    detailFailures.isNotEmpty() -> "Some season statuses couldn't be refreshed."
                                    else -> null
                                },
                            notifications = store,
                        )
                    Timber.d("Request status sync: %d rows, %d pending updates", items.size, store.pending.size)
                    true
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    if (current(s)) {
                        Timber.w("Request status sync paused: %s", ex.javaClass.simpleName)
                        _state.update {
                            it.copy(
                                loading = false,
                                error = "Requests unavailable. Showing the last update.",
                                syncComplete = false,
                            )
                        }
                        val code = (ex as? ClientException)?.statusCode
                        if (code == 401 || (code == 403 && listedCurrent === s.current)) probeSession(s)
                    }
                    false
                }
            }

        private suspend fun probeSession(s: Session) {
            probeMutex.withLock {
                if (!current(s)) return@withLock
                val now = System.currentTimeMillis()
                if (now - (probeTimes[s.key] ?: 0L) < 900_000L) return@withLock
                probeTimes[s.key] = now
                try {
                    s.client.usersApi.authMeGet()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    if ((ex as? ClientException)?.statusCode in listOf(401, 403) && current(s)) {
                        // The probe has its own clock; the normal connect policy owns the one reconnect.
                        // A refused account can cost two auth strikes (probe + reconnect), then waits 15 minutes.
                        connections.expireAndReconnect(s.current)
                    }
                }
            }
        }

        private fun trackEvents(
            store: RequestNotificationStore,
            items: List<RequestStatusItem>,
            scopeKey: String,
            confirmedGone: Set<Int>,
        ): RequestNotificationStore {
            val baselined = store.baselined.toMutableSet()
            val delivered = store.delivered.toMutableSet()
            // Includes legacy acknowledgements: an already dismissed partial season signature
            // suppresses later partial signatures for this request and quality after an upgrade.
            val deliveredPresentations = delivered.mapTo(mutableSetOf()) { it.requestPresentationKey() }
            val pending = store.pending.toMutableList()
            val unread = store.unread.toMutableSet()
            val seeded = store.seeded.toMutableSet()
            val downloads = store.downloadObserved.toMutableSet()
            for (item in items.filter { it.fresh }) {
                val id = item.request.id
                if (item.stage.terminal) {
                    downloads.remove(id)
                } else if (item.stage.downloadObserved) {
                    downloads.add(id)
                }
                val event = item.eventKey()
                val presentation = event?.requestPresentationKey()
                if ((!store.initialized || id in store.awaitingBaseline) && id !in seeded) {
                    if (event != null) {
                        delivered.add(event)
                        deliveredPresentations.add(event.requestPresentationKey())
                    }
                } else if (event != null && presentation != null) {
                    if (presentation in deliveredPresentations) {
                        pending.removeAll { it.presentationKey() == presentation }
                    } else {
                        val existing = pending.indexOfFirst { it.presentationKey() == presentation }
                        if (existing >= 0) {
                            // Keep one pending milestone current without restarting an on-screen chip.
                            pending[existing] = RequestEvent(event, id, scopeKey)
                        } else {
                            pending.removeAll { it.requestId == id }
                            if (store.enabled) {
                                pending.add(RequestEvent(event, id, scopeKey))
                            } else {
                                delivered.add(event)
                                deliveredPresentations.add(presentation)
                            }
                            if (item.stage.ready && !myRequestsVisible) unread.add(id)
                        }
                    }
                }
                baselined.add(id)
                seeded.remove(id)
            }
            val byId = items.associateBy { it.request.id }
            pending.removeAll { event ->
                byId[event.requestId]?.let {
                    it.fresh &&
                        it.eventKey()?.requestPresentationKey() != event.presentationKey()
                } ==
                    true ||
                    event.requestId in confirmedGone
            }
            return store.copy(
                initialized = true,
                awaitingBaseline =
                    (store.awaitingBaseline + if (!store.initialized) items.map { it.request.id } else emptyList())
                        .filterNot { id ->
                            items.any {
                                it.request.id ==
                                    id &&
                                    it.fresh
                            }
                        }.toSet() -
                        confirmedGone,
                baselined = baselined - confirmedGone,
                seeded = seeded - confirmedGone,
                delivered = delivered,
                pending = pending,
                unread =
                    unread
                        .filter { id ->
                            id !in
                                confirmedGone &&
                                byId[id]?.let { !it.fresh || it.stage.ready } != false
                        }.toSet(),
                downloadObserved = downloads - confirmedGone,
            )
        }

        suspend fun seed(
            request: MediaRequest,
            expectedScope: String?,
            metadata: com.github.damontecres.wholphin.data.model.DiscoverItem? = null,
            updated: Boolean = false,
        ) = mutex.withLock {
            val s = session ?: return@withLock
            if (!current(s) || s.key != expectedScope || request.requestedBy?.id != s.current.config.id) return@withLock
            val stage = deriveRequestStage(request)
            val store =
                _state.value.notifications.let {
                    it.copy(
                        seeded = it.seeded + request.id,
                        baselined = it.baselined - request.id,
                        downloadObserved =
                            if (stage.downloadObserved) {
                                it.downloadObserved +
                                    request.id
                            } else {
                                it.downloadObserved
                            },
                    )
                }
            keyValue.save(s.key, store)
            if (current(s)) {
                val item = RequestStatusItem(request, stage, metadata)
                val submission = RequestSubmission(item, updated)
                _state.update {
                    it.copy(
                        items = listOf(item) + it.items.filterNot { it.request.id == request.id },
                        notifications = store,
                        submission = submission,
                    )
                }
            }
            progress.refresh()
            refresh()
        }

        fun clearSubmission(submission: RequestSubmission) {
            _state.update { if (it.submission === submission) it.copy(submission = null) else it }
        }

        /** Resolve a missing library link only on Watch now, retaining the captured account and quality. */
        suspend fun libraryItemId(
            id: Int,
            expectedScope: String?,
        ): java.util.UUID? {
            val (s, row) =
                mutex.withLock {
                    val s = session ?: return null
                    val row = _state.value.items.firstOrNull { it.request.id == id } ?: return null
                    if (!current(s) || s.key != expectedScope || row.request.requestedBy?.id != s.current.config.id) return null
                    s to row
                }
            row.jellyfinId?.let { return if (current(s)) it else null }
            val tmdb = row.request.media?.tmdbId ?: return null
            val media =
                if (row.request.type == "tv") {
                    val details = s.client.tvApi.tvTvIdGet(tmdb)
                    if (details.id != tmdb) return null
                    details.mediaInfo
                } else {
                    val details = s.client.moviesApi.movieMovieIdGet(tmdb)
                    if (details.id != tmdb) return null
                    details.mediaInfo
                }
            if (!current(s)) return null
            val latest = _state.value.items.firstOrNull { it.request.id == id } ?: return null
            if (latest.request.requestedBy?.id != row.request.requestedBy?.id ||
                latest.request.media?.tmdbId != tmdb || latest.request.type != row.request.type ||
                (latest.request.is4k == true) != (row.request.is4k == true)
            ) {
                return null
            }
            return media?.jellyfinId(row.request.is4k == true)
        }

        fun loadMetadata(id: Int) {
            scope.launch {
                val s = session ?: return@launch
                val row = _state.value.items.firstOrNull { it.request.id == id } ?: return@launch
                val tmdb = row.request.media?.tmdbId ?: return@launch
                if ((row.item != null && metadataCache.containsKey(row.request.type to tmdb)) || !metadataInFlight.add(id)) return@launch
                try {
                    val cacheKey = row.request.type to tmdb
                    val metadata =
                        metadataCache[cacheKey] ?: if (row.request.type == "tv") {
                            service.createDiscoverItem(s.client.tvApi.tvTvIdGet(tmdb)) to null
                        } else {
                            val movie = s.client.moviesApi.movieMovieIdGet(tmdb)
                            val regions = movie.releases?.results.orEmpty()
                            val region =
                                regions.firstOrNull {
                                    it.iso31661 ==
                                        java.util.Locale
                                            .getDefault()
                                            .country
                                }
                                    ?: regions.firstOrNull { it.iso31661 == "US" } ?: regions.firstOrNull()
                            val date =
                                region
                                    ?.releaseDates
                                    .orEmpty()
                                    .filter { it.type in listOf(4, 5) }
                                    .mapNotNull { runCatching { java.time.LocalDate.parse(it.releaseDate?.take(10)) }.getOrNull() }
                                    .minOrNull()
                            val item = service.createDiscoverItem(movie)
                            item to (date ?: item.releaseDate)
                        }
                    val (item, availabilityDate) = metadata
                    if (current(s)) metadataCache[cacheKey] = metadata
                    mutex.withLock {
                        if (current(s)) {
                            _state.update { st ->
                                st.copy(
                                    items =
                                        st.items.map {
                                            if (it.request.id ==
                                                id
                                            ) {
                                                it.copy(
                                                    item = item,
                                                    availabilityDate = availabilityDate,
                                                    stage =
                                                        if (it.fresh) {
                                                            deriveRequestStage(
                                                                it.request,
                                                                availabilityDate,
                                                                it.stage.downloadObserved || id in st.notifications.downloadObserved,
                                                            )
                                                        } else {
                                                            it.stage
                                                        },
                                                )
                                            } else {
                                                it
                                            }
                                        },
                                )
                            }
                        }
                    }
                } catch (
                    ex: CancellationException,
                ) {
                    throw ex
                } catch (
                    _: Exception,
                ) {
                    // A missing title never hides a request or stops another row.
                } finally {
                    metadataInFlight.remove(id)
                }
            }
        }

        fun markOpened(requestId: Int? = null) =
            changeStore {
                it.copy(
                    unread =
                        if (requestId ==
                            null
                        ) {
                            emptySet()
                        } else {
                            it.unread - requestId
                        },
                )
            }

        fun setNotificationsEnabled(enabled: Boolean) =
            changeStore {
                it.copy(
                    enabled = enabled,
                    delivered =
                        if (enabled) {
                            it.delivered
                        } else {
                            it.delivered +
                                it.pending.map { e ->
                                    e.key
                                }
                        },
                    pending = if (enabled) it.pending else emptyList(),
                )
            }

        fun delivered(events: List<RequestEvent>) =
            changeStore(events.firstOrNull()?.scopeKey) { store ->
                val presentations = events.mapTo(mutableSetOf()) { it.presentationKey() }
                val acknowledged = events.map { it.key } + store.pending.filter { it.presentationKey() in presentations }.map { it.key }
                store.copy(
                    delivered = store.delivered + acknowledged,
                    pending =
                        store.pending.filterNot {
                            it.presentationKey() in
                                presentations
                        },
                )
            }

        private suspend fun readStore(key: String): RequestNotificationStore =
            try {
                keyValue.get(key, RequestNotificationStore()).first()
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                RequestNotificationStore()
            }

        private fun changeStore(
            requestedKey: String? = null,
            update: (RequestNotificationStore) -> RequestNotificationStore,
        ): Job {
            val key = requestedKey ?: session?.key ?: _state.value.scope ?: return Job().apply { complete() }
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                mutex.withLock {
                    val live = session?.takeIf { it.key == key && current(it) }
                    val base = if (live != null) _state.value.notifications else readStore(key)
                    val next = update(base)
                    if (next != base) keyValue.save(key, next)
                    if (live != null && current(live)) _state.update { it.copy(notifications = next) }
                }
            }
        }

        private suspend fun forgetLocked(
            id: Int,
            key: String,
        ) {
            val live = session?.takeIf { it.key == key && current(it) }
            val base = if (live != null) _state.value.notifications else readStore(key)
            val next =
                base.copy(
                    baselined = base.baselined - id,
                    seeded = base.seeded - id,
                    downloadObserved = base.downloadObserved - id,
                    awaitingBaseline = base.awaitingBaseline - id,
                    unread = base.unread - id,
                    pending = base.pending.filterNot { it.requestId == id },
                )
            keyValue.save(key, next)
            if (live != null && current(live)) {
                missing.remove(id)
                progress.forget(id)
                progress.refresh()
                _state.update { it.copy(items = it.items.filterNot { row -> row.request.id == id }, notifications = next) }
            }
        }

        suspend fun forget(
            id: Int,
            expectedScope: String?,
        ) = mutex.withLock {
            if (expectedScope != null) forgetLocked(id, expectedScope)
            refresh()
        }

        suspend fun cancel(id: Int) =
            mutex.withLock {
                val s = session ?: return@withLock
                val item = _state.value.items.firstOrNull { it.request.id == id } ?: return@withLock
                if (!current(s) || !canCancel(item.request)) return@withLock
                s.client.requestApi.requestRequestIdDelete(id.toString())
                forgetLocked(id, s.key)
                refresh()
            }

        fun canCancel(request: MediaRequest): Boolean {
            val user = session?.current?.config ?: return false
            return request.requestedBy?.id == user.id && request.status == 1 &&
                (user.hasPermission(SeerrPermission.REQUEST) || user.hasPermission(SeerrPermission.MANAGE_REQUESTS))
        }
    }
