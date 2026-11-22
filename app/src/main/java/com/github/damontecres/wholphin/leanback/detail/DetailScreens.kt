package com.github.damontecres.wholphin.leanback.detail

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: MediaItem for SERIES, MOVIE, VIDEO, MUSIC_VIDEO, EPISODE, BOX_SET, PLAYLIST, PERSON and SeriesOverview.
 * Return null for any destination this area does not own.
 */
object DetailScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
