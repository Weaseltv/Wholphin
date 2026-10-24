package com.github.damontecres.wholphin.kids

import android.content.Intent
import android.net.Uri
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.IntentResult
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A launcher tile or deep link held while the profile screen is up (plan 4.5) */
data class HeldTarget(
    val intent: Intent,
    val ownerUserId: UUID?,
)

/**
 * "Always return to the profile chooser" (plan 4.5): when more than one profile is saved for the current
 * server, every return to the app lands on the profile screen. Tracks whether the app is in the background,
 * the app's own transient activities (exempt), and launcher tiles/deep links held until a profile is picked.
 */
@Singleton
class ProfileChooserLock
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val serverDao: JellyfinServerDao,
        private val setupNavigationManager: SetupNavigationManager,
        private val navigationManager: NavigationManager,
        private val intentService: Lazy<IntentService>,
        @param:DefaultCoroutineScope scope: CoroutineScope,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        val enabled: Boolean get() = BuildConfig.KID_PROFILES_ENABLED

        /** The last profile that had a session, kept through the chooser teardown */
        @Volatile
        var lastProfile: CurrentUser? = null
            private set

        /**
         * Whether the current server has more than one saved profile. Keeps its last value while no session
         * is active, so it still answers on the chooser and in the background.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val multiProfile: StateFlow<Boolean> =
            serverRepository.current
                .onEach { if (it != null) lastProfile = it }
                .flatMapLatest { current ->
                    val serverId = current?.server?.id ?: lastProfile?.server?.id
                    if (serverId == null) {
                        flowOf(false)
                    } else {
                        serverDao.getUsersFlow(serverId).map { it.size > 1 }
                    }
                }.stateIn(scope, SharingStarted.Eagerly, false)

        /** True while MainActivity is stopped for anything but the external player or a transient activity */
        @Volatile
        var inBackground: Boolean = false
            private set

        @Volatile
        private var transientLaunch = false

        @Volatile
        private var stoppedExempt = false

        /** A transient activity was started and the activity hasn't resumed since */
        val transientLaunchPending: Boolean get() = transientLaunch

        private val _held = MutableStateFlow<HeldTarget?>(null)
        val held: StateFlow<HeldTarget?> = _held

        /** Whether the rule applies to [serverId]: more than one saved profile */
        suspend fun appliesTo(serverId: UUID?): Boolean {
            if (!enabled || serverId == null) return false
            return withContext(ioDispatcher) { serverDao.countUsers(serverId) } > 1
        }

        /**
         * The server a start or deep link is about when neither the session nor the saved current ids name one
         * (the chooser cleared them, then the process died): the build's pinned server, else the only saved one
         */
        suspend fun fallbackServerId(): UUID? {
            val servers = withContext(ioDispatcher) { serverDao.getServers() }.map { it.server }
            val pinnedUrl = BuildConfig.DEFAULT_SERVER_URL
            if (pinnedUrl.isNotBlank()) {
                val pinnedHost = Uri.parse(pinnedUrl).host
                servers.firstOrNull { Uri.parse(it.url).host.equals(pinnedHost, ignoreCase = true) }?.let { return it.id }
            }
            return servers.singleOrNull()?.id
        }

        /** MainActivity is about to start one of the app's own activities (trailer, voice search, installer...) */
        fun onTransientLaunch() {
            transientLaunch = true
        }

        fun onActivityStopped(externalPlayer: Boolean) {
            stoppedExempt = externalPlayer || transientLaunch
            if (!stoppedExempt) inBackground = true
        }

        /** @return whether the coming restart returns from the external player or a transient activity */
        fun consumeExemptReturn(): Boolean {
            val value = stoppedExempt
            stoppedExempt = false
            return value
        }

        fun onActivityStarted() {
            inBackground = false
        }

        fun onActivityResumed() {
            // An overlay that only paused the activity doesn't exempt a later return
            transientLaunch = false
        }

        /** The profile screen is showing (no profile in use) */
        val chooserShowing: Boolean
            get() = setupNavigationManager.backStack.firstOrNull() !is SetupDestination.AppContent

        /**
         * Play On is refused while the chooser lock is up: the profile screen is showing or the app is in the
         * background (plan 4.5)
         */
        fun isPlayOnLocked(): Boolean = enabled && multiProfile.value && (inBackground || chooserShowing)

        fun hold(
            intent: Intent,
            ownerUserId: UUID?,
        ) {
            Timber.i("Holding intent until a profile is picked")
            _held.value = HeldTarget(intent, ownerUserId)
        }

        fun clearHeld() {
            _held.value = null
        }

        /**
         * After a profile was picked on the chooser: continue a held tile/deep link if [current] owns it,
         * otherwise open that profile's home. [isCurrent] is asked on the main thread, right where it navigates
         * (review round 4): if the chooser flow that published [current] was left meanwhile, nothing navigates and a
         * held target stays held.
         *
         * @return whether it navigated
         */
        suspend fun navigateAfterSwitch(
            current: CurrentUser,
            isCurrent: () -> Boolean = { true },
        ): Boolean {
            val held = _held.value
            if (held != null && held.ownerUserId == current.user.id) {
                val result =
                    try {
                        intentService.get().parseIntent(held.intent, sessionReady = true)
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not continue the held intent")
                        null
                    }
                if (result is IntentResult.Target) {
                    return withContext(WholphinDispatchers.Main) {
                        if (!isCurrent()) return@withContext false
                        _held.compareAndSet(held, null)
                        if (result.addHomeToBackStack) {
                            navigationManager.reloadHome()
                        } else {
                            navigationManager.backStack.clear()
                        }
                        navigationManager.backStack.addAll(result.destinations)
                        setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                        true
                    }
                }
            } else if (held != null) {
                Timber.i("Held intent belongs to another profile, going to the picked profile's home")
            }
            return withContext(WholphinDispatchers.Main) {
                if (!isCurrent()) return@withContext false
                if (held != null) _held.compareAndSet(held, null)
                setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                true
            }
        }
    }
