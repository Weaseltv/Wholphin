package com.github.damontecres.wholphin.services

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.JustWatchHomeList
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.theme.NeonSectionPalette
import com.github.damontecres.wholphin.ui.theme.libraryAccent
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** WeaselPlex's Home rows, in the order the owner laid out on 2026-10-14. */
object WeaselHomeRows {
    /** The two Recently Added rows' kinds of library, in Home's order (owner 2026-11-03). */
    private val RecentlyAddedKinds = listOf(CollectionType.MOVIES, CollectionType.TVSHOWS)

    /**
     * The libraries in the Recently Added Movies ([type] MOVIES) or Recently Added TV Shows ([type]
     * TVSHOWS) row: those movie or show libraries other than Stand Up Comedy and Sports.
     */
    fun recentlyAddedLibraries(
        libraries: List<Library>,
        type: CollectionType,
    ): List<UUID> = libraries.filter { it.collectionType == type && isMainLibrary(it.name, it.collectionType) }.map { it.itemId }

    /** A Recently Added row's full title: Recently added movies, TV shows, or (the older row) both. */
    @StringRes
    fun recentlyAddedTitle(row: HomeRowConfig.RecentlyAddedCombined): Int =
        when (row.collectionType) {
            CollectionType.MOVIES -> R.string.home_recently_added_movies_full
            CollectionType.TVSHOWS -> R.string.home_recently_added_shows_full
            else -> R.string.home_recently_added_full
        }

    /** A Recently Added row's heading under its RECENTLY ADDED kicker: Movies, TV Shows, or (the older row) both. */
    @StringRes
    fun recentlyAddedHeading(row: HomeRowConfig.RecentlyAddedCombined): Int =
        when (row.collectionType) {
            CollectionType.MOVIES -> R.string.home_movies_kicker
            CollectionType.TVSHOWS -> R.string.home_tv_shows_kicker
            else -> R.string.home_movies_and_tv_shows
        }

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
     * [rows] in Home's order: Continue Watching, Streaming Services, Recently Added Movies,
     * Recently Added TV Shows, then the server's lists ([JustWatchHomeList]: Trending Movies,
     * Trending TV Shows, 90 Days Recently Released, Movie Roulette). The two Recently Added rows
     * replace the older combined Movies and TV Shows row and each movie and show library's own row
     * (Stand Up Comedy and Sports included); each keeps the older row's view options. Any other rows
     * follow in their order. The retired Picks row and the Collections library's Recently Added
     * row, which Jellyfin always returns empty, are dropped.
     */
    fun arrange(
        rows: List<HomeRowConfig>,
        libraries: List<Library>,
    ): List<HomeRowConfig> {
        val mediaLibraries = libraries.filter { it.isMovieOrShowLibrary }.map { it.itemId }.toSet()
        val collectionLibraries = libraries.filter { it.collectionType == CollectionType.BOXSETS }.map { it.itemId }.toSet()
        val watching = rows.filter { it.isWatchingRow }.ifEmpty { listOf(HomeRowConfig.ContinueWatchingCombined()) }
        val streaming = rows.filter(StreamingCollections::isStreamingRow).ifEmpty { listOf(StreamingCollections.row) }
        val combined = rows.filterIsInstance<HomeRowConfig.RecentlyAddedCombined>()
        val recentlyAdded =
            RecentlyAddedKinds
                .mapNotNull { type ->
                    val parentIds = recentlyAddedLibraries(libraries, type)
                    if (parentIds.isEmpty()) return@mapNotNull null
                    combined.firstOrNull { it.collectionType == type && it.parentIds == parentIds }
                        ?: HomeRowConfig.RecentlyAddedCombined(
                            parentIds = parentIds,
                            viewOptions = (combined.firstOrNull { it.collectionType == type } ?: combined.firstOrNull())?.viewOptions
                                ?: HomeRowViewOptions(),
                            collectionType = type,
                        )
                }
                // Libraries that couldn't be read: keep what was saved
                .ifEmpty { combined }
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
