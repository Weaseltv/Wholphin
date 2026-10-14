package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JustWatchHomeList
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette
import com.github.damontecres.wholphin.ui.theme.libraryAccent
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** WeaselPlex's Home rows, in the order the owner laid out on 2026-10-14. */
object WeaselHomeRows {
    /** The libraries in the combined Recently Added row: every movie and show library except Stand Up Comedy and Sports. */
    fun recentlyAddedLibraries(libraries: List<Library>): List<UUID> =
        libraries
            .filter { it.isMovieOrShowLibrary }
            .filterNot {
                val accent = libraryAccent(it.name, it.collectionType)
                accent == NeonSectionPalette.StandUpComedy.border || accent == NeonSectionPalette.Sports.border
            }.map { it.itemId }

    /**
     * [rows] in Home's order: Continue Watching, Streaming Services, Recently Added Movies and TV
     * Shows, then the JustWatch rows. The combined Recently Added row replaces each movie and show
     * library's own row (Stand Up Comedy and Sports included). Any other rows follow in their
     * order, and The Weasel's Picks stays last (see [CuratedCollections.withCuratedRow]).
     */
    fun arrange(
        rows: List<HomeRowConfig>,
        libraries: List<Library>,
    ): List<HomeRowConfig> {
        val mediaLibraries = libraries.filter { it.isMovieOrShowLibrary }.map { it.itemId }.toSet()
        val watching = rows.filter { it.isWatchingRow }.ifEmpty { listOf(HomeRowConfig.ContinueWatchingCombined()) }
        val streaming = rows.filter(StreamingCollections::isStreamingRow).ifEmpty { listOf(StreamingCollections.row) }
        val recentlyAdded =
            rows
                .filterIsInstance<HomeRowConfig.RecentlyAddedCombined>()
                .ifEmpty { listOf(HomeRowConfig.RecentlyAddedCombined(recentlyAddedLibraries(libraries))) }
        val justWatch =
            JustWatchHomeList.entries.map { list ->
                rows.firstOrNull { it is HomeRowConfig.JustWatch && it.list == list } ?: HomeRowConfig.JustWatch(list)
            }
        val others =
            rows.filterNot {
                it.isWatchingRow ||
                    StreamingCollections.isStreamingRow(it) ||
                    it is HomeRowConfig.RecentlyAddedCombined ||
                    it is HomeRowConfig.JustWatch ||
                    (it is HomeRowConfig.RecentlyAdded && it.parentId in mediaLibraries)
            }
        return watching + streaming + recentlyAdded + justWatch + others
    }

    private val Library.isMovieOrShowLibrary
        get() = collectionType == CollectionType.MOVIES || collectionType == CollectionType.TVSHOWS

    private val HomeRowConfig.isWatchingRow
        get() =
            this is HomeRowConfig.ContinueWatchingCombined ||
                this is HomeRowConfig.ContinueWatching ||
                this is HomeRowConfig.NextUp
}
