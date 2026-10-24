package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.kids.KidContentRules.Decision
import com.github.damontecres.wholphin.kids.KidContentRules.HideReason
import com.github.damontecres.wholphin.kids.KidContentRules.ItemFacts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.IdentityHashMap

/**
 * Loads the metadata [KidResponseFilter] is missing, as the same kid user and never through the filter itself. Every
 * id it can't return (the server hid it, or the lookup failed) is treated as hidden.
 */
interface KidFactsLoader {
    /** The items with [ids] (normalized, see [KidJson.normalizeId]) the server returned; [fresh] skips any cache */
    suspend fun items(
        ids: Collection<String>,
        fresh: Boolean,
    ): Map<String, ItemFacts>

    /** Whether the box set [id] has at least one child the rules show at [maxLevel] */
    suspend fun boxSetHasVisibleChild(
        id: String,
        maxLevel: Int?,
        fresh: Boolean,
    ): Boolean
}

/** Reading Jellyfin item JSON for the content rules */
object KidJson {
    private val ITEM_KINDS: Set<String> = BaseItemKind.entries.mapTo(HashSet()) { it.serialName }

    /** Keys only a `BaseItemDto` or `SearchHint` has; an item of a [BaseItemKind] this SDK doesn't know still has them */
    private val ITEM_MARKERS = listOf("ServerId", "IsFolder", "MediaType")

    /** Jellyfin ids compared without dashes, lowercase */
    fun normalizeId(id: String): String = id.replace("-", "").lowercase()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.strings(key: String): List<String>? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    /** The item id of a `BaseItemDto` (`Id`) or a `SearchHint` (`Id`, else `ItemId`) */
    fun idOf(obj: JsonObject): String? = (obj.string("Id") ?: obj.string("ItemId"))?.let(::normalizeId)

    /**
     * Whether [obj] is an item the rules decide: an id, and a `Type` that is a [BaseItemKind] (or, for a kind this SDK
     * doesn't know, an object with the keys only items have: an unknown type is treated as a title)
     */
    fun isItem(obj: JsonObject): Boolean {
        if (idOf(obj) == null) return false
        val type = obj.string("Type") ?: return false
        return type in ITEM_KINDS || ITEM_MARKERS.any { obj.containsKey(it) }
    }

    fun factsOf(obj: JsonObject): ItemFacts =
        ItemFacts(
            id = idOf(obj),
            type = obj.string("Type"),
            name = obj.string("Name"),
            officialRating = obj.string("OfficialRating"),
            genres = obj.strings("Genres"),
            premiereDate = obj.string("PremiereDate"),
            productionYear = (obj["ProductionYear"] as? JsonPrimitive)?.intOrNull,
            lockedFields = obj.strings("LockedFields"),
            seriesId = obj.string("SeriesId")?.let(::normalizeId),
        )

    /** The item objects of a query result (`Items`) or a top-level array */
    fun itemsOf(root: JsonElement): List<JsonObject> {
        val array =
            when (root) {
                is JsonArray -> root
                is JsonObject -> root["Items"] as? JsonArray
                else -> null
            } ?: return emptyList()
        return array.filterIsInstance<JsonObject>().filter(::isItem)
    }
}

/**
 * Applies the content rules to one Jellyfin response for a kid session whose maximum level is [maxLevel] (null:
 * unknown ceiling). Facts a response lacks are loaded through [loader]; whatever can't be loaded is hidden.
 */
class KidResponseFilter(
    private val maxLevel: Int?,
    private val loader: KidFactsLoader,
    private val fresh: Boolean = false,
    /**
     * The kids Home's collection list ([KidContentGuard.isKidsHomeCollections]): its box sets are shown without the
     * one-visible-child probe (with a known ceiling only; an unknown one still shows none)
     */
    private val boxSetsAsRowSources: Boolean = false,
) {
    /** The filtered response */
    data class Result(
        /** The filtered body, or null if the top-level item itself is hidden (the caller answers 404) */
        val root: JsonElement?,
        val changed: Boolean,
        /** How many entries were removed from the top-level list (`Items` or the root array) */
        val removedFromList: Int,
    )

    suspend fun filter(root: JsonElement): Result {
        val found = mutableListOf<JsonObject>()
        collect(root, found)
        if (found.isEmpty()) return Result(root, changed = false, removedFromList = 0)
        val decisions = decideAll(found)
        if (root is JsonObject && decisions[root] is Decision.Hide) return Result(null, changed = true, removedFromList = 0)
        if (decisions.values.none { it is Decision.Hide }) return Result(root, changed = false, removedFromList = 0)
        var removedFromList = 0
        val rebuilt =
            rebuild(root, decisions) { container, removed ->
                if (container === root || (root is JsonObject && container === root["Items"])) removedFromList += removed
            }
        return Result(rebuilt, changed = true, removedFromList = removedFromList)
    }

    /** One item by its facts (eg a path item) */
    suspend fun decide(facts: ItemFacts): Decision {
        val obj = JsonObject(emptyMap())
        return decideAll(listOf(obj), mapOf(obj to facts)).getValue(obj)
    }

    /** Every item object in [element], depth first, items included in their nested fields */
    private fun collect(
        element: JsonElement,
        into: MutableList<JsonObject>,
    ) {
        when (element) {
            is JsonObject -> {
                if (KidJson.isItem(element)) into.add(element)
                element.values.forEach { collect(it, into) }
            }

            is JsonArray -> {
                element.forEach { collect(it, into) }
            }

            else -> {
                Unit
            }
        }
    }

    private suspend fun decideAll(
        objects: List<JsonObject>,
        given: Map<JsonObject, ItemFacts> = emptyMap(),
    ): Map<JsonObject, Decision> {
        val facts = IdentityHashMap<JsonObject, ItemFacts>()
        objects.forEach { facts[it] = given[it] ?: KidJson.factsOf(it) }
        val decisions = IdentityHashMap<JsonObject, Decision>()
        if (maxLevel == null) {
            // Unknown ceiling: no title is shown; containers need nothing loaded (a box set has no visible child)
            facts.forEach { (obj, item) ->
                decisions[obj] =
                    if (item.type == KidContentRules.BOX_SET) {
                        KidContentRules.decideBoxSet(false)
                    } else {
                        KidContentRules.decide(item, null)
                    }
            }
            return decisions
        }
        // Items whose own metadata is incomplete (eg search hints, endpoints without `fields`)
        val incomplete =
            facts.values
                .filterNot(KidContentRules::hasFacts)
                .mapNotNull { it.id }
                .toSet()
        val loaded = if (incomplete.isEmpty()) emptyMap() else loadItems(incomplete)
        val resolved = IdentityHashMap<JsonObject, ItemFacts?>()
        facts.forEach { (obj, item) ->
            resolved[obj] = if (KidContentRules.hasFacts(item)) item else item.id?.let { loaded[it] }
        }
        // Series of episodes and seasons: from this response if it has them complete, else loaded
        val seriesInResponse =
            resolved.values
                .filterNotNull()
                .filter { it.type == KidContentRules.SERIES && it.genres != null && it.id != null }
                .associateBy { it.id!! }
        val seriesIds =
            resolved.values
                .filterNotNull()
                .filter { KidContentRules.needsSeries(it.type) }
                .mapNotNull { it.seriesId }
                .filterNot { it in seriesInResponse }
                .toSet()
        val series = seriesInResponse + (if (seriesIds.isEmpty()) emptyMap() else loadItems(seriesIds))
        val boxSets = HashMap<String, Boolean>()
        resolved.forEach { (obj, item) ->
            decisions[obj] =
                when {
                    item == null -> {
                        Decision.Hide(HideReason.MISSING_FACTS)
                    }

                    item.type == KidContentRules.BOX_SET -> {
                        val id = item.id
                        val visible = id != null && (boxSetsAsRowSources || boxSets.getOrPut(id) { boxSetVisible(id) })
                        KidContentRules.decideBoxSet(visible)
                    }

                    KidContentRules.needsSeries(item.type) -> {
                        KidContentRules.decide(item, maxLevel, item.seriesId?.let { series[it] })
                    }

                    else -> {
                        KidContentRules.decide(item, maxLevel)
                    }
                }
        }
        return decisions
    }

    private suspend fun loadItems(ids: Set<String>): Map<String, ItemFacts> =
        try {
            loader.items(ids, fresh)
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            // Fail closed: everything this lookup was for is hidden
            emptyMap()
        }

    private suspend fun boxSetVisible(id: String): Boolean =
        try {
            loader.boxSetHasVisibleChild(id, maxLevel, fresh)
        } catch (ex: CancellationException) {
            throw ex
        } catch (_: Exception) {
            false
        }

    /**
     * [element] without its hidden items: removed from arrays (a sibling `TotalRecordCount` goes down by as many) and
     * from the fields of objects. [onRemoved] hears how many entries each array lost.
     */
    private fun rebuild(
        element: JsonElement,
        decisions: Map<JsonObject, Decision>,
        onRemoved: (container: JsonElement, removed: Int) -> Unit,
    ): JsonElement =
        when (element) {
            is JsonArray -> {
                val kept = ArrayList<JsonElement>(element.size)
                element.forEach {
                    if (it is JsonObject && decisions[it] is Decision.Hide) return@forEach
                    kept.add(rebuild(it, decisions, onRemoved))
                }
                if (kept.size != element.size) onRemoved(element, element.size - kept.size)
                JsonArray(kept)
            }

            is JsonObject -> {
                var removedFromArrays = 0
                val fields = LinkedHashMap<String, JsonElement>(element.size)
                element.forEach { (key, value) ->
                    if (value is JsonObject && decisions[value] is Decision.Hide) return@forEach
                    fields[key] =
                        rebuild(value, decisions) { container, removed ->
                            if (container === value) removedFromArrays += removed
                            onRemoved(container, removed)
                        }
                }
                val total = (element["TotalRecordCount"] as? JsonPrimitive)?.intOrNull
                if (total != null && removedFromArrays > 0) {
                    fields["TotalRecordCount"] = JsonPrimitive((total - removedFromArrays).coerceAtLeast(0))
                }
                JsonObject(fields)
            }

            else -> {
                element
            }
        }
}
