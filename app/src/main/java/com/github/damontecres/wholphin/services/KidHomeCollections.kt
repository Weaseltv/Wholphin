package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * A kids profile's Home (kid-home-rows spec v1, 2026-10-23), built from the server like Streaming
 * Services so every app shows the same rows: the combined Next Up & Continue Watching row, Watch
 * Again, then one row per collection tagged [TAG] in the server's sort-name order. The server owns
 * the themed rows' names, contents and order (reshuffled daily, so children are never sorted here);
 * the app knows only the tag. Nothing here is saved.
 */
object KidHomeCollections {
    const val TAG = "WeaselPlex Kids"
    const val WATCH_AGAIN_LIMIT = 40

    /** How often returning Home looks for new or removed collections */
    val DISCOVERY_INTERVAL = 3.minutes

    private val viewOptions = ApprovedHomeLayout.apply(HomeRowViewOptions())

    /** The kids' collections, as the kid, in the server's order (each collection's sort name is its position). */
    val collectionsRequest =
        GetItemsRequest(
            includeItemTypes = listOf(BaseItemKind.BOX_SET),
            recursive = true,
            tags = listOf(TAG),
            fields = listOf(ItemFields.CHILD_COUNT),
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableUserData = false,
        )

    /** The kid's own played movies and shows, most recently played first. */
    fun watchAgainRow(title: String) =
        HomeRowConfig.GetItems(
            name = title,
            getItems =
                GetItemsRequest(
                    includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    recursive = true,
                    isPlayed = true,
                    sortBy = listOf(ItemSortBy.DATE_PLAYED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    limit = WATCH_AGAIN_LIMIT,
                    fields = HomeItemFields,
                ),
            viewOptions = viewOptions,
        )

    /** A collection's row: its children as the kid, in the order the server gives them. */
    fun collectionRow(collectionId: UUID) = HomeRowConfig.ByParent(parentId = collectionId, viewOptions = viewOptions)

    /**
     * The kids Home's rows for [owner]: Next Up & Continue Watching, Watch Again, then [collections] in
     * the order given. Empty rows are hidden by Home, so with no collections yet it's just the first two.
     */
    fun settings(
        owner: HomeSettingsOwner,
        watchAgainTitle: String,
        collections: List<BaseItemDto>,
    ): HomePageResolvedSettings =
        HomePageResolvedSettings(
            owner = owner,
            rows =
                buildList {
                    add(
                        HomeRowConfigDisplay(
                            0,
                            ResStringProvider(R.string.kid_home_continue),
                            HomeRowConfig.ContinueWatchingCombined(viewOptions),
                        ),
                    )
                    add(HomeRowConfigDisplay(1, StringStringProvider(watchAgainTitle), watchAgainRow(watchAgainTitle)))
                    collections.distinctBy { it.id }.forEachIndexed { index, collection ->
                        add(
                            HomeRowConfigDisplay(
                                index + 2,
                                StringStringProvider(collection.name.orEmpty().uppercase(Locale.getDefault())),
                                collectionRow(collection.id),
                            ),
                        )
                    }
                },
        )

    /**
     * The kids' collections, as the kid, in the server's order. Selected by [TAG] alone (a collection
     * may carry other tags too). The server hides empty ones and the kid content rules drop any with
     * nothing the kid may see; each row's own first page decides the rest ([firstPage]).
     */
    suspend fun fetch(
        api: ApiClient,
        userId: UUID,
    ): List<BaseItemDto> = GetItemsRequestHandler.execute(api, collectionsRequest.copy(userId = userId)).content.items

    /**
     * A kids collection row's items: the first page of [limit], or, if the kid content rules left
     * nothing of it, one more page before the row counts as empty (and Home hides it).
     */
    suspend fun <T> firstPage(
        limit: Int,
        page: suspend (startIndex: Int) -> List<T>,
    ): List<T> = page(0).ifEmpty { page(limit) }
}
