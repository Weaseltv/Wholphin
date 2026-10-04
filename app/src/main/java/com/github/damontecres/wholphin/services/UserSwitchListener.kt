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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import timber.log.Timber
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
            context.lifecycleScope.launchDefault {
                serverRepository.currentUserFlow.collect { user ->
                    Timber.d("New user")
                    seerrServerRepository.clear()
                    homeSettingsService.currentSettings.update { HomePageResolvedSettings.EMPTY }
                    if (user != null) {
                        switchUser(user)
                    }
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
                    homeSettingsService.loadCurrentSettings(user.id)
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
