package com.github.damontecres.wholphin.data.model

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** A combined episode is one playable item covering every number in its inclusive range. */
fun BaseItemDto.coversEpisodeNumber(number: Int): Boolean {
    if (type != BaseItemKind.EPISODE) return false
    val start = indexNumber ?: return false
    return number >= start && number <= maxOf(start, indexNumberEnd ?: start)
}
