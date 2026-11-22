package com.github.damontecres.wholphin.leanback.home

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Home, Favorites (Watchlist) and MoreHomeRow.
 * Return null for any destination this area does not own.
 */
object HomeScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
