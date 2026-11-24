package com.github.damontecres.wholphin.leanback.library

import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.DefaultForGenresFilterOptions
import com.github.damontecres.wholphin.data.filter.DefaultForStudiosFilterOptions
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.components.ViewOptionsSquare
import com.github.damontecres.wholphin.ui.components.ViewOptionsWide
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.PlaylistSortOptions
import com.github.damontecres.wholphin.ui.data.VideoSortOptions
import com.github.damontecres.wholphin.ui.data.sortOptionsFor
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: MediaItem for COLLECTION_FOLDER, FOLDER,
 * USER_VIEW, PHOTO_ALBUM, FilteredCollection and ItemGrid. Returns null for any destination this area does not own.
 *
 * The choice is the old `DestinationContent` / `CollectionFolder`: TV and movie libraries are tabbed pages, every other
 * folder a single library grid ([folderSpecFor]).
 */
object LibraryScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.MediaItem -> {
                when (destination.type) {
                    BaseItemKind.COLLECTION_FOLDER, BaseItemKind.FOLDER, BaseItemKind.USER_VIEW -> {
                        when (destination.collectionType) {
                            CollectionType.TVSHOWS -> TvLibraryFragment()
                            CollectionType.MOVIES -> MovieLibraryFragment()
                            else -> CollectionFolderFragment()
                        }
                    }

                    BaseItemKind.PHOTO_ALBUM -> {
                        CollectionFolderFragment()
                    }

                    else -> {
                        null
                    }
                }
            }

            is Destination.FilteredCollection -> {
                CollectionFolderFragment()
            }

            is Destination.ItemGrid<*> -> {
                ItemGridFragment()
            }

            else -> {
                null
            }
        }

    /**
     * The single-grid pages (`CollectionFolderGeneric`, `CollectionFolderPlaylist`, `CollectionFolderPhotoAlbum`) for
     * [destination], with the old chooser's rules: folders use posters (`usePostersOverride`), user views list
     * recursively (`recursiveOverride`), Collections use posters with movie sorts, playlists square cards, photos and
     * home videos wide cards opening the slideshow, music videos and books (and other folders) wide cards; genre and
     * studio pages filter with their own options.
     */
    fun folderSpecFor(destination: Destination): FolderSpec =
        when (destination) {
            is Destination.FilteredCollection -> {
                FolderSpec(
                    itemId = destination.itemId,
                    kind = FolderKind.GENERIC,
                    defaultViewOptions = ViewOptionsPoster,
                    recursive = destination.recursive,
                    sortOptions = sortOptionsFor(destination.collectionType),
                    filterOptions =
                        when (destination.parentType) {
                            BaseItemKind.GENRE -> DefaultForGenresFilterOptions
                            BaseItemKind.STUDIO -> DefaultForStudiosFilterOptions
                            else -> throw IllegalArgumentException("Unsupported parentType ${destination.parentType}")
                        },
                    filter = destination.filter,
                )
            }

            is Destination.MediaItem -> {
                if (destination.type == BaseItemKind.PHOTO_ALBUM) {
                    photoAlbum(destination.itemId, recursive = false)
                } else {
                    val usePostersOverride = if (destination.type == BaseItemKind.FOLDER) true else null
                    val recursiveOverride = if (destination.type == BaseItemKind.USER_VIEW) true else null
                    when (destination.collectionType) {
                        CollectionType.BOXSETS -> {
                            FolderSpec(
                                itemId = destination.itemId,
                                kind = FolderKind.GENERIC,
                                defaultViewOptions = ViewOptionsPoster,
                                recursive = false,
                                sortOptions = MovieSortOptions,
                            )
                        }

                        CollectionType.PLAYLISTS -> {
                            FolderSpec(
                                itemId = destination.itemId,
                                kind = FolderKind.PLAYLIST,
                                defaultViewOptions = ViewOptionsSquare,
                                recursive = true,
                                sortOptions = PlaylistSortOptions,
                            )
                        }

                        CollectionType.HOMEVIDEOS, CollectionType.PHOTOS -> {
                            photoAlbum(destination.itemId, recursive = recursiveOverride ?: false)
                        }

                        else -> {
                            // MUSICVIDEOS and BOOKS played; FOLDERS, TRAILERS, LIVETV, MUSIC, UNKNOWN and null did not
                            // (playEnabled was not used by the grid either way)
                            FolderSpec(
                                itemId = destination.itemId,
                                kind = FolderKind.GENERIC,
                                defaultViewOptions = if (usePostersOverride ?: false) ViewOptionsPoster else ViewOptionsWide,
                                recursive = recursiveOverride ?: false,
                                sortOptions = VideoSortOptions,
                            )
                        }
                    }
                }
            }

            else -> {
                throw IllegalArgumentException("Not a library folder: $destination")
            }
        }

    private fun photoAlbum(
        itemId: UUID,
        recursive: Boolean,
    ) = FolderSpec(
        itemId = itemId,
        kind = FolderKind.PHOTO_ALBUM,
        defaultViewOptions = ViewOptionsWide,
        recursive = recursive,
        sortOptions = VideoSortOptions,
        useSeriesForPrimary = false,
    )
}

/** Which old page a single-grid library page is */
enum class FolderKind {
    /** `CollectionFolderGeneric`: OK opens the item (with its index) */
    GENERIC,

    /** `CollectionFolderPlaylist`: OK opens the playlist */
    PLAYLIST,

    /** `CollectionFolderPhotoAlbum`: OK on a photo opens the slideshow at it */
    PHOTO_ALBUM,
}

/** The parameters of a single-grid library page */
data class FolderSpec(
    val itemId: UUID,
    val kind: FolderKind,
    val defaultViewOptions: ViewOptions,
    val recursive: Boolean,
    val sortOptions: List<ItemSortBy>,
    val filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    val filter: CollectionFolderFilter = CollectionFolderFilter(),
    val useSeriesForPrimary: Boolean = true,
)
