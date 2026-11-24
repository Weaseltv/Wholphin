package com.github.damontecres.wholphin.leanback.settings

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Settings, SubtitleSettings, UserAppPreferences, HomeSettings, License, Debug and UpdateApp.
 * Return null for any destination this area does not own.
 */
object SettingsScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.Settings -> AppSettingsFragment()
            is Destination.SubtitleSettings -> SubtitleSettingsFragment()
            Destination.UserAppPreferences -> UserPreferencesFragment()
            Destination.HomeSettings -> HomeSettingsFragment()
            Destination.License -> LicenseFragment()
            Destination.Debug -> DebugFragment()
            is Destination.UpdateApp -> UpdateFragment()
            else -> null
        }
}
