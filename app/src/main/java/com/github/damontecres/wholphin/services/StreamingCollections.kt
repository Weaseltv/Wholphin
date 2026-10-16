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

    /**
     * The names the server used before (owner, 2026-10-16: Max -> HBO Max, Apple TV+ -> Apple TV), and
     * other common names. Not "Showtime Network": that's a TV Networks collection, not the service.
     */
    private fun canonicalName(name: String?): String =
        when (val value = name.orEmpty().trim().lowercase(Locale.ROOT)) {
            "max", "max (hbo)", "hbo" -> "hbo max"
            "apple tv+", "apple tv plus" -> "apple tv"
            "hallmark+" -> "hallmark"
            "angel studios" -> "angel"
            "discovery plus" -> "discovery+"
            "the criterion channel" -> "criterion channel"
            "pluto" -> "pluto tv"
            "tubi tv" -> "tubi"
            "acorn" -> "acorn tv"
            else -> value
        }

    /**
     * A streaming service's JustWatch lists. The server keys its saved lists by [code]
     * (`justwatch-home.sh`); [packages] are JustWatch's US package codes for the service, including
     * ad tiers and plans that share the catalog, for asking JustWatch when the server has none
     * (empty for a service only the server knows).
     */
    data class JustWatchLists(
        val code: String,
        val packages: List<String>,
    )

    /** Lists keyed by the first package, as for most services. */
    private fun lists(vararg packages: String) = JustWatchLists(packages.first(), packages.toList())

    /**
     * The services the server's JustWatch lists cover, by collection name, for servers without
     * `service_codes` (see [justWatch]) and for JustWatch's packages. Hallmark+ is only listed
     * as an Apple TV channel. Showtime has no JustWatch service of its own any more (it's folded into
     * Paramount+ Premium), so its lists are Paramount+'s rankings kept to its own members, saved
     * under its own code (owner, 2026-10-16).
     */
    private val justWatch =
        mapOf(
            "netflix" to lists("nfx", "nfa"),
            "disney+" to lists("dnp"),
            "hulu" to lists("hlu"),
            "hbo max" to lists("mxx"),
            "prime video" to lists("amp"),
            "paramount+" to lists("ppp", "ppe"),
            "peacock" to lists("pct", "pcp"),
            "apple tv" to lists("atp"),
            "amc+" to lists("acp"),
            "mgm+" to lists("epx"),
            "starz" to lists("stz"),
            "britbox" to lists("bbo"),
            "crunchyroll" to lists("cru"),
            "hallmark" to lists("hmc"),
            "angel" to lists("agl"),
            "tubi" to lists("tbv"),
            "pluto tv" to lists("ptv"),
            "discovery+" to lists("dpu"),
            "shudder" to lists("shd", "sua"),
            "acorn tv" to lists("act", "acr"),
            "criterion channel" to lists("crc"),
            "youtube" to lists("yfr", "ytr"),
            "showtime" to JustWatchLists("sho", listOf("ppp", "ppe")),
        )

    /** The JustWatch lists for the streaming collection named [name], if it's a known service. */
    fun justWatch(name: String?): JustWatchLists? = justWatch[canonicalName(name)]

    /**
     * The JustWatch lists for the streaming collection [id] named [name]. The server says which
     * collection has which lists ([serviceCodes], see [parseServiceCodes]), so a service added there
     * gets them without an app release (owner, 2026-10-16); a collection it leaves out has none. Only
     * without that mapping (an older server job) do the built-in names decide. JustWatch's packages
     * always come from the built-in names.
     */
    fun justWatch(
        id: UUID,
        name: String?,
        serviceCodes: Map<String, String>?,
    ): JustWatchLists? {
        val builtIn = justWatch(name)
        if (serviceCodes == null) return builtIn
        val code = serviceCodes[serverId(id.toString())] ?: return null
        return JustWatchLists(code, builtIn?.packages.orEmpty())
    }

    /**
     * The server's `service_codes` ("<collection id>=<code>,…") by collection ID, or null when it has
     * none (missing or empty), so the built-in names apply.
     */
    fun parseServiceCodes(value: String?): Map<String, String>? {
        if (value.isNullOrBlank()) return null
        return value
            .split(',')
            .mapNotNull { pair ->
                val (id, code) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                (serverId(id) to code.trim()).takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
            }.toMap()
    }

    /** IDs compared as the server writes them: no dashes, any case. */
    private fun serverId(id: String) = id.trim().replace("-", "").lowercase(Locale.ROOT)

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
