package com.github.damontecres.wholphin

import android.service.dreams.DreamService
import android.view.View
import android.widget.FrameLayout
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.leanback.shell.ScreensaverView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.RemotePlaybackService
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.ui.CoilConfig
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class WholphinDreamService :
    DreamService(),
    SavedStateRegistryOwner {
    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var screensaverService: ScreensaverService

    @Inject
    lateinit var preferencesDataStore: DataStore<AppPreferences>

    @Inject
    lateinit var api: ApiClient

    @Inject
    lateinit var remotePlaybackService: RemotePlaybackService

    @AuthOkHttpClient
    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val savedStateRegistryController =
        SavedStateRegistryController.create(this).apply {
            performAttach()
        }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Timber.d("onCreate")

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleScope.launchDefault {
            // Kid profiles (plan 4.1): the screensaver only shows the existing session and never restores one
            if (!BuildConfig.KID_PROFILES_ENABLED && serverRepository.current.value == null) {
                val prefs = preferencesDataStore.data.first()
                serverRepository.restoreSession(prefs.currentServerId.toUUIDOrNull(), prefs.currentUserId.toUUIDOrNull())
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.d("onAttachedToWindow")
        val itemFlow = screensaverService.createItemFlow(lifecycleScope)
        // The same View as the in-app screensaver (no-Compose UI, 2026-11-22), with its dim on top
        val root = FrameLayout(this)
        setContentView(root)
        lifecycleScope.launch(WholphinDispatchers.Main) {
            serverRepository.currentUserFlow.collectLatest { user ->
                root.removeAllViews()
                if (user == null) return@collectLatest
                var view: ScreensaverView? = null
                val dim = View(this@WholphinDreamService).apply { setBackgroundColor(0xFF000000.toInt()) }
                try {
                    coroutineScope {
                        val userScope = this
                        preferencesDataStore.data.collect { prefs ->
                            CoilConfig.install(
                                prefs = prefs,
                                okHttpClient = okHttpClient,
                                debugLogging = false,
                                enableCache = true,
                            )
                            AppTheme.update(prefs.interfacePreferences.appThemeColors)
                            val screensaverPrefs = prefs.interfacePreferences.screensaverPreference
                            val v =
                                view ?: ScreensaverView(this@WholphinDreamService).also {
                                    view = it
                                    root.addView(
                                        it,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                    root.addView(
                                        dim,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                        ),
                                    )
                                    it.start(userScope, itemFlow)
                                }
                            v.configure(
                                showClock = screensaverPrefs.showClock,
                                duration = screensaverPrefs.duration.milliseconds,
                                animate = screensaverPrefs.animate,
                            )
                            dim.visibility = if (screensaverPrefs.dimEnabled) View.VISIBLE else View.GONE
                            dim.alpha = screensaverPrefs.dimPercent / 100f
                        }
                    }
                } finally {
                    view?.stop()
                }
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Timber.d("onDreamingStarted")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        listenForRemotePlay()
    }

    /**
     * While the screensaver is showing, the main activity is stopped and its web socket is closed, so the server
     * would stop offering this device as a "Play On" target. Keep listening here and hand any request to the
     * activity through the playback intent, which also wakes the screen.
     */
    private fun listenForRemotePlay() {
        lifecycleScope.launchIO {
            try {
                if (!remotePlaybackService.isEnabled()) {
                    Timber.v("Remote control disabled, not listening while dreaming")
                    return@launchIO
                }
                // onCreate may still be restoring the session
                var attempts = 0
                while (serverRepository.current.value == null && attempts++ < 10) {
                    delay(500.milliseconds)
                }
                if (serverRepository.current.value == null || api.accessToken == null) {
                    Timber.v("No user signed in, not listening for remote play while dreaming")
                    return@launchIO
                }
                try {
                    api.sessionApi.postFullCapabilities(data = remotePlaybackService.capabilities(true))
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Error posting capabilities while dreaming")
                }
                Timber.v("Listening for remote play while dreaming")
                // Re-post after a reconnect too, since a server restart forgets capabilities
                launchIO {
                    api.webSocket.state
                        .filter { it is SocketApiState.Connected }
                        .collect {
                            try {
                                api.sessionApi.postFullCapabilities(data = remotePlaybackService.capabilities(true))
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                Timber.w(ex, "Error re-posting capabilities while dreaming")
                            }
                        }
                }
                api.webSocket
                    .subscribe<PlayMessage>()
                    .catch { ex -> Timber.e(ex, "Error in screensaver play subscription") }
                    .collect { message ->
                        val request = message.data ?: return@collect
                        if (remotePlaybackService.isPlayOnLocked()) {
                            // Kid profiles: dreaming means the app is in the background
                            showToast(this@WholphinDreamService, getString(R.string.remote_play_locked))
                            return@collect
                        }
                        val intent = remotePlaybackService.createLaunchIntent(request)
                        if (intent == null) {
                            Timber.w("Ignoring remote play request while dreaming: %s", request)
                            return@collect
                        }
                        Timber.i("Remote play request while dreaming, launching playback")
                        withContext(WholphinDispatchers.Main) {
                            try {
                                startActivity(intent)
                                finish()
                            } catch (ex: Exception) {
                                Timber.e(ex, "Could not launch playback from the screensaver")
                            }
                        }
                    }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Error listening for remote play while dreaming")
            }
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        Timber.d("onDreamingStopped")
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
