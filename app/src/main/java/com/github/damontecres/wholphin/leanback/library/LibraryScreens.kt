package com.github.damontecres.wholphin.leanback.library

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: MediaItem for COLLECTION_FOLDER, FOLDER, USER_VIEW, PHOTO_ALBUM, FilteredCollection and ItemGrid.
 * Return null for any destination this area does not own.
 */
object LibraryScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
