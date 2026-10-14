package com.github.damontecres.wholphin.services

import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The Collections page's group shortcuts. Every collection on the server carries one
 * "WeaselPlex Group: <group>" tag; other WeaselPlex tags are internal and ignored.
 */
object CollectionGroups {
    const val TAG_PREFIX = "WeaselPlex Group: "

    /** The owner's order (2026-10-14). Groups that aren't listed follow it, by name. */
    private val order =
        listOf(
            "New & Trending",
            "Moods & Picks",
            "TV Binges",
            "Top Episodes",
            "Charts & Awards",
            "Based On",
            "Studios",
            "TV Networks",
            "Actors & Directors",
            "Sports",
            "Franchises & Universes",
            "Streaming Services",
        )

    /** The groups that [collections] belong to, in the owner's order. Groups with no collections are left out. */
    fun groups(collections: List<BaseItemDto>): List<String> =
        collections
            .flatMap { it.tags.orEmpty() }
            .filter { it.startsWith(TAG_PREFIX) && it.length > TAG_PREFIX.length }
            .map { it.removePrefix(TAG_PREFIX) }
            .distinct()
            .sortedWith(compareBy({ order.indexOf(it).takeIf { index -> index >= 0 } ?: order.size }, { it }))

    fun tag(group: String) = TAG_PREFIX + group
}
