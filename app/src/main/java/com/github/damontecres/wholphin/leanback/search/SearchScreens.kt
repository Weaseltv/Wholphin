package com.github.damontecres.wholphin.leanback.search

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Search, Discover, DiscoveredItem and DiscoverMoreResult.
 * Return null for any destination this area does not own.
 */
object SearchScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
