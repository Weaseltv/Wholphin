package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Listens for JF user switching in the app to also switch other settings like Seerr user/server
 */
@ActivityScoped
class UserSwitchListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val seerrServerRepository: SeerrServerRepository,
        private val homeSettingsService: HomeSettingsService,
        private val navigationManager: NavigationManager,
        private val setupNavigationManager: SetupNavigationManager,
        private val accountAvatars: AccountAvatars,
    ) {
        init {
            context as AppCompatActivity
            if (BuildConfig.DISCOVER_ENABLED) {
                // WeaselFin: a TV app is rarely killed, so a Seerr sign-in that failed
                // (server down, or the account refused until an admin fixed it) used to
                // stay failed, with Requests gone, until the customer relaunched the
                // app. Try again whenever the activity comes to the foreground; the
                // repository throttles this so it cannot hammer the request server.
                context.lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            context.lifecycleScope.launchIO {
                                seerrServerRepository.reconnectIfNeeded()
                            }
                        }
                    },
                )
            }
            if (BuildConfig.KID_PROFILES_ENABLED) {
                context.lifecycleScope.launchDefault { rebuildOnCeilingChange() }
            }
            context.lifecycleScope.launchDefault {
                // collectLatest: a newer session (or the chooser teardown) cancels the previous user's
                // in-flight Seerr connect and home settings load, so their late results are dropped
                serverRepository.currentUserFlow.collectLatest { user ->
                    Timber.d("New user")
                    seerrServerRepository.clear()
                    homeSettingsService.currentSettings.update { HomePageResolvedSettings.EMPTY }
                    if (user != null) {
                        switchUser(user)
                    }
                }
            }
        }

        /**
         * Kid content rules (contract section 10, review round 3): when the session epoch moves while the same profile
         * stays signed in (its ceiling was refreshed), every screen goes: the back stack is cleared to a fresh Home and
         * the app content is rebuilt for the refreshed session, so no pager or list from the old ceiling stays usable.
         * App-wide lists need nothing here: kid and unknown sessions never use the saved Collections lists or genre
         * backdrops (review rounds 6 and 7), and adults keep theirs as before. A switch to another profile goes through
         * the chooser (or a deep link), which already rebuilds everything.
         */
        private suspend fun rebuildOnCeilingChange() {
            var last: Pair<Long, UUID?>? = null
            serverRepository.sessionEpoch.collect { epoch ->
                // The epoch first, then the session it covers
                val current = serverRepository.current.value
                val previous = last
                last = epoch to current?.user?.id
                if (previous == null || previous.first == epoch) return@collect
                if (current == null || previous.second != current.user.id) return@collect
                withContext(WholphinDispatchers.Main) {
                    // Only while the app content is shown, never over the chooser or sign-in
                    if (setupNavigationManager.backStack.firstOrNull() !is SetupDestination.AppContent) return@withContext
                    Timber.i("Kid content rules: the ceiling changed, rebuilding from Home")
                    navigationManager.reloadHome()
                    setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                }
            }
        }

        private suspend fun switchUser(user: JellyfinUser) =
            supervisorScope {
                // Switch the locale to either the user's choice or the system default (empty)
                val localeList =
                    user.uiLanguage?.let { LocaleListCompat.forLanguageTags(it) }
                        ?: LocaleListCompat.getEmptyLocaleList()
                Timber.i("Switching locale to %s", localeList)
                withContext(WholphinDispatchers.Main) {
                    AppCompatDelegate.setApplicationLocales(localeList)
                }

                // Check for home settings
                launchIO {
                    homeSettingsService.loadCurrentSettings(user.id, user)
                }
                if (BuildConfig.KID_PROFILES_ENABLED) {
                    // Contract 11.1: an adult without a picture gets a random one, once per user per TV (also when
                    // the select-user page never shows)
                    launchIO {
                        serverRepository.currentServer?.let { accountAvatars.assignMissing(it.url, listOf(user)) }
                    }
                }
                if (BuildConfig.DISCOVER_ENABLED) {
                    // Check for seerr server. The sign-in itself lives in the
                    // repository so the activity-start retry above can run it too.
                    launchIO {
                        seerrServerRepository.connectForUser(user)
                    }
                }
            }
    }
