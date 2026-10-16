package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Streaming collection lists without waiting on the server.
 *
 * Each collection's members are fetched once, newest first, in pages (a snapshot). The first page
 * is shown as soon as it arrives and the rest fill in behind it. The finished snapshot is kept in
 * memory for the collections in use and saved to the app's cache directory, so it survives app
 * restarts. NEW, Popular, Trending and Top 10, and the ALL / MOVIES / SHOWS
 * filters, are then built from it instantly. Popular, Trending and Top 10 follow the server's saved
 * lists ([JustWatchRowService.serviceList]), or JustWatch's rankings matched here when it has none.
 * Snapshots older than [REFRESH_AFTER] are shown and refreshed in the background for next time;
 * older than [MAX_AGE] they're fetched again first.
 *
 * Work runs in this service's own scope and is shared, so leaving a page or moving focus never
 * throws away a load, and asking again joins the load already running.
 */
@Singleton
class StreamingQuickListService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val justWatchService: JustWatchService,
        private val justWatchRowService: JustWatchRowService,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class SavedSnapshot(
            val savedAt: Long,
            val items: List<BaseItemDto>,
        )

        private class Snapshot(
            val savedAt: Long,
            val items: List<BaseItem>,
        ) {
            /** Members by "m:<tmdb id>" (movies) or "t:<tmdb id>" (shows). */
            val byTmdbId: Map<String, BaseItem> =
                items
                    .mapNotNull { item ->
                        val tmdbId = item.data.providerIds?.get("Tmdb") ?: return@mapNotNull null
                        (if (item.type == BaseItemKind.MOVIE) "m:" else "t:") + tmdbId to item
                    }.toMap()

            val byId: Map<UUID, BaseItem> = items.associateBy { it.id }
        }

        private data class Key(
            val userId: UUID,
            val collectionId: UUID,
        )

        private val lock = Mutex()

        /** Newest-first members loaded so far, and whether that's all of them. */
        data class Progress(
            val items: List<BaseItem>,
            val complete: Boolean,
        )

        private class Load(
            val progress: MutableStateFlow<Progress>,
            val result: Deferred<Snapshot>,
        )

        /** The few snapshots in use, least recently used first. */
        private val inMemory = LinkedHashMap<Key, Load>(MEMORY_SNAPSHOTS, .75f, true)

        /** Collections loading on screen; background prefetching waits for these. */
        private val foregroundLoads = MutableStateFlow(0)

        /** The collection's members newest first, as pages arrive, ending with the complete list. */
        fun newest(
            userId: UUID,
            collectionId: UUID,
        ): Flow<Progress> =
            flow {
                foregroundLoads.update { it + 1 }
                try {
                    emitAll(
                        snapshot(Key(userId, collectionId)).progress.transformWhile {
                            emit(it)
                            !it.complete
                        },
                    )
                } finally {
                    foregroundLoads.update { it - 1 }
                }
            }

        /** [list] for the collection, honoring [filter]'s type selection (see [canFilterLocally]). */
        suspend fun items(
            userId: UUID,
            collectionId: UUID,
            packages: List<String>?,
            list: StreamingQuickList,
            filter: GetItemsFilter,
        ): List<BaseItem> {
            val savedJob =
                packages
                    ?.takeIf { list != StreamingQuickList.NEW }
                    ?.let { scope.async { saved(userId, it, list) } }
            val snapshot =
                try {
                    foregroundLoads.update { it + 1 }
                    snapshot(Key(userId, collectionId)).result.await()
                } finally {
                    foregroundLoads.update { it - 1 }
                }
            val members =
                if (packages == null || savedJob == null) {
                    snapshot.items
                } else {
                    val ranked =
                        savedJob.await()?.mapNotNull { snapshot.byId[it] }
                            ?: justWatchService
                                .ranked(packages, list)
                                .mapNotNull { snapshot.byTmdbId[(if (it.isMovie) "m:" else "t:") + it.tmdbId] }
                    ranked.distinctBy { it.id }
                }
            val types = filter.includeItemTypes
            val filtered = if (types.isNullOrEmpty()) members else members.filter { it.type in types }
            return if (list == StreamingQuickList.TOP_10) filtered.take(10) else filtered
        }

        /** Whether [filter] only chooses types, so [items] can apply it without the server. */
        fun canFilterLocally(filter: GetItemsFilter): Boolean = filter.copy(includeItemTypes = null) == GetItemsFilter()

        /**
         * Read the server's lists for [packages] ahead of time, or load JustWatch's rankings when it has
         * none; the snapshot loads with the page.
         */
        fun prefetch(
            userId: UUID,
            packages: List<String>?,
        ) {
            packages ?: return
            scope.launch {
                if (saved(userId, packages, StreamingQuickList.POPULAR) != null) return@launch
                StreamingQuickList.entries
                    .filter { it != StreamingQuickList.NEW }
                    .forEach { list -> launch { runCatching { justWatchService.ranked(packages, list) } } }
            }
        }

        private suspend fun saved(
            userId: UUID,
            packages: List<String>,
            list: StreamingQuickList,
        ): List<UUID>? =
            runCatching { justWatchRowService.serviceList(userId, packages.first(), list) }
                .onFailure { Timber.w(it, "Couldn't read the saved %s list", list) }
                .getOrNull()

        /**
         * Save snapshots for [collectionIds] without one from the last [PREFETCH_REFRESH_AFTER],
         * one at a time after a short pause, so opening a streaming collection from Home is instant.
         * It pauses between pages while a collection is loading on screen.
         */
        fun prefetchAll(
            userId: UUID,
            collectionIds: List<UUID>,
        ) {
            scope.launch {
                delay(PREFETCH_DELAY)
                collectionIds.forEach { collectionId ->
                    val key = Key(userId, collectionId)
                    val savedAge = savedAge(key)
                    val loaded = lock.withLock { inMemory[key] } != null
                    if (!loaded && (savedAge == null || savedAge > PREFETCH_REFRESH_AFTER.inWholeMilliseconds)) {
                        runCatching { fetchAndSave(key, progress = null, background = true) }
                            .onFailure { Timber.w(it, "Prefetch failed for %s", collectionId) }
                    }
                }
            }
        }

        private suspend fun snapshot(key: Key): Load =
            lock.withLock {
                inMemory[key]?.let { existing ->
                    val result = existing.result
                    val failed = result.isCompleted && result.getCompletionExceptionOrNull() != null
                    val expired = result.isCompleted && !failed && age(result.getCompleted().savedAt) > MAX_AGE.inWholeMilliseconds
                    if (!failed && !expired) return@withLock existing
                }
                val progress = MutableStateFlow(Progress(emptyList(), false))
                Load(progress, scope.async { load(key, progress) }).also {
                    inMemory[key] = it
                    while (inMemory.size > MEMORY_SNAPSHOTS) inMemory.remove(inMemory.keys.first())
                }
            }

        private suspend fun load(
            key: Key,
            progress: MutableStateFlow<Progress>,
        ): Snapshot =
            try {
                val saved = readSaved(key)
                if (saved != null && age(saved.savedAt) < MAX_AGE.inWholeMilliseconds) {
                    if (age(saved.savedAt) > REFRESH_AFTER.inWholeMilliseconds) {
                        // Show what's saved now and refresh it for next time
                        scope.launch { runCatching { fetchAndSave(key, progress = null, background = true) } }
                    }
                    progress.value = Progress(saved.items, true)
                    saved
                } else {
                    fetchAndSave(key, progress, background = false)
                }
            } catch (ex: Exception) {
                // Let anything waiting on the progress finish
                progress.value = Progress(progress.value.items, true)
                throw ex
            }

        /**
         * Fetch the members newest first: a small first page (shown straight away when
         * [progress] is set), then the rest in larger pages, two at a time.
         */
        private suspend fun fetchAndSave(
            key: Key,
            progress: MutableStateFlow<Progress>?,
            background: Boolean,
        ): Snapshot {
            val started = System.currentTimeMillis()

            suspend fun page(
                startIndex: Int,
                limit: Int,
                count: Boolean,
            ) = GetItemsRequestHandler
                .execute(
                    api,
                    GetItemsRequest(
                        userId = key.userId,
                        parentId = key.collectionId,
                        includeItemTypes = StreamingCollections.types,
                        recursive = false,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
                        fields = SlimItemFields + ItemFields.PROVIDER_IDS,
                        startIndex = startIndex,
                        limit = limit,
                        enableTotalRecordCount = count,
                    ),
                ).content

            val first = page(0, FIRST_PAGE, count = true)
            val dtos = first.items.toMutableList()
            progress?.value = Progress(dtos.map { BaseItem(it, true) }, dtos.size >= first.totalRecordCount)
            val pageStarts = (FIRST_PAGE until first.totalRecordCount step PAGE).toList()
            val permits = Semaphore(PARALLEL_PAGES)
            coroutineScope {
                val pages =
                    pageStarts.map { start ->
                        async {
                            permits.withPermit {
                                // Let a collection being opened go first
                                if (background) foregroundLoads.first { it == 0 }
                                page(start, PAGE, count = false).items
                            }
                        }
                    }
                // Add pages in order as they arrive
                pages.forEachIndexed { index, page ->
                    dtos += page.await()
                    progress?.value = Progress(dtos.map { BaseItem(it, true) }, index == pages.lastIndex)
                }
            }
            val items = dtos.distinctBy { it.id }
            Timber.d("Snapshot for %s: %s items in %s ms", key.collectionId, items.size, System.currentTimeMillis() - started)
            val now = System.currentTimeMillis()
            runCatching {
                file(key).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(SavedSnapshot(now, items)))
            }.onFailure { Timber.w(it, "Couldn't save snapshot for %s", key.collectionId) }
            val snapshot = Snapshot(now, items.map { BaseItem(it, true) })
            progress?.value = Progress(snapshot.items, true)
            return snapshot
        }

        private fun readSaved(key: Key): Snapshot? =
            runCatching {
                file(key)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.let { json.decodeFromString<SavedSnapshot>(it) }
                    ?.let { saved -> Snapshot(saved.savedAt, saved.items.map { BaseItem(it, true) }) }
            }.onFailure { Timber.w(it, "Couldn't read snapshot for %s", key.collectionId) }
                .getOrNull()

        private fun savedAge(key: Key): Long? = file(key).takeIf { it.exists() }?.lastModified()?.let(::age)

        private fun age(time: Long) = System.currentTimeMillis() - time

        private fun file(key: Key) = File(context.cacheDir, "streaming-collections/${key.userId}_${key.collectionId}.json")

        companion object {
            private const val MEMORY_SNAPSHOTS = 3

            /** The first page: about what the page showed before, so it arrives as quickly. */
            private const val FIRST_PAGE = 60
            private const val PAGE = 300
            private const val PARALLEL_PAGES = 2
            private val REFRESH_AFTER = 30.minutes
            private val MAX_AGE = 24.hours

            /** Home's background prefetch only refreshes lists older than this, to spare the server. */
            private val PREFETCH_REFRESH_AFTER = 6.hours
            private val PREFETCH_DELAY = 5.seconds
        }
    }
