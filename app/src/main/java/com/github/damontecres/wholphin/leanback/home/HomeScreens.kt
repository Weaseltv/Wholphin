package com.github.damontecres.wholphin.leanback.home

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Home, Favorites (the Watchlist in the
 * weaselfin flavor, the per-type Favorites page in the others, as DestinationContent chose) and MoreHomeRow.
 * Returns null for any destination this area does not own.
 */
object HomeScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.Home -> HomeFragment()
            is Destination.MoreHomeRow -> HomeRowGridFragment()
            Destination.Favorites -> if (BuildConfig.FLAVOR == "weaselfin") WatchlistFragment() else FavoritesFragment()
            else -> null
        }
}
