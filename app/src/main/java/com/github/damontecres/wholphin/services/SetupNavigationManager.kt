package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.model.JellyfinServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages navigating for setup
 *
 * No-Compose UI (2026-11-22): [backStack] is an [ObservableBackStack] (always one entry); the shell's setup layer
 * follows [backStackFlow].
 */
@Singleton
class SetupNavigationManager
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
    ) {
        private val _backStackFlow = MutableStateFlow<List<SetupDestination>>(listOf(SetupDestination.Loading))

        /** The setup back stack after every change, for the shell */
        val backStackFlow: StateFlow<List<SetupDestination>> = _backStackFlow.asStateFlow()

        var backStack: MutableList<SetupDestination> = observable(listOf(SetupDestination.Loading))
            set(value) {
                val list = observable(value.toList())
                field = list
                _backStackFlow.value = list.snapshot()
            }

        private fun observable(initial: Collection<SetupDestination>): ObservableBackStack<SetupDestination> =
            ObservableBackStack(initial) { _backStackFlow.value = it }

        /**
         * Go to the specified [SetupDestination]
         */
        fun navigateTo(destination: SetupDestination) {
            backStack[0] = destination
            log()
            if (destination !is SetupDestination.AppContent) {
                navigationManager.reloadHome()
            }
        }

        private fun log() {
            val dest = backStack.lastOrNull().toString()
            Timber.i("Current setup destination: %s", dest)
            ACRA.errorReporter.putCustomData("setupDestination", dest)
        }
    }

@Serializable
sealed interface SetupDestination {
    @Serializable
    data object Loading : SetupDestination

    @Serializable
    data object ServerList : SetupDestination

    @Serializable
    data class UserList(
        val server: JellyfinServer,
    ) : SetupDestination

    @Serializable
    data class AppContent(
        val current: CurrentUser,
    ) : SetupDestination
}
