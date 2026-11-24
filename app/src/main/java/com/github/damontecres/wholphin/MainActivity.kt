package com.github.damontecres.wholphin

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.core.DataStore
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.ProfileChooserLock
import com.github.damontecres.wholphin.leanback.shell.AppShell
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.shell.InjectedLongPress
import com.github.damontecres.wholphin.leanback.shell.NavHost
import com.github.damontecres.wholphin.leanback.shell.NavHostState
import com.github.damontecres.wholphin.leanback.shell.OrphanPageFragment
import com.github.damontecres.wholphin.leanback.shell.ScreenHost
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.AppUpgradeHandler
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedInvalidationService
import com.github.damontecres.wholphin.services.DeviceProfileService
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.IntentResult
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.KeepScreenOnService
import com.github.damontecres.wholphin.services.LatestNextUpSchedulerService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlaybackLifecycleObserver
import com.github.damontecres.wholphin.services.RefreshRateService
import com.github.damontecres.wholphin.services.ServerEventListener
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.services.SuggestionsSchedulerService
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.UserSwitchListener
import com.github.damontecres.wholphin.services.WhatsNewService
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.tvprovider.TvProviderSchedulerService
import com.github.damontecres.wholphin.ui.CoilConfig
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.PlayExternalViewModel
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.requestSerializersModule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours

/**
 * The app's single activity. No-Compose UI (2026-11-22): its content is the View frame of `leanback.shell.AppShell`
 * (setup layer, side menu, pages, backdrop, overlays); it implements [ScreenHost] for the pages.
 */
@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    ScreenHost {
    private val viewModel: MainActivityViewModel by viewModels()
    private val playExternalViewModel: PlayExternalViewModel by viewModels()

    @Inject
    lateinit var userPreferencesDataStore: DataStore<AppPreferences>

    @Inject
    lateinit var userPreferencesService: UserPreferencesService

    @AuthOkHttpClient
    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var navigationManager: NavigationManager

    @Inject
    lateinit var setupNavigationManager: SetupNavigationManager

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var coachMarkTour: CoachMarkTour

    @Inject
    lateinit var whatsNewService: WhatsNewService

    @Inject
    lateinit var playbackLifecycleObserver: PlaybackLifecycleObserver

    @Inject
    lateinit var refreshRateService: RefreshRateService

    @Inject
    lateinit var userSwitchListener: UserSwitchListener

    @Inject
    lateinit var tvProviderSchedulerService: TvProviderSchedulerService

    @Inject
    lateinit var suggestionsSchedulerService: SuggestionsSchedulerService

    @Inject
    lateinit var latestNextUpSchedulerService: LatestNextUpSchedulerService

    @Inject
    lateinit var backdropService: BackdropService

    @Inject
    lateinit var navDrawerService: NavDrawerService

    @Inject
    lateinit var homeSettingsService: HomeSettingsService

    // Note: unused but injected to ensure it is created
    @Inject
    lateinit var serverEventListener: ServerEventListener

    // Note: unused but injected to ensure it is created
    @Inject
    lateinit var datePlayedInvalidationService: DatePlayedInvalidationService

    @Inject
    lateinit var keepScreenOnService: KeepScreenOnService

    @Inject lateinit var requestStatusRepository: com.github.damontecres.wholphin.services.RequestStatusRepository
    private var requestStatusJob: Job? = null
    private var updateCheckJob: Job? = null

    @Inject
    lateinit var intentService: IntentService

    @Inject
    lateinit var profileChooserLock: ProfileChooserLock

    private var signInAutomatically = true
    private var playerBackend: PlayerBackend? = null

    private val json =
        Json {
            classDiscriminator = "_type"
            serializersModule = requestSerializersModule
        }

    private val fragmentFactory = ShellFragmentFactory()
    private lateinit var shell: AppShell

    /** The signed-in user's preferences for the pages (null until first read) */
    private val preferencesState = MutableStateFlow(lastPreferences)
    override val userPreferences: StateFlow<UserPreferences?> = preferencesState.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pages a recreated activity restores keep their destinations through NavHostState; after the process was
        // killed those are gone, so stand-ins are restored instead and the shell builds the pages again
        val savedKeys = savedInstanceState?.getStringArrayList(KEY_PAGE_KEYS)
        fragmentFactory.dropPages = savedInstanceState != null && (savedKeys == null || savedKeys.any { !NavHostState.has(it) })
        supportFragmentManager.fragmentFactory = fragmentFactory
        super.onCreate(savedInstanceState)
        fragmentFactory.dropPages = false
        instance = this
        Timber.i("MainActivity.onCreate: savedInstanceState is null=${savedInstanceState == null}")
        lifecycle.addObserver(playbackLifecycleObserver)
        whatsNewService.check()

        val backStackStr = savedInstanceState?.getString(KEY_BACK_STACK)
        val restoredBackStack =
            if (backStackStr != null) {
                try {
                    json.decodeFromString<List<Destination>>(backStackStr)
                } catch (ex: Exception) {
                    // Best-effort: a previously persisted back stack we can no longer decode
                    // must not crash startup; fall back to a fresh start destination.
                    Timber.w(ex, "Could not restore back stack; starting fresh")
                    null
                }
            } else {
                null
            }
        if (restoredBackStack != null) {
            Timber.d("Restoring back stack")
            var backStack = restoredBackStack
            if (!playExternalViewModel.launched.value) {
                val lastDest = backStack.lastOrNull()
                if (lastDest.isPlayback) {
                    Timber.v("Restoring back stack without playback")
                    backStack = backStack.toMutableList().apply { removeAt(lastIndex) }
                }
            }
            navigationManager.backStack = backStack.toMutableList()
        } else {
            navigationManager.backStack = mutableListOf(Destination.Home())
        }

        viewModel.serverRepository.currentUserFlow
            .onEach { user ->
                withContext(WholphinDispatchers.Main) {
                    // The user-guide capture build (Alpha only) never blocks screen capture.
                    if (user?.hasPin == true && !BuildConfig.GUIDE_CAPTURE) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }.catch { ex ->
                Timber.e(ex, "Error with settings flag secure")
            }.launchIn(lifecycleScope)

        keepScreenOnService.keepScreenOn
            .onEach { keepScreenOn ->
                Timber.v("keepScreenOn: %s", keepScreenOn)
                withContext(WholphinDispatchers.Main) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }.catch { ex ->
                Timber.e(ex, "Error with keepScreenOn")
            }.launchIn(lifecycleScope)

        userPreferencesDataStore.data.collectLatestIn(lifecycleScope) { prefs ->
            signInAutomatically = prefs.signInAutomatically
            playerBackend = prefs.playbackPreferences.playerBackend
        }

        lifecycleScope.launch {
            userPreferencesService.flow.collect {
                lastPreferences = it
                preferencesState.value = it
            }
        }

        viewModel.appStart(intent)

        AppShell.dropRestoredSetup(this)
        shell =
            AppShell(
                this,
                AppShell.Deps(
                    navigationManager = navigationManager,
                    setupNavigationManager = setupNavigationManager,
                    backdropService = backdropService,
                    updateChecker = updateChecker,
                    requestStatusRepository = requestStatusRepository,
                    coachMarkTour = coachMarkTour,
                    whatsNewService = whatsNewService,
                    navDrawerService = navDrawerService,
                    homeSettingsService = homeSettingsService,
                    serverRepository = viewModel.serverRepository,
                    userPreferences = userPreferences,
                    hideContentOnStop = ::hideContentOnStop,
                    installImageLoader = { prefs ->
                        CoilConfig.install(
                            prefs = prefs.appPreferences,
                            okHttpClient = okHttpClient,
                            debugLogging = false,
                            enableCache = true,
                        )
                    },
                ),
            )
        setContentView(shell.root)
        onBackPressedDispatcher.addCallback(this, shell.backCallback)
        shell.start()
    }

    /**
     * Kid profiles (plan 4.5): on a TV with more than one profile, hide the last profile's screen when the app
     * stops so it never flashes on return, unless the stop is for the external player or one of the app's own
     * activities
     */
    private fun hideContentOnStop(): Boolean =
        profileChooserLock.enabled &&
            profileChooserLock.multiProfile.value &&
            !playExternalViewModel.launched.value &&
            !profileChooserLock.transientLaunchPending

    override fun clearBackdrop() = shell.clearBackdrop()

    override fun focusNavDrawer(): Boolean = shell.focusNavDrawer()

    override fun startActivityForResult(
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ) {
        // Trailers, voice search, the installer, the external player...: returning from these is exempt
        // from the "always return to the profile screen" rule
        profileChooserLock.onTransientLaunch()
        super.startActivityForResult(intent, requestCode, options)
    }

    private val injectedLongPress = InjectedLongPress()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The coach-mark tour takes the remote while it runs, so nothing moves underneath it
        if (coachMarkTour.onKey(event)) return true
        // OK long-pressed by a phone remote app or adb arrives as one flagged event: open the long-click action
        if (injectedLongPress.onKey(event, currentFocus?.findFocus())) return true
        // Then the top page's own key handling (the old page-level onPreviewKeyEvent), then normal dispatch
        if (::shell.isInitialized && shell.dispatchPreviewKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::shell.isInitialized) shell.onWindowFocusChanged()
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume")
        profileChooserLock.onActivityResumed()
        viewModel.appResume()
        if (::shell.isInitialized) shell.onWindowFocusChanged()
    }

    override fun onRestart() {
        super.onRestart()
        Timber.d("onRestart")
        val exempt = playExternalViewModel.launched.value or profileChooserLock.consumeExemptReturn()
        viewModel.appStart(null, fromBackground = true, exemptReturn = exempt)
        if (!playExternalViewModel.launched.value) {
            // If restarting during playback that is not external, go back a page
            val lastDest = navigationManager.backStack.lastOrNull()
            if (lastDest.isPlayback) {
                Timber.v("onRestart: go back from playback")
                navigationManager.goBack()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.d("onStop")
        profileChooserLock.onActivityStopped(externalPlayer = playExternalViewModel.launched.value)
        tvProviderSchedulerService.launchOneTimeRefresh()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause")
        if (::shell.isInitialized) shell.onWindowFocusChanged()
    }

    override fun onStart() {
        super.onStart()
        Timber.d("onStart")
        profileChooserLock.onActivityStarted()
        startPeriodicUpdateCheck()
        requestStatusJob?.cancel()
        requestStatusJob =
            lifecycleScope.launchIO {
                repeatOnLifecycle(Lifecycle.State.STARTED) { requestStatusRepository.runForeground() }
            }
    }

    /**
     * WeaselFin: check for an update when the app comes to the foreground and then every
     * [UPDATE_CHECK_INTERVAL] while it stays there. TV users rarely fully close the app, so a
     * check only at start could miss a release for days. The loop stops while the activity is
     * stopped and restarts on the next start; the popup's own 12-hour rule still decides
     * whether the same release is offered again.
     */
    private fun startPeriodicUpdateCheck() {
        updateCheckJob?.cancel()
        updateCheckJob =
            lifecycleScope.launchDefault {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Back from "Install unknown apps" (perhaps restarted by Android): carry on
                    if (UpdateChecker.ACTIVE) updateChecker.checkPendingInstall()
                    while (isActive) {
                        val appPreferences = userPreferencesDataStore.data.first()
                        if (UpdateChecker.ACTIVE && appPreferences.autoCheckForUpdates) {
                            try {
                                updateChecker.maybePromptForUpdate(appPreferences.updateUrl)
                            } catch (ex: Exception) {
                                Timber.w(ex, "Exception during update check")
                            }
                        }
                        delay(UPDATE_CHECK_INTERVAL)
                    }
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Timber.d("onSaveInstanceState")
        try {
            val str = json.encodeToString(navigationManager.backStack.toList())
            outState.putString(KEY_BACK_STACK, str)
        } catch (ex: Exception) {
            // Best-effort: some destinations carry types we can't serialize; skip persisting
            // the back stack rather than crashing in onSaveInstanceState.
            Timber.w(ex, "Could not persist back stack; skipping")
        }
        outState.putStringArrayList(
            KEY_PAGE_KEYS,
            ArrayList(supportFragmentManager.fragments.mapNotNull { it.tag?.takeIf { tag -> tag.startsWith(NavHost.TAG_PREFIX) } }),
        )
        outState.putBoolean(KEY_EXTERNAL_PLAYER, playerBackend == PlayerBackend.EXTERNAL_PLAYER)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Timber.d("onRestoreInstanceState")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("onDestroy")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.d("onConfigurationChanged: newConfig=%s", newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.v("onNewIntent")
        setIntent(intent)
        viewModel.appStart(intent)
    }

    fun changeDisplayMode(modeId: Int) {
        lifecycleScope.launch(WholphinDispatchers.Main + ExceptionHandler(autoToast = true)) {
            try {
                val attrs = window.attributes
                if (attrs.preferredDisplayModeId != modeId) {
                    Timber.d("Switch preferredDisplayModeId to %s", modeId)
                    window.attributes = attrs.apply { preferredDisplayModeId = modeId }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error switching preferredDisplayModeId to %s", modeId)
                Toast
                    .makeText(this@MainActivity, "Error changing display mode", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /** Restores a stand-in for pages whose destinations were lost with the process (see [onCreate]) */
    private class ShellFragmentFactory : FragmentFactory() {
        var dropPages = false

        override fun instantiate(
            classLoader: ClassLoader,
            className: String,
        ): Fragment {
            if (dropPages && DestinationFragment::class.java.isAssignableFrom(loadFragmentClass(classLoader, className))) {
                return OrphanPageFragment()
            }
            return super.instantiate(classLoader, className)
        }
    }

    companion object {
        private const val KEY_BACK_STACK = "backStack"
        private const val KEY_PAGE_KEYS = "lbShellPages"
        private val UPDATE_CHECK_INTERVAL = 1.hours
        private const val KEY_EXTERNAL_PLAYER = "extPlayer"

        /** The last preferences read in this process, so pages a recreated activity restores can read them at once */
        @Volatile
        private var lastPreferences: UserPreferences? = null

        lateinit var instance: MainActivity
            private set
    }
}

@HiltViewModel
class MainActivityViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val preferences: DataStore<AppPreferences>,
        val serverRepository: ServerRepository,
        private val setupNavigationManager: SetupNavigationManager,
        private val navigationManager: NavigationManager,
        private val deviceProfileService: DeviceProfileService,
        private val backdropService: BackdropService,
        private val appUpgradeHandler: AppUpgradeHandler,
        private val intentService: IntentService,
        private val kidSessionGate: KidSessionGateImpl,
        private val profileChooserLock: ProfileChooserLock,
    ) : ViewModel() {
        private val mutex = Mutex()

        /**
         * @param fromBackground the activity is restarting (Home, another app, sleep, the system screensaver)
         * @param exemptReturn returning from the external player or one of the app's own activities
         */
        fun appStart(
            intent: Intent?,
            fromBackground: Boolean = false,
            exemptReturn: Boolean = false,
        ) {
            viewModelScope.launchDefault {
                mutex.withLock {
                    if (BuildConfig.KID_PROFILES_ENABLED) {
                        try {
                            kidAppStart(intent, fromBackground, exemptReturn)
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error during appStart")
                            setupNavigationManager.navigateTo(SetupDestination.ServerList)
                        }
                        return@withLock
                    }
                    try {
                        val result = intent?.let { intentService.parseIntent(intent) }
                        when (result) {
                            is IntentResult.Error -> {
                                val current = serverRepository.current.value
                                val destination =
                                    if (current != null) {
                                        SetupDestination.UserList(current.server)
                                    } else {
                                        SetupDestination.ServerList
                                    }
                                setupNavigationManager.navigateTo(destination)
                                Timber.e("Error parsing intent: %s", result.message)
                                showToast(context, result.message)
                                return@withLock
                            }

                            is IntentResult.Target -> {
                                val current = serverRepository.current.value
                                if (current != null) {
                                    Timber.i("Received valid intent, switching to AppContent")

                                    if (result.addHomeToBackStack) {
                                        navigationManager.reloadHome()
                                    } else {
                                        navigationManager.backStack.clear()
                                    }
                                    navigationManager.backStack.addAll(result.destinations)

                                    setupNavigationManager.navigateTo(
                                        SetupDestination.AppContent(current),
                                    )
                                } else {
                                    // This should never happen, but just reset app state if it does
                                    setupNavigationManager.navigateTo(SetupDestination.ServerList)
                                    Timber.e("Error parsing intent, no user is active")
                                    showToast(context, "An error occurred parsing the intent")
                                }
                                return@withLock
                            }

                            IntentResult.Unavailable -> {
                                showUnavailable()
                                return@withLock
                            }

                            IntentResult.NoOp,
                            null,
                            -> {
                                // No-op, proceed below
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error parsing intent")
                    }

                    try {
                        val needUpgrade = appUpgradeHandler.needUpgrade()
                        if (needUpgrade) {
                            showToast(
                                context,
                                context.getString(
                                    R.string.updated_toast,
                                    appUpgradeHandler.currentVersion.toString(),
                                ),
                            )
                            appUpgradeHandler.run()
                        }
                        appUpgradeHandler.copySubfont(false)
                        val prefs =
                            preferences.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                        val profileProtected =
                            serverRepository.current.value
                                ?.user
                                ?.isProtected == true
                        if (prefs.signInAutomatically && !profileProtected) {
                            val current =
                                serverRepository.restoreSession(
                                    prefs.currentServerId?.toUUIDOrNull(),
                                    prefs.currentUserId?.toUUIDOrNull(),
                                )
                            if (current != null) {
                                if (current.user.isProtected) {
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.UserList(
                                            current.server,
                                        ),
                                    )
                                } else {
                                    // Restored
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.AppContent(
                                            current,
                                        ),
                                    )
                                }
                            } else {
                                // Did not restore
                                setupNavigationManager.navigateTo(SetupDestination.ServerList)
                            }
                        } else {
                            setupNavigationManager.navigateTo(SetupDestination.Loading)
                            backdropService.clearBackdrop()
                            val currentServerId = prefs.currentServerId?.toUUIDOrNull()
                            if (currentServerId != null) {
                                val currentServer =
                                    serverRepository.serverDao.getServer(currentServerId)?.server
                                if (currentServer != null) {
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.UserList(
                                            currentServer,
                                        ),
                                    )
                                } else {
                                    setupNavigationManager.navigateTo(SetupDestination.ServerList)
                                }
                            } else {
                                setupNavigationManager.navigateTo(SetupDestination.ServerList)
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error during appStart")
                        setupNavigationManager.navigateTo(SetupDestination.ServerList)
                    }
                }
            }
            viewModelScope.launchDefault {
                // Create the mediaCodecCapabilitiesTest if needed
                deviceProfileService.mediaCodecCapabilitiesTest.supportsAVC()
            }
        }

        /**
         * Kid profiles start/return (plan 4.1, 4.2, 4.5). Reads the saved row and the switch gate before any
         * restore; with more than one profile saved for the server every return lands on the profile screen.
         */
        private suspend fun kidAppStart(
            intent: Intent?,
            fromBackground: Boolean,
            exemptReturn: Boolean,
        ) {
            val prefs = preferences.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
            val current = serverRepository.current.value
            // A deep link's own server first; after the chooser cleared the saved ids and the process died, the
            // pinned (or only) server, so the profile count, and with it the chooser-first rule, still applies
            // Same target resolution as IntentService's restore: only a complete server + user pair counts
            val target = intent?.let { intentService.intentTarget(it) }
            val serverId =
                target?.serverId
                    ?: current?.server?.id
                    ?: prefs.currentServerId?.toUUIDOrNull()
                    ?: profileChooserLock.lastProfile?.server?.id
                    ?: profileChooserLock.fallbackServerId()
            val multiProfile = profileChooserLock.appliesTo(serverId)

            if (intent != null && !intentService.isNormalLaunch(intent)) {
                if (multiProfile) {
                    // Launcher tile / deep link: open the chooser holding the target (plan 4.5)
                    val owner =
                        target?.userId
                            ?: current?.user?.id
                            ?: prefs.currentUserId?.toUUIDOrNull()
                            ?: profileChooserLock.lastProfile?.user?.id
                    profileChooserLock.hold(intent, owner)
                    showChooser(serverId)
                } else {
                    handleIntentResult(intentService.parseIntent(intent))
                }
                return
            }
            if (fromBackground && exemptReturn && current != null) {
                // Back from the external player or a transient activity: carry on where we were
                return
            }

            val needUpgrade = appUpgradeHandler.needUpgrade()
            if (needUpgrade) {
                showToast(
                    context,
                    context.getString(R.string.updated_toast, appUpgradeHandler.currentVersion.toString()),
                )
                appUpgradeHandler.run()
            }
            appUpgradeHandler.copySubfont(false)

            if (multiProfile) {
                showChooser(serverId)
                return
            }
            val profileProtected = current?.user?.isProtected == true
            if (prefs.signInAutomatically && !profileProtected) {
                val lastServerId = prefs.currentServerId?.toUUIDOrNull() ?: current?.server?.id
                val lastUserId = prefs.currentUserId?.toUUIDOrNull() ?: current?.user?.id
                val restored = kidSessionGate.restoreGated(lastServerId, lastUserId)
                if (restored != null) {
                    setupNavigationManager.navigateTo(SetupDestination.AppContent(restored))
                } else if (lastServerId != null && lastUserId != null) {
                    // Protected, gated or missing: the chooser decides (PIN, parent unlock)
                    showChooser(lastServerId)
                } else {
                    setupNavigationManager.navigateTo(SetupDestination.ServerList)
                }
            } else {
                showChooser(serverId)
            }
        }

        private suspend fun showChooser(serverId: java.util.UUID?) {
            setupNavigationManager.navigateTo(SetupDestination.Loading)
            backdropService.clearBackdrop()
            val server = serverId?.let { serverRepository.serverDao.getServer(it)?.server }
            if (server != null) {
                setupNavigationManager.navigateTo(SetupDestination.UserList(server))
            } else {
                setupNavigationManager.navigateTo(SetupDestination.ServerList)
            }
        }

        private suspend fun handleIntentResult(result: IntentResult) {
            when (result) {
                is IntentResult.Error -> {
                    val current = serverRepository.current.value
                    setupNavigationManager.navigateTo(
                        if (current != null) SetupDestination.UserList(current.server) else SetupDestination.ServerList,
                    )
                    Timber.e("Error parsing intent: %s", result.message)
                    showToast(context, result.message)
                }

                is IntentResult.Target -> {
                    val current = serverRepository.current.value
                    if (current != null) {
                        if (result.addHomeToBackStack) {
                            navigationManager.reloadHome()
                        } else {
                            navigationManager.backStack.clear()
                        }
                        navigationManager.backStack.addAll(result.destinations)
                        setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                    } else {
                        setupNavigationManager.navigateTo(SetupDestination.ServerList)
                        showToast(context, "An error occurred parsing the intent")
                    }
                }

                IntentResult.Unavailable -> {
                    showUnavailable()
                }

                IntentResult.NoOp -> {}
            }
        }

        /** Kid content rules (contract section 10): a deep link to a hidden title opens home instead */
        private suspend fun showUnavailable() {
            showToast(context, context.getString(R.string.kid_content_item_unavailable))
            val current = serverRepository.current.value
            if (current != null) {
                navigationManager.reloadHome()
                setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
            } else {
                setupNavigationManager.navigateTo(SetupDestination.ServerList)
            }
        }

        fun appResume() {
            viewModelScope.launchDefault {
                mutex.withLock {
                    try {
                        Timber.v("Updating userDto")
                        serverRepository.updateUserDto()
                    } catch (ex: Exception) {
                        Timber.w(ex, "Error during appResume")
                    }
                }
            }
        }
    }

internal val Destination?.isPlayback: Boolean
    get() =
        this is Destination.Playback ||
            this is Destination.PlaybackList ||
            this is Destination.Slideshow
