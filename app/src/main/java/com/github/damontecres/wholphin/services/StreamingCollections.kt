package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
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
import java.util.Locale
import java.util.UUID

/** WeaselPlex's streaming discovery uses the existing query-row and collection screens. */
object StreamingCollections {
    const val TAG = "WeaselPlex Streaming"

    /** The Home row's title. Saved layouts may still hold an older name, so use [title] to show it. */
    const val NAME = "Streaming Services"
    val types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

    /** The names the server used before (owner, 2026-10-16: Max -> HBO Max, Apple TV+ -> Apple TV). */
    private fun canonicalName(name: String?): String =
        when (val value = name.orEmpty().trim().lowercase(Locale.ROOT)) {
            "max", "max (hbo)", "hbo" -> "hbo max"
            "apple tv+", "apple tv plus" -> "apple tv"
            "hallmark+" -> "hallmark"
            "angel studios" -> "angel"
            else -> value
        }

    /**
     * JustWatch's US package codes for each service the server's JustWatch lists cover, by
     * collection name, including ad tiers and plans that share the catalogue. Hallmark+ is only
     * listed as an Apple TV channel. The first code keys the server's lists (`justwatch-home.sh`).
     */
    private val justWatchPackages =
        mapOf(
            "netflix" to listOf("nfx", "nfa"),
            "disney+" to listOf("dnp"),
            "hulu" to listOf("hlu"),
            "hbo max" to listOf("mxx"),
            "prime video" to listOf("amp"),
            "paramount+" to listOf("ppp", "ppe"),
            "peacock" to listOf("pct", "pcp"),
            "apple tv" to listOf("atp"),
            "amc+" to listOf("acp"),
            "mgm+" to listOf("epx"),
            "starz" to listOf("stz"),
            "britbox" to listOf("bbo"),
            "crunchyroll" to listOf("cru"),
            "hallmark" to listOf("hmc"),
            "angel" to listOf("agl"),
        )

    /** The JustWatch packages for the streaming collection named [name], if it's a known service. */
    fun justWatchPackages(name: String?): List<String>? = justWatchPackages[canonicalName(name)]

    val row =
        HomeRowConfig.GetItems(
            name = NAME,
            getItems =
                GetItemsRequest(
                    includeItemTypes = listOf(BaseItemKind.BOX_SET),
                    recursive = true,
                    tags = listOf(TAG),
                    fields = listOf(ItemFields.CHILD_COUNT, ItemFields.OVERVIEW, ItemFields.TAGS),
                    // The owner's order: each collection's sort name is its position (~01 Netflix…)
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                ),
            viewOptions = ApprovedHomeLayout.apply(HomeRowViewOptions(), streaming = true),
        )

    fun isStreamingRow(config: HomeRowConfig): Boolean =
        config is HomeRowConfig.GetItems &&
            config.getItems.tags == listOf(TAG) &&
            config.getItems.includeItemTypes == listOf(BaseItemKind.BOX_SET)

    /** A row's title, with the Streaming row always shown as [NAME]. */
    fun title(config: HomeRowConfig.GetItems): String = if (isStreamingRow(config)) NAME else config.name

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

    /**
     * Fresh member-scoped discovery on every Home load; no persisted membership cache. Every
     * collection tagged [TAG], in the server's sort-name order (the owner's row order), however
     * many there are.
     */
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
        return visible
    }
}
