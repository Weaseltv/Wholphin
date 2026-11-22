package com.github.damontecres.wholphin.leanback.playback

import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination

/**
 * Screens of this area for the shell's ScreenFactory (no-Compose UI). Owns: Playback, PlaybackList and Slideshow.
 * Return null for any destination this area does not own.
 */
object PlaybackScreens {
    fun create(
        destination: Destination,
        preferences: UserPreferences,
    ): DestinationFragment? =
        when (destination) {
            is Destination.Playback -> {
                // Exactly as DestinationContent: the requested backend, else the preference
                val backend = destination.backend ?: preferences.appPreferences.playbackPreferences.playerBackend
                if (backend == PlayerBackend.EXTERNAL_PLAYER) PlayExternalFragment() else PlaybackFragment()
            }

            is Destination.PlaybackList -> {
                if (preferences.appPreferences.playbackPreferences.playerBackend == PlayerBackend.EXTERNAL_PLAYER) {
                    PlayExternalFragment()
                } else {
                    PlaybackFragment()
                }
            }

            is Destination.Slideshow -> {
                SlideshowFragment()
            }

            else -> {
                null
            }
        }
}
