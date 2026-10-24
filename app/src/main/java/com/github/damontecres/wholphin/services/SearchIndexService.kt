package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.kids.KidContentGuard
import com.github.damontecres.wholphin.util.FuzzySearch
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * The titles Search matches fuzzily ([FuzzySearch]): every movie, show and collection the member
 * can see, one index per server and member, holding only names (owner, 2026-10-16). About 12,000
 * titles take a few seconds to fetch, so the index is kept in memory and in the app's cache
 * directory. A saved copy is used straight away and refreshed in the background once it's older
 * than [REFRESH_AFTER]. Search warms it when it opens and never waits for it.
 */
@Singleton
class SearchIndexService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val kidContentGuard: KidContentGuard,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }
        private val lock = Mutex()

        /** A member's titles of [types], fetched at [savedAt]. */
        class Index(
            val serverId: UUID,
            val userId: UUID,
            val types: Set<BaseItemKind>,
            val savedAt: Long,
            val entries: List<FuzzySearch.Entry>,
        ) {
            /** Whether this is the member's index and has their titles of [types]. */
            fun holds(
                serverId: UUID,
                userId: UUID,
                types: Collection<BaseItemKind>,
            ): Boolean = this.serverId == serverId && this.userId == userId && this.types.containsAll(types)
        }

        @Serializable
        private data class SavedTitle(
            @Serializable(UUIDSerializer::class) val id: UUID,
            val name: String,
            val type: BaseItemKind,
            val year: Int? = null,
        )

        /** Keys are worked out again on reading, so a saved index follows any change to [FuzzySearch.searchKey]. */
        @Serializable
        private data class SavedIndex(
            val savedAt: Long,
            val types: Set<BaseItemKind>,
            val titles: List<SavedTitle>,
        )

        private val _index = MutableStateFlow<Index?>(null)

        /** The index for the member Search last warmed it for, once it's ready. */
        val index: StateFlow<Index?> = _index

        /** Whose titles, and of which types, the index holds or a fetch is for. */
        private data class IndexKey(
            val serverId: UUID,
            val userId: UUID,
            val types: Set<BaseItemKind>,
        ) {
            fun holds(other: IndexKey) = serverId == other.serverId && userId == other.userId && types.containsAll(other.types)
        }

        /** The member Search last warmed the index for, as (server, user). */
        private var member: Pair<UUID, UUID>? = null
        private var refresh: Job? = null
        private var refreshing: IndexKey? = null

        /**
         * Make the member's index of [types] ready: load the saved copy if it isn't in memory, and fetch
         * it again in the background when there's none, it's older than [REFRESH_AFTER] or it lacks
         * one of [types].
         */
        fun warm(
            serverId: UUID,
            userId: UUID,
            types: Set<BaseItemKind>,
        ) {
            if (types.isEmpty()) return
            val wanted = IndexKey(serverId, userId, types)
            scope.launch {
                lock.withLock {
                    member = serverId to userId
                    val current =
                        _index.value?.takeIf { it.serverId == serverId && it.userId == userId }
                            ?: readSaved(serverId, userId).also { _index.value = it }
                    if (current != null &&
                        current.holds(serverId, userId, types) &&
                        age(current.savedAt) < REFRESH_AFTER.inWholeMilliseconds
                    ) {
                        return@withLock
                    }
                    // A fetch already under way for these types will do
                    if (refresh?.isActive == true && refreshing?.holds(wanted) == true) return@withLock
                    refresh?.cancel()
                    refreshing = wanted
                    refresh = scope.launch { fetchAndSave(wanted) }
                }
            }
        }

        private suspend fun fetchAndSave(wanted: IndexKey) {
            try {
                val started = System.currentTimeMillis()
                // Kid content rules (review round 1): built only from a response made for this member's session, and
                // kept only while it is still the current one
                val session = kidContentGuard.sessionOf(api)
                if (session != null && session.userId != wanted.userId) return
                val response =
                    GetItemsRequestHandler
                        .execute(
                            api,
                            GetItemsRequest(
                                userId = wanted.userId,
                                recursive = true,
                                includeItemTypes = wanted.types.toList(),
                                enableImages = false,
                                enableUserData = false,
                                enableTotalRecordCount = false,
                                sortBy = listOf(ItemSortBy.SORT_NAME),
                            ),
                        )
                if (session != null && !session.made(kidContentGuard.sessionOf(response.headers))) return
                val items = response.content.items
                val titles =
                    items.mapNotNull { item ->
                        val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        SavedTitle(item.id, name, item.type, item.productionYear)
                    }
                val saved = SavedIndex(System.currentTimeMillis(), wanted.types, titles)
                val index = toIndex(wanted.serverId, wanted.userId, saved)
                if (session != null && kidContentGuard.sessionOf(api) != session) return
                lock.withLock {
                    // Only if Search hasn't moved on to another member meanwhile
                    if (member == wanted.serverId to wanted.userId) _index.value = index
                }
                Timber.d("Search index: %s titles in %s ms", titles.size, System.currentTimeMillis() - started)
                runCatching {
                    file(wanted.serverId, wanted.userId)
                        .apply { parentFile?.mkdirs() }
                        .writeText(json.encodeToString(saved))
                }.onFailure { Timber.w(it, "Couldn't save the search index") }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Search carries on with the server's results alone
                Timber.w(ex, "Couldn't fetch the search index")
            }
        }

        private fun readSaved(
            serverId: UUID,
            userId: UUID,
        ): Index? =
            runCatching {
                file(serverId, userId)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.let { json.decodeFromString<SavedIndex>(it) }
                    ?.let { toIndex(serverId, userId, it) }
            }.onFailure { Timber.w(it, "Couldn't read the search index") }
                .getOrNull()

        private fun toIndex(
            serverId: UUID,
            userId: UUID,
            saved: SavedIndex,
        ) = Index(
            serverId = serverId,
            userId = userId,
            types = saved.types,
            savedAt = saved.savedAt,
            entries = saved.titles.map { FuzzySearch.Entry(it.id, it.name, it.type, it.year) },
        )

        private fun age(time: Long) = System.currentTimeMillis() - time

        private fun file(
            serverId: UUID,
            userId: UUID,
        ) = File(context.cacheDir, "search-index/${serverId}_$userId.json")

        companion object {
            private val REFRESH_AFTER = 6.hours
        }
    }
