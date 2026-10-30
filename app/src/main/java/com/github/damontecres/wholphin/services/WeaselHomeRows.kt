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
        libraries.filter { isMainLibrary(it.name, it.collectionType) }.map { it.itemId }

    /** A movie or show library other than Stand Up Comedy and Sports, i.e. Movies and TV Shows. */
    fun isMainLibrary(
        name: String,
        collectionType: CollectionType?,
    ): Boolean {
        if (collectionType != CollectionType.MOVIES && collectionType != CollectionType.TVSHOWS) return false
        val accent = libraryAccent(name, collectionType)
        return accent != NeonSectionPalette.StandUpComedy.border && accent != NeonSectionPalette.Sports.border
    }

    /**
     * [rows] in Home's order: Continue Watching, Streaming Services, Recently Added Movies and TV
     * Shows, then the JustWatch rows. The combined Recently Added row replaces each movie and show
     * library's own row (Stand Up Comedy and Sports included). Any other rows follow in their
     * order. The retired Picks row and the Collections library's Recently Added row, which
     * Jellyfin always returns empty, are dropped.
     */
    fun arrange(
        rows: List<HomeRowConfig>,
        libraries: List<Library>,
    ): List<HomeRowConfig> {
        val mediaLibraries = libraries.filter { it.isMovieOrShowLibrary }.map { it.itemId }.toSet()
        val collectionLibraries = libraries.filter { it.collectionType == CollectionType.BOXSETS }.map { it.itemId }.toSet()
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
                    it.isRetiredPicksRow ||
                    (it is HomeRowConfig.RecentlyAdded && (it.parentId in mediaLibraries || it.parentId in collectionLibraries))
            }
        return watching + streaming + recentlyAdded + justWatch + others
    }

    private val Library.isMovieOrShowLibrary
        get() = collectionType == CollectionType.MOVIES || collectionType == CollectionType.TVSHOWS

    /** The Weasel's Picks row, retired 2026-10-16 after its collections were deleted from the server. */
    private val HomeRowConfig.isRetiredPicksRow
        get() = this is HomeRowConfig.GetItems && getItems.tags == listOf("WeaselPlex Curated")

    private val HomeRowConfig.isWatchingRow
        get() =
            this is HomeRowConfig.ContinueWatchingCombined ||
                this is HomeRowConfig.ContinueWatching ||
                this is HomeRowConfig.NextUp
}
