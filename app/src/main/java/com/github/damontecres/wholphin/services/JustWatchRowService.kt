package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JustWatchHomeList
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * Home's JustWatch rows: each ranking's titles that are on the server, in ranked order.
 *
 * Titles are matched by their TMDb ID, using an index of the member's movies and shows. The index
 * is saved in the app's cache directory, used straight away, and rebuilt in the background once
 * it's older than [INDEX_REFRESH_AFTER], so newly added titles join the rows within hours.
 */
@Singleton
class JustWatchRowService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val justWatchService: JustWatchService,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }

        /** Item IDs by "m:<tmdb id>" (movies) or "t:<tmdb id>" (shows). */
        @Serializable
        private data class Index(
            val savedAt: Long,
            val ids: Map<String, String>,
        )

        private val indexes = ConcurrentHashMap<UUID, Index>()
        private val builds = ConcurrentHashMap<UUID, Deferred<Index>>()
        private val readLock = Mutex()

        /** The first [JustWatchHomeList.size] titles ranked by [list] that the member can watch. */
        suspend fun items(
            userId: UUID,
            list: JustWatchHomeList,
        ): List<BaseItem> =
            coroutineScope {
                val ranked = async { justWatchService.homeList(list) }
                val index = index(userId)
                val ids =
                    ranked
                        .await()
                        .mapNotNull { index.ids[key(it.isMovie, it.tmdbId)] }
                        .distinct()
                        .take(list.size)
                        .map(UUID::fromString)
                if (ids.isEmpty()) return@coroutineScope emptyList()
                val items =
                    GetItemsRequestHandler
                        .execute(
                            api,
                            GetItemsRequest(
                                userId = userId,
                                ids = ids,
                                fields = HomeItemFields,
                                enableTotalRecordCount = false,
                            ),
                        ).content.items
                        .associateBy { it.id }
                // The server returns them in its own order, so put them back in ranked order
                ids.mapNotNull { items[it] }.map { BaseItem(it, true) }
            }

        private suspend fun index(userId: UUID): Index {
            // Home's rows load together, so read the saved index once
            val current = indexes[userId] ?: readLock.withLock { indexes[userId] ?: readSaved(userId)?.also { indexes[userId] = it } }
            if (current != null) {
                if (System.currentTimeMillis() - current.savedAt > INDEX_REFRESH_AFTER.inWholeMilliseconds) build(userId)
                return current
            }
            return build(userId).await()
        }

        /** Rebuild the member's index, sharing a build that's already running. */
        private fun build(userId: UUID): Deferred<Index> =
            builds.compute(userId) { _, running ->
                running?.takeIf { it.isActive } ?: scope.async {
                    try {
                        fetchIndex(userId).also { index ->
                            indexes[userId] = index
                            runCatching {
                                file(userId).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(index))
                            }.onFailure { Timber.w(it, "Couldn't save the JustWatch index") }
                        }
                    } catch (ex: Exception) {
                        Timber.w(ex, "Couldn't build the JustWatch index")
                        throw ex
                    }
                }
            }!!

        /** Every movie and show the member can see, in pages, two at a time. */
        private suspend fun fetchIndex(userId: UUID): Index =
            coroutineScope {
                val started = System.currentTimeMillis()

                fun request(
                    startIndex: Int,
                    count: Boolean,
                ) = GetItemsRequest(
                    userId = userId,
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    fields = listOf(ItemFields.PROVIDER_IDS),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    enableImages = false,
                    enableUserData = false,
                    startIndex = startIndex,
                    limit = INDEX_PAGE,
                    enableTotalRecordCount = count,
                )
                val first = GetItemsRequestHandler.execute(api, request(0, true)).content
                val permits = Semaphore(2)
                val rest =
                    (INDEX_PAGE until first.totalRecordCount step INDEX_PAGE)
                        .map { start ->
                            async { permits.withPermit { GetItemsRequestHandler.execute(api, request(start, false)).content.items } }
                        }.awaitAll()
                        .flatten()
                val ids = mutableMapOf<String, String>()
                (first.items + rest).forEach { item ->
                    val tmdbId = item.providerIds?.get("Tmdb") ?: return@forEach
                    ids.putIfAbsent(key(item.type == BaseItemKind.MOVIE, tmdbId), item.id.toString())
                }
                Timber.d("JustWatch index: %s titles in %s ms", ids.size, System.currentTimeMillis() - started)
                Index(System.currentTimeMillis(), ids)
            }

        private fun readSaved(userId: UUID): Index? =
            runCatching {
                file(userId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Index>(it) }
            }.onFailure { Timber.w(it, "Couldn't read the JustWatch index") }
                .getOrNull()

        private fun file(userId: UUID) = File(context.cacheDir, "justwatch/index_$userId.json")

        private fun key(
            isMovie: Boolean,
            tmdbId: String,
        ) = (if (isMovie) "m:" else "t:") + tmdbId

        companion object {
            private const val INDEX_PAGE = 2000
            private val INDEX_REFRESH_AFTER = 6.hours
        }
    }
