package com.github.damontecres.wholphin.leanback.shell

import com.github.damontecres.wholphin.leanback.detail.DetailScreens
import com.github.damontecres.wholphin.leanback.home.HomeScreens
import com.github.damontecres.wholphin.leanback.library.LibraryScreens
import com.github.damontecres.wholphin.leanback.playback.PlaybackScreens
import com.github.damontecres.wholphin.leanback.search.SearchScreens
import com.github.damontecres.wholphin.leanback.settings.SettingsScreens
import com.github.damontecres.wholphin.leanback.setup.AccountScreens
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Chooses the screen for a [Destination] (replaces the Compose `DestinationContent`). Each area owns its own
 * `*Screens` object so the areas never edit the same file. Owned by the shell.
 */
object ScreenFactory {
    /** @return the screen, or null when no area owns [destination] (the shell shows an "unsupported" page) */
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        HomeScreens.create(destination, preferences)
            ?: DetailScreens.create(destination, preferences)
            ?: LibraryScreens.create(destination, preferences)
            ?: SearchScreens.create(destination, preferences)
            ?: SettingsScreens.create(destination, preferences)
            ?: PlaybackScreens.create(destination, preferences)
            ?: AccountScreens.create(destination, preferences)
}
