package com.github.damontecres.wholphin.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /** Fail closed (review): only a session known to be an adult's counts as one; no session at all is not an adult's */
    fun kidOrUnknown(current: CurrentUser?): Boolean = current?.user?.profileKind != ProfileKind.ADULT
}

/**
 * Keeps a kid session out of the Settings pages, whichever way it got there ([KidNavigation]). Read from the session
 * itself, so no session (or one not known to be an adult's) is kept out too.
 */
@HiltViewModel
class KidPageGuardViewModel
    @Inject
    constructor(
        serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        val isKidOrUnknown: StateFlow<Boolean> =
            serverRepository.current
                .map { KidNavigation.kidOrUnknown(it) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, KidNavigation.kidOrUnknown(serverRepository.current.value))

        /** Whether [destination] may show for the session now */
        fun mayShow(destination: Destination): Boolean = KidNavigation.mayOpen(destination, isKidOrUnknown.value)

        fun leave(destination: Destination) {
            Timber.i("Kid session kept out of %s", destination)
            navigationManager.goToHome()
        }
    }
