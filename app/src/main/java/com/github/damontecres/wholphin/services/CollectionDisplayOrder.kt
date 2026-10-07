package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.theme.collectionPosterAccent
import java.util.Locale

/** Display-name ordering shared by all users, independent of server SortName values. */
object CollectionDisplayOrder {
    private val sports = setOf("boxing", "ufc fight night", "ufc on abc", "ufc on espn", "ufc ppv")
    private fun name(item: BaseItem) = item.name.orEmpty().trim().lowercase(Locale.ROOT)
    private fun group(item: BaseItem): Int = when {
        StreamingCollections.serviceIndex(item.name) != null || StreamingCollections.isStreamingCollection(item.data) -> 1
        name(item) in sports -> 2
        CuratedCollections.isCuratedCollection(item.data) || collectionPosterAccent(item.name) != null -> 0
        else -> 3
    }

    fun sort(items: List<BaseItem>, descending: Boolean = false): List<BaseItem> = items.sortedWith(
        Comparator { a, b ->
            val groupOrder = group(a).compareTo(group(b))
            if (groupOrder != 0) return@Comparator groupOrder
            if (group(a) == 1) {
                val providerOrder = (StreamingCollections.serviceIndex(a.name) ?: Int.MAX_VALUE)
                    .compareTo(StreamingCollections.serviceIndex(b.name) ?: Int.MAX_VALUE)
                if (providerOrder != 0) return@Comparator providerOrder
            }
            val nameOrder = name(a).compareTo(name(b)) * if (descending && group(a) != 1) -1 else 1
            if (nameOrder != 0) nameOrder else a.id.toString().compareTo(b.id.toString())
        },
    )
}
