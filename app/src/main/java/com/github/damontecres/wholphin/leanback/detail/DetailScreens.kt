package com.github.damontecres.wholphin.leanback.detail

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: MediaItem for SERIES, MOVIE, VIDEO, MUSIC_VIDEO, EPISODE, BOX_SET, PLAYLIST, PERSON and SeriesOverview.
 * Return null for any destination this area does not own.
 */
object DetailScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.MediaItem -> {
                when (destination.type) {
                    // Videos and music videos use the movie page, as before
                    BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> MovieDetailsFragment()
                    else -> null
                }
            }

            else -> {
                null
            }
        }
}
