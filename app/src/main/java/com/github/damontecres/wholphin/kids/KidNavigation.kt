package com.github.damontecres.wholphin.kids

import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

/*
 * A kids profile has no Settings (owner 2026-10-23): not in the side menu, and no other way in (a remote client's "go to
 * settings", a page left on the back stack). Kid and unknown sessions alike (fail closed, plan 4.1). Leaving a kids
 * profile stays where it was: the profile avatar at the top of the menu opens Select user.
 */
object KidNavigation {
    /** Settings and the pages only Settings opens */
    fun isSettingsPage(destination: Destination): Boolean =
        when (destination) {
            is Destination.Settings,
            Destination.HomeSettings,
            is Destination.SubtitleSettings,
            Destination.UserAppPreferences,
            Destination.ProfilePicture,
            Destination.License,
            -> true

            else -> false
        }

    /** Whether a session ([kidOrUnknown]: a kid's, or one not known to be an adult's) may open [destination] */
    fun mayOpen(
        destination: Destination,
        kidOrUnknown: Boolean,
    ): Boolean = !(BuildConfig.KID_PROFILES_ENABLED && kidOrUnknown && isSettingsPage(destination))
}

/** Keeps a kid session out of the Settings pages, whichever way it got there ([KidNavigation]) */
@HiltViewModel
class KidPageGuardViewModel
    @Inject
    constructor(
        gate: KidSessionGate,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        val isKidSession: StateFlow<Boolean> = gate.isKidSession

        fun leave(destination: Destination) {
            Timber.i("Kid session kept out of %s", destination)
            navigationManager.goToHome()
        }
    }
