package com.github.damontecres.wholphin.ui.main.settings

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.SuggestionsWorker
import org.jellyfin.sdk.model.api.CollectionType

/*
 * The row types a library offers in Home settings (Compose-free, moved from HomeLibraryRowTypeList.kt 2026-11-22).
 */

/** The row types [library] supports, in display order */
fun getSupportedRowTypes(library: Library): List<LibraryRowType> {
    val supportsSuggestions = SuggestionsWorker.getTypeForCollection(library.collectionType) != null
    return when {
        library.collectionType == CollectionType.TVSHOWS -> {
            listOf(
                LibraryRowType.RECENTLY_ADDED,
                LibraryRowType.RECENTLY_RELEASED,
                LibraryRowType.SUGGESTIONS,
                LibraryRowType.GENRES,
                LibraryRowType.STUDIOS,
            )
        }

        supportsSuggestions -> {
            listOf(
                LibraryRowType.RECENTLY_ADDED,
                LibraryRowType.RECENTLY_RELEASED,
                LibraryRowType.SUGGESTIONS,
                LibraryRowType.GENRES,
            )
        }

        library.collectionType == CollectionType.BOXSETS -> {
            listOf(
                LibraryRowType.RECENTLY_ADDED,
                LibraryRowType.RECENTLY_RELEASED,
                LibraryRowType.GENRES,
                LibraryRowType.COLLECTION,
            )
        }

        library.collectionType == CollectionType.PLAYLISTS -> {
            listOf(
                LibraryRowType.RECENTLY_ADDED,
                LibraryRowType.RECENTLY_RELEASED,
                LibraryRowType.GENRES,
                LibraryRowType.PLAYLIST,
            )
        }

        else -> {
            listOf(
                LibraryRowType.RECENTLY_ADDED,
                LibraryRowType.RECENTLY_RELEASED,
                LibraryRowType.GENRES,
            )
        }
    }
}

enum class LibraryRowType(
    @param:StringRes val stringId: Int,
) {
    RECENTLY_ADDED(R.string.recently_added),
    RECENTLY_RELEASED(R.string.recently_released),
    SUGGESTIONS(R.string.suggestions),
    GENRES(R.string.genres),
    STUDIOS(R.string.studios),
    COLLECTION(R.string.collection),
    PLAYLIST(R.string.playlist),
}
