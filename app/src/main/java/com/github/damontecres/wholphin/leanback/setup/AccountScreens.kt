package com.github.damontecres.wholphin.leanback.setup

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * App-layer screens owned by the SETUP area for the shell's ScreenFactory (no-Compose UI): [Destination.ProfilePicture]
 * (the signed-in profile's picture page, built on the same avatar picker as the kids and first-avatar flows).
 * Return null for any destination this area does not own.
 */
object AccountScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
