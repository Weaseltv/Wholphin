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
 * Home's JustWatch rows, and the Movies and TV Shows Recommended tabs' Top 10 rows for each streaming
 * service: each ranking's titles that are on the server, in ranked order.
 *
 * The server works the lists out: `justwatch-home.sh` on the media server fetches JustWatch's
 * rankings once a day, matches them to the library every hour, and saves each list's item IDs in
 * every member's display preferences ([PREFS_ID], [PREFS_CLIENT]; one key per list, named after it
 * in lower case). The app reads them once per Home load and fetches the items as the member, so
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
        private val lists = ConcurrentHashMap<UUID, Pair<Long, Deferred<Map<String, String?>>>>()

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

        private suspend fun ranked(
            userId: UUID,
            key: String,
            size: Int,
        ): List<BaseItem> {
            val ids =
                lists(userId)
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

        /** The member's saved lists, read once for all of Home's rows. */
        private fun lists(userId: UUID): Deferred<Map<String, String?>> =
            lists
                .compute(userId) { _, current ->
                    // A failed read counts as cancelled, so it's tried again
                    current?.takeIf { (readAt, saved) ->
                        System.currentTimeMillis() - readAt < REUSE_FOR.inWholeMilliseconds && !saved.isCancelled
                    } ?: (
                        System.currentTimeMillis() to
                            scope.async {
                                displayPreferencesService.getDisplayPreferences(userId, PREFS_ID, PREFS_CLIENT).customPrefs
                            }
                    )
                }!!
                .second

        companion object {
            const val PREFS_ID = "justwatch-home"
            const val PREFS_CLIENT = "WeaselPlex"
            private val REUSE_FOR = 5.minutes
        }
    }
