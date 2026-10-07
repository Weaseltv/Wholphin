package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import kotlin.math.roundToInt

/** Server-managed Picks, using the existing query row and collection poster grid. */
object CuratedCollections {
    const val TAG = "WeaselPlex Curated"
    const val NAME = "WeaselPlex Picks"
    const val CARD_SIZE_MULTIPLIER = 1.35f
    val types = listOf(BaseItemKind.MOVIE)

    val row =
        HomeRowConfig.GetItems(
            name = NAME,
            getItems =
                GetItemsRequest(
                    includeItemTypes = listOf(BaseItemKind.BOX_SET),
                    recursive = true,
                    tags = listOf(TAG),
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    fields = listOf(ItemFields.CHILD_COUNT, ItemFields.OVERVIEW),
                ),
            viewOptions =
                HomeRowViewOptions(
                    heightDp = (BuildConfig.DEFAULT_CARD_HEIGHT_DP * CARD_SIZE_MULTIPLIER).roundToInt(),
                    showTitles = false,
                    useSeries = false,
                ),
        )

    fun isCuratedRow(config: HomeRowConfig): Boolean =
        config is HomeRowConfig.GetItems &&
            config.getItems.tags == listOf(TAG) &&
            config.getItems.includeItemTypes == listOf(BaseItemKind.BOX_SET)

    fun isCuratedCollection(item: BaseItemDto): Boolean = item.type == BaseItemKind.BOX_SET && TAG in item.tags.orEmpty()

    /** Keep Picks directly after Streaming, including in previously saved Home layouts. */
    fun withCuratedRow(settings: HomePageResolvedSettings): HomePageResolvedSettings {
        val streaming = StreamingCollections.withStreamingRow(settings)
        val existing = streaming.rows.firstOrNull { isCuratedRow(it.config) }
        val rows = streaming.rows.filterNot { isCuratedRow(it.config) }.toMutableList()
        val index = rows.indexOfFirst { StreamingCollections.isStreamingRow(it.config) } + 1
        rows.add(
            index,
            HomeRowConfigDisplay(
                id = existing?.id ?: ((rows.maxOfOrNull { it.id } ?: -1) + 1),
                title = StringStringProvider(NAME),
                config = existing?.config ?: row,
            ),
        )
        return HomePageResolvedSettings(rows)
    }

    /** Refetch by tag on every Home load; preserve the server's SortName order and current IDs. */
    suspend fun fetch(
        api: ApiClient,
        userId: UUID,
    ): List<BaseItemDto> =
        GetItemsRequestHandler
            .execute(api, row.getItems.copy(userId = userId))
            .content.items
            .filter { it.childCount != 0 }
}
