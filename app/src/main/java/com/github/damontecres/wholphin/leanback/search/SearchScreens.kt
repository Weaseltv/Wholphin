package com.github.damontecres.wholphin.leanback.search

import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Search, Discover (Requests),
 * DiscoveredItem (Requests movie / show details, Requests person page) and DiscoverMoreResult.
 * Return null for any destination this area does not own.
 */
object SearchScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.Search -> {
                SearchFragment.newInstance(preferences)
            }

            is Destination.Discover -> {
                DiscoverFragment()
            }

            is Destination.DiscoverMoreResult -> {
                DiscoverMoreFragment()
            }

            is Destination.DiscoveredItem -> {
                when (destination.item.type) {
                    SeerrItemType.MOVIE -> DiscoverMovieFragment()
                    SeerrItemType.TV -> DiscoverSeriesFragment()
                    SeerrItemType.PERSON -> DiscoverPersonFragment()
                    SeerrItemType.UNKNOWN -> UnknownDiscoverFragment()
                }
            }

            else -> {
                null
            }
        }
}
