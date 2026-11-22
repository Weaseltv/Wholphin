package com.github.damontecres.wholphin.leanback.settings

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Settings, SubtitleSettings, UserAppPreferences, ProfilePicture, HomeSettings, License, Debug and UpdateApp.
 * Return null for any destination this area does not own.
 */
object SettingsScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? = null
}
