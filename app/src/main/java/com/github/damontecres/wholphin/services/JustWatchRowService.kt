package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JustWatchHomeList
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * Home's JustWatch rows, the Movies and TV Shows Recommended tabs' Top 10 rows for each streaming
 * service, and the streaming service pages' POPULAR / TRENDING / TOP 10 lists: each ranking's titles
 * that are on the server, in ranked order.
 *
 * The server works the lists out: `justwatch-home.sh` on the media server fetches JustWatch's
 * rankings once a day, matches them to the library every 10 minutes, and saves each list's item IDs
 * in every member's display preferences ([PREFS_ID], [PREFS_CLIENT]; one key per list, named after it
 * in lower case). The service pages' lists are kept apart in [STREAMING_PREFS_ID], so Home's read
 * stays small. The app reads them once per Home load and fetches the items as the member, so
 * anything they can't see drops out.
 */
@Singleton
class JustWatchRowService
    @Inject
    constructor(
        private val api: ApiClient,
        private val displayPreferencesService: DisplayPreferencesService,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lists = ConcurrentHashMap<Pair<UUID, String>, Pair<Long, Deferred<Map<String, String?>>>>()

        /** The first [JustWatchHomeList.size] titles ranked by [list] that the member can watch. */
        suspend fun items(
            userId: UUID,
            list: JustWatchHomeList,
        ): List<BaseItem> = ranked(userId, list.name.lowercase(), list.size)

        /**
         * A streaming service's top 10 movies or shows this week: the highest-ranked titles on JustWatch's
         * weekly chart for it that are in Movies or TV Shows. The server lists the chart 100 deep, so a row
         * fills up from further down when some of the top 10 aren't on the server. [code] is the service's
         * first JustWatch package, see [StreamingCollections.justWatchPackages].
         */
        suspend fun serviceTop10(
            userId: UUID,
            code: String,
            movies: Boolean,
        ): List<BaseItem> = ranked(userId, "top_10_${if (movies) "movies" else "shows"}_$code", 10)

        /**
         * A streaming service page's POPULAR, TRENDING or TOP 10 list: the IDs of the service collection's
         * titles in ranked order (TOP 10 holds its first 10 movies and 10 shows), or null when the server
         * hasn't saved one. [code] is the service's first JustWatch package.
         */
        suspend fun serviceList(
            userId: UUID,
            code: String,
            list: StreamingQuickList,
        ): List<UUID>? =
            lists(userId, STREAMING_PREFS_ID)
                .await()["service_${list.name.lowercase()}_$code"]
                ?.split(',')
                ?.mapNotNull { it.toUUIDOrNull() }

        private suspend fun ranked(
            userId: UUID,
            key: String,
            size: Int,
        ): List<BaseItem> {
            val ids =
                lists(userId, PREFS_ID)
                    .await()[key]
                    .orEmpty()
                    .split(',')
                    .mapNotNull { it.toUUIDOrNull() }
            if (ids.isEmpty()) return emptyList()
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
            return ids.mapNotNull { items[it] }.take(size).map { BaseItem(it, true) }
        }

        /** The member's saved lists in [prefsId], read once for all of Home's rows or a service page. */
        private fun lists(
            userId: UUID,
            prefsId: String,
        ): Deferred<Map<String, String?>> =
            lists
                .compute(userId to prefsId) { _, current ->
                    // A failed read counts as cancelled, so it's tried again
                    current?.takeIf { (readAt, saved) ->
                        System.currentTimeMillis() - readAt < REUSE_FOR.inWholeMilliseconds && !saved.isCancelled
                    } ?: (
                        System.currentTimeMillis() to
                            scope.async {
                                displayPreferencesService.getDisplayPreferences(userId, prefsId, PREFS_CLIENT).customPrefs
                            }
                    )
                }!!
                .second

        companion object {
            const val PREFS_ID = "justwatch-home"
            const val STREAMING_PREFS_ID = "justwatch-streaming"
            const val PREFS_CLIENT = "WeaselPlex"
            private val REUSE_FOR = 5.minutes
        }
    }
