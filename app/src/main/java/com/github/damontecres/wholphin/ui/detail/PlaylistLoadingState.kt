package com.github.damontecres.wholphin.ui.detail

import com.github.damontecres.wholphin.data.model.PlaylistInfo

/** Loading state of the "Add to playlist" picker's playlist list */
sealed interface PlaylistLoadingState {
    data object Pending : PlaylistLoadingState

    data object Loading : PlaylistLoadingState

    data class Success(
        val items: List<PlaylistInfo>,
        val query: String,
    ) : PlaylistLoadingState

    data class Error(
        val message: String? = null,
        val exception: Throwable? = null,
    ) : PlaylistLoadingState {
        constructor(exception: Throwable) : this(null, exception)

        val localizedMessage: String =
            listOfNotNull(message, exception?.localizedMessage).joinToString(" - ")
    }
}
