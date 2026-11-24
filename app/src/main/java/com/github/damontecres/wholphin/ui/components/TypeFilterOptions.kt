package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * The ALL / MOVIES / SHOWS filter options, moved out of TypeFilterButton.kt for the no-Compose UI (2026-11-22). Unchanged.
 */

/**
 * One of the outlined ALL / MOVIES / SHOWS filters. [types] is what the filter puts in
 * `includeItemTypes`; null means everything the page shows.
 */
data class TypeFilterOption(
    @param:StringRes val title: Int,
    val types: List<BaseItemKind>?,
) {
    /** The first type picks the colour, so SHOWS stays the series colour when it also covers seasons. */
    val accentKind: BaseItemKind? get() = types?.firstOrNull()
}

val StreamingTypeFilters =
    listOf(
        TypeFilterOption(R.string.streaming_all, null),
        TypeFilterOption(R.string.movies_title, listOf(BaseItemKind.MOVIE)),
        TypeFilterOption(R.string.streaming_shows, listOf(BaseItemKind.SERIES)),
    )

/** Seasons and episodes count as shows: the long-press menu can add either to the Watchlist. */
val WatchlistTypeFilters =
    listOf(
        TypeFilterOption(R.string.streaming_all, null),
        TypeFilterOption(R.string.movies_title, listOf(BaseItemKind.MOVIE)),
        TypeFilterOption(
            R.string.streaming_shows,
            listOf(BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE),
        ),
    )
