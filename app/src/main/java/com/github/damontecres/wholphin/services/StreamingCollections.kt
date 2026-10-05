package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.Locale
import java.util.UUID

/** WeaselPlex's streaming discovery uses the existing query-row and collection screens. */
object StreamingCollections {
    const val TAG = "WeaselPlex Streaming"
    val types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
    private val serviceOrder =
        listOf(
            "Netflix",
            "Disney+",
            "Hulu",
            "Max",
            "Prime Video",
            "Paramount+",
            "Peacock",
            "Apple TV+",
            "AMC+",
            "MGM+",
            "Starz",
            "BritBox",
            "Crunchyroll",
            "Hallmark",
            "Angel",
        ).map { it.lowercase(Locale.ROOT) }

    val row =
        HomeRowConfig.GetItems(
            name = "Streaming",
            getItems =
                GetItemsRequest(
                    includeItemTypes = listOf(BaseItemKind.BOX_SET),
                    recursive = true,
                    tags = listOf(TAG),
                    fields = listOf(ItemFields.CHILD_COUNT, ItemFields.OVERVIEW, ItemFields.TAGS),
                ),
            viewOptions = HomeRowViewOptions(),
        )

    fun isStreamingRow(config: HomeRowConfig): Boolean =
        config is HomeRowConfig.GetItems &&
            config.getItems.tags == listOf(TAG) &&
            config.getItems.includeItemTypes == listOf(BaseItemKind.BOX_SET)

    fun isStreamingCollection(item: BaseItemDto): Boolean = item.type == BaseItemKind.BOX_SET && TAG in item.tags.orEmpty()

    /** Apply locally, including to existing custom layouts; never write server preferences. */
    fun withStreamingRow(settings: HomePageResolvedSettings): HomePageResolvedSettings {
        if (settings.rows.any { isStreamingRow(it.config) }) return settings
        val rows = settings.rows.toMutableList()
        val index =
            rows.indexOfLast {
                it.config is HomeRowConfig.ContinueWatching ||
                    it.config is HomeRowConfig.NextUp ||
                    it.config is HomeRowConfig.ContinueWatchingCombined
            } + 1
        rows.add(
            index,
            HomeRowConfigDisplay(
                id = (rows.maxOfOrNull { it.id } ?: -1) + 1,
                title = StringStringProvider(row.name),
                config = row,
            ),
        )
        return HomePageResolvedSettings(rows)
    }

    /** Fresh member-scoped discovery on every Home load; no persisted membership cache. */
    suspend fun fetch(
        api: ApiClient,
        userId: UUID,
    ): List<BaseItemDto> {
        val collections =
            GetItemsRequestHandler.execute(api, row.getItems.copy(userId = userId)).content.items
        val visible =
            collections.filter { collection ->
                // ChildCount may be absent on some servers. Probe with the same member scope.
                collection.childCount?.let { it > 0 } ?: (
                    GetItemsRequestHandler
                        .execute(
                            api,
                            GetItemsRequest(
                                userId = userId,
                                parentId = collection.id,
                                includeItemTypes = types,
                                limit = 1,
                                enableTotalRecordCount = false,
                            ),
                        ).content.items
                        .isNotEmpty()
                )
            }
        return visible.sortedWith(
            compareBy<BaseItemDto> {
                serviceOrder.indexOf(it.name.orEmpty().lowercase(Locale.ROOT)).takeIf { it >= 0 }
                    ?: serviceOrder.size
            }.thenBy { it.name.orEmpty().lowercase(Locale.ROOT) },
        )
    }
}
