package com.github.damontecres.wholphin.leanback.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.kids.KidNavigation
import com.github.damontecres.wholphin.kids.KidPageGuardViewModel
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.leanback.setup.SetupScreens
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.defaultHomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.homeMediaAppearanceView
import com.github.damontecres.wholphin.leanback.theme.libraryAccent
import com.github.damontecres.wholphin.leanback.theme.navigationSectionAccent
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropResult
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RequestStatusRepository
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.WhatsNewService
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.HOME_INDEX
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.NavDrawerViewModel
import com.github.damontecres.wholphin.ui.nav.SEARCH_INDEX
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkRotation
import com.github.damontecres.wholphin.util.DebugLogTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The app frame without Compose (replaces `MainContent`, `ApplicationContent`, `NavDrawer`, `Backdrop` and the
 * overlays they hosted). Bottom to top:
 *
 * - setup layer: SetupDestination.Loading (spinner), the server list / profile chooser fragments
 *   (`leanback.setup.SetupScreens`), or the signed-in app layer, cross-fading over 700 ms like the old NavDisplay;
 * - app layer: backdrop, side menu, the pages ([NavHost]), the clock, request chips, the coach-mark tour.
 *
 * The update and What's New popups are dialogs (their own windows). MainActivity creates one shell and forwards keys,
 * BACK and lifecycle; see `runbooks/no-compose/SHELL.md` for what screens get from it.
 */
internal class AppShell(
    private val activity: FragmentActivity,
    private val deps: Deps,
) {
    /** The services the frame uses */
    class Deps(
        val navigationManager: NavigationManager,
        val setupNavigationManager: SetupNavigationManager,
        val backdropService: BackdropService,
        val updateChecker: UpdateChecker,
        val requestStatusRepository: RequestStatusRepository,
        val coachMarkTour: CoachMarkTour,
        val whatsNewService: WhatsNewService,
        val navDrawerService: NavDrawerService,
        val homeSettingsService: HomeSettingsService,
        val serverRepository: ServerRepository,
        val userPreferences: StateFlow<UserPreferences?>,
        /** Kid profiles (plan 4.5): also hide the profile's screen on stop when the profile screen will show on return */
        val hideContentOnStop: () -> Boolean,
        /** Sets up Coil's image loader once the preferences are first read (the old `CoilConfig` composable) */
        val installImageLoader: (UserPreferences) -> Unit,
    )

    private val context: Context = activity
    private val nav get() = deps.navigationManager
    private val coachMarksEnabled = BuildConfig.COACH_MARK_TOUR_ENABLED

    // ---- Views ----
    val root = ShellRootLayout(context)
    private val prefsLoading = FrameLayout(context)
    private val content = FrameLayout(context)
    private val setupLayer = FrameLayout(context).apply { id = R.id.lb_shell_setup }
    private val loadingLayer = loadingSpinner(context)
    private val appLayer = AppLayerLayout(context)
    private val backdrop = BackdropView(context)
    private val menu = SideMenuView(context)
    private val pages = PageContainer(context)
    private val clock = ClockView(context)
    private val chipLayer = FrameLayout(context)
    private val coachOverlay = CoachMarkOverlayView(context, deps.coachMarkTour, deps.navigationManager)

    private val navHost =
        NavHost(
            activity.supportFragmentManager,
            pages,
            object : NavHost.Callbacks {
                override val preferences: UserPreferences? get() = deps.userPreferences.value

                override fun pageStyle(
                    stack: List<Destination>,
                    destination: Destination,
                ): PageStyle = computePageStyle(stack, destination)

                override fun mayOpen(destination: Destination): Boolean = mayOpenPage(destination)

                override fun onBlocked(destination: Destination) {
                    kidGuard.leave(destination)
                }

                override fun clearBackdrop() = this@AppShell.clearBackdrop()

                override fun currentBackdrop(): BackdropResult = deps.backdropService.backdropFlow.value

                override fun restoreBackdrop(backdrop: BackdropResult) {
                    // Main.immediate: done before the page takes focus, so a backdrop the page submits itself wins
                    activity.lifecycleScope.launch(Dispatchers.Main.immediate) { deps.backdropService.restore(backdrop) }
                }

                override fun menuHasFocus(): Boolean = menu.hasFocus()

                override fun onTopChanged(
                    top: DestinationFragment,
                    pop: Boolean,
                ) = this@AppShell.onTopChanged(top, pop)

                override fun layoutPage(
                    fragment: DestinationFragment,
                    view: View,
                ) = this@AppShell.layoutPage(fragment, view)
            },
        )

    private val kidGuard: KidPageGuardViewModel by lazy {
        ViewModelProvider(activity)[KidPageGuardViewModel::class.java]
    }

    // ---- State ----
    private var preferences: UserPreferences? = null
    private var setupShown: SetupDestination? = null
    private var appUser: CurrentUser? = null
    private var appActive = false
    private var menuViewModel: NavDrawerViewModel? = null

    /** Scope of everything tied to the signed-in content; cancelled when it goes */
    private var appScope: CoroutineScope? = null
    private var menuHideJob: Job? = null

    private val updatePrompt = UpdatePromptController(activity, deps.updateChecker, deps.navigationManager)
    private val whatsNew = WhatsNewController(activity, deps.whatsNewService, deps.updateChecker, deps.navigationManager)
    private var chips: RequestChipHost? = null

    /** BACK for the signed-in content; screens' own callbacks (registered later) come first */
    val backCallback =
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }

    init {
        root.id = R.id.lb_shell_root
        root.setBackgroundColor(0xFF15121C.toInt())
        content.visibility = View.GONE
        root.addView(content, matchParent())
        content.addView(setupLayer, matchParent())
        content.addView(loadingLayer, matchParent())
        content.addView(appLayer, matchParent())
        loadingLayer.visibility = View.GONE
        appLayer.visibility = View.GONE
        appLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        appLayer.addView(backdrop, matchParent())
        appLayer.addView(
            menu,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        appLayer.addView(pages, matchParent())
        clock.setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
        appLayer.addView(
            clock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        appLayer.addView(chipLayer, matchParent())
        appLayer.addView(coachOverlay, matchParent())
        menu.visibility = View.GONE
        clock.visibility = View.GONE

        // Before the preferences are read: a black page with a spinner, shown only if it takes over 500 ms
        prefsLoading.setBackgroundColor(0xFF000000.toInt())
        prefsLoading.addView(
            ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(0xBF741BF8.toInt())
            },
            FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER),
        )
        prefsLoading.visibility = View.GONE
        root.addView(prefsLoading, matchParent())

        menu.onOpenFractionChanged = { f -> slidePages(f) }
        coachOverlay.scope = activity.lifecycleScope
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus -> onGlobalFocus(newFocus) }
    }

    private fun matchParent() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    /** Start following the preferences, setup destination, back stack and backdrop */
    fun start() {
        menu.listener = menuListener
        activity.lifecycle.addObserver(lifecycleObserver)
        val scope = activity.lifecycleScope
        // Collected on Main (not Main.immediate), so a page that navigates while it is being created never re-enters
        // the fragment transaction that creates it
        scope.launch(Dispatchers.Main) {
            delay(500)
            if (preferences == null) {
                Timber.i("Showing loading page")
                prefsLoading.visibility = View.VISIBLE
            }
        }
        scope.launch(Dispatchers.Main) {
            deps.userPreferences.filterNotNull().collect { onPreferences(it) }
        }
        scope.launch(Dispatchers.Main) {
            deps.userPreferences.filterNotNull().first()
            deps.setupNavigationManager.backStackFlow.collect { stack -> stack.firstOrNull()?.let { onSetupDestination(it) } }
        }
        scope.launch(Dispatchers.Main) {
            deps.navigationManager.backStackFlow.collect { stack -> if (appActive) navHost.apply(stack) }
        }
        scope.launch(Dispatchers.Main) {
            combine(deps.navDrawerService.state, deps.homeSettingsService.currentSettings) { _, _ -> }.collect {
                if (appActive) navHost.refreshStyles()
            }
        }
        scope.launch(Dispatchers.Main) {
            AppTheme.themeFlow.collect { onThemeChanged() }
        }
        scope.launch(Dispatchers.Main) {
            activity.repeatOnStarted {
                combine(deps.backdropService.backdropFlow, deps.userPreferences.filterNotNull()) { b, p ->
                    b to p.appPreferences.interfacePreferences.backdropStyle
                }.collect { (b, style) -> backdrop.bind(b, style) }
            }
        }
        scope.launch(Dispatchers.Main) {
            kidGuard.isKidOrUnknown.collect {
                // A session that became a kid's is kept out of the Settings page it is on
                val top = navHost.top
                if (appActive && top != null) top.destinationOrNull?.let { d -> if (!mayOpenPage(d)) kidGuard.leave(d) }
            }
        }
        scope.launch(Dispatchers.Main) {
            // Kids profiles get their own loading artwork; remembered for the next app start
            deps.userPreferences.filterNotNull().first()
            deps.serverRepository.current
                .map { current -> current?.user?.let { BuildConfig.KID_PROFILES_ENABLED && it.profileKind == ProfileKind.KID } }
                .distinctUntilChanged()
                .collect { kids -> kids?.let { LoadingArtworkRotation.rememberProfile(activity, it) } }
        }
    }

    private suspend fun FragmentActivity.repeatOnStarted(block: suspend CoroutineScope.() -> Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED, block)
    }

    // ---- Preferences & theme ----

    private fun onPreferences(prefs: UserPreferences) {
        val first = preferences == null
        preferences = prefs
        val app = prefs.appPreferences
        AppTheme.update(app.interfacePreferences.appThemeColors)
        DebugLogTree.INSTANCE.enabled = app.debugLogging
        if (first) {
            deps.installImageLoader(prefs)
            prefsLoading.visibility = View.GONE
            content.visibility = View.VISIBLE
            root.setBackgroundColor(AppTheme.palette.surface)
        }
        clock.visibility = if (menu.visibility == View.VISIBLE && app.interfacePreferences.showClock) View.VISIBLE else View.GONE
    }

    private fun onThemeChanged() {
        // The old MainContent Surface: the theme's surface colour under everything
        if (preferences != null) root.setBackgroundColor(AppTheme.palette.surface)
        menu.onThemeChanged()
        clock.onThemeChanged()
        backdrop.onThemeChanged()
        if (appActive) {
            navHost.refreshStyles()
            navHost.pages.forEach { f -> f.view?.let { layoutPage(f, it) } }
        }
    }

    // ---- Setup layer ----

    private fun onSetupDestination(destination: SetupDestination) {
        if (destination == setupShown) return
        Timber.d("Setup layer: %s", destination)
        setupShown = destination
        when (destination) {
            SetupDestination.Loading -> {
                leaveAppContent()
                showSetupFragment(null)
                fade(loadingLayer, true)
            }

            SetupDestination.ServerList, is SetupDestination.UserList -> {
                leaveAppContent()
                fade(loadingLayer, false)
                showSetupFragment(destination)
            }

            is SetupDestination.AppContent -> {
                fade(loadingLayer, false)
                showSetupFragment(null)
                enterAppContent(destination.current)
            }
        }
    }

    private fun showSetupFragment(destination: SetupDestination?) {
        val fm = activity.supportFragmentManager
        if (fm.isDestroyed) return
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        tx.setCustomAnimations(R.animator.lb_shell_setup_enter, R.animator.lb_shell_setup_exit)
        fm.fragments.filter { it.tag?.startsWith(SETUP_TAG) == true }.forEach { tx.remove(it) }
        if (destination != null) {
            val fragment = SetupScreens.create(destination) ?: SetupPlaceholderFragment.create(destination)
            tx.add(R.id.lb_shell_setup, fragment, SETUP_TAG + destination::class.simpleName)
            setupLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            setupLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        tx.commitNowAllowingStateLoss()
    }

    /** Cross-fade a layer view in or out over 700 ms */
    private fun fade(
        view: View,
        visible: Boolean,
        onHidden: () -> Unit = {},
    ) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view
                .animate()
                .alpha(1f)
                .setDuration(SETUP_FADE_MS)
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withEndAction(null)
                .start()
        } else if (view.visibility == View.VISIBLE) {
            view
                .animate()
                .alpha(0f)
                .setDuration(SETUP_FADE_MS)
                .setInterpolator(FAST_OUT_SLOW_IN)
                .withEndAction {
                    view.visibility = View.GONE
                    onHidden()
                }.start()
        } else {
            onHidden()
        }
    }

    // ---- App layer ----

    private fun enterAppContent(current: CurrentUser) {
        if (appActive && appUser != current) leaveAppContent(immediately = true)
        if (appActive) return
        // A previous session's pages still fading out go now; every new app content starts fresh, as a new NavEntry did
        appLayer.animate().cancel()
        if (navHost.active) finishLeaving()
        Timber.i("Showing the app content for %s", current.user.name)
        appActive = true
        appUser = current
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        appScope = scope
        clearBackdrop()
        CoachMarkTargets.tour = deps.coachMarkTour.takeIf { coachMarksEnabled }
        if (coachMarksEnabled) deps.coachMarkTour.rail = menu.coachMarkRail
        appLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        fade(appLayer, true)

        // The side menu, keyed to the server & user so it resets when switching either
        val vm = ViewModelProvider(activity)["${current.server.id}_${current.user.id}", NavDrawerViewModel::class.java]
        menuViewModel = vm
        scope.launch {
            combine(
                vm.serviceState,
                vm.state,
                vm.isKidSession,
                vm.pictureVersions,
                deps.requestStatusRepository.state
                    .map { it.badgeCount }
                    .distinctUntilChanged(),
            ) { service, state, kid, versions, badge ->
                SideMenuModel(
                    user = current.user,
                    server = current.server,
                    userImageUrl = vm.getUserImage(current.user, versions),
                    items = service.items,
                    moreItems = service.moreItems,
                    selectedIndex = state.selectedIndex,
                    moreExpanded = state.moreExpanded,
                    kidSession = kid,
                    badgeCount = badge,
                )
            }.collect { menu.bind(it) }
        }
        scope.launch {
            // The highlight is a position in the list, so pick it again whenever the items change, e.g. when Playlists
            // appears after a first playlist or goes away with the last one
            vm.serviceState
                .map { it.items to it.moreItems }
                .distinctUntilChanged()
                .collect { vm.updateSelectedIndex() }
        }

        navHost.activate(nav.backStack.toList())

        if (coachMarksEnabled) coachOverlay.start(scope)
        updatePrompt.start(scope)
        val tourActive = if (coachMarksEnabled) deps.coachMarkTour.activeFlow else flowOf(false)
        val kid = current.user.profileKind == ProfileKind.KID
        // After an update: once, over Home, never for a kid profile or on top of the tour
        whatsNew.start(scope, tourActive.map { it || kid })
        chips =
            RequestChipHost(
                host = chipLayer,
                repository = deps.requestStatusRepository,
                navigation = nav,
                ownerRowId = current.user.rowId,
                presentationBlocked = tourActive,
                lifecycle = activity.lifecycle,
                focusPage = { navHost.top?.let { navHost.focusTop(it, restoring = true, park = true) } },
            ).also { it.start(scope) }
    }

    /** The signed-in content goes (another setup screen, or hidden on stop): its pages are removed */
    private fun leaveAppContent(immediately: Boolean = false) {
        if (!appActive) return
        Timber.i("Leaving the app content")
        appActive = false
        appUser = null
        chips?.stop()
        chips = null
        updatePrompt.stop()
        whatsNew.stop()
        if (coachMarksEnabled) {
            coachOverlay.stop()
            if (deps.coachMarkTour.rail === menu.coachMarkRail) deps.coachMarkTour.rail = null
        }
        CoachMarkTargets.tour = null
        appScope?.cancel()
        appScope = null
        menuViewModel = null
        appLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (immediately) {
            appLayer.animate().cancel()
            appLayer.visibility = View.GONE
            finishLeaving()
        } else {
            fade(appLayer, false) { if (!appActive) finishLeaving() }
        }
    }

    private fun finishLeaving() {
        navHost.deactivate()
        menuHideJob?.cancel()
        menu.closeNow()
        menu.visibility = View.GONE
        clock.visibility = View.GONE
    }

    private val lifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val prefs = preferences ?: return
                if (appActive && (!prefs.appPreferences.signInAutomatically || deps.hideContentOnStop())) {
                    // The profile's screen never flashes on return: a spinner until the next setup screen
                    leaveAppContent(immediately = true)
                    loadingLayer.animate().cancel()
                    loadingLayer.alpha = 1f
                    loadingLayer.visibility = View.VISIBLE
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                appScope?.cancel()
            }
        }

    /** The window gained or lost focus (a dialog opened or closed) */
    fun onWindowFocusChanged() {
        chips?.environment?.let { it.value = it.value + 1 }
    }

    // ---- Pages ----

    private fun computePageStyle(
        stack: List<Destination>,
        destination: Destination,
    ): PageStyle {
        val navItems = deps.navDrawerService.state.value
        val items = navItems.items + navItems.moreItems
        val homeSettings = deps.homeSettingsService.currentSettings.value
        val keyIndex = stack.indexOf(destination)
        val entryStack = stack.take(if (keyIndex >= 0) keyIndex + 1 else stack.size)
        val sectionAccent = navigationSectionAccent(entryStack, items)
        val libraryId =
            items
                .filterIsInstance<ServerNavDrawerItem>()
                .firstOrNull { libraryAccent(it.name, it.type) == sectionAccent }
                ?.itemId
        val neon = AppTheme.isNeon
        return PageStyle(
            sectionAccent = sectionAccent,
            homeCardAppearance =
                if (neon) homeMediaAppearanceView(homeSettings, libraryId).copy(accentIndex = 0) else defaultHomeCardAppearance(),
            homeMediaSettings = homeSettings,
            posterCountAppearance = if (neon) homeMediaAppearanceView(homeSettings) else null,
        )
    }

    private fun mayOpenPage(destination: Destination): Boolean =
        !(BuildConfig.KID_PROFILES_ENABLED && KidNavigation.isSettingsPage(destination)) || kidGuard.mayShow(destination)

    /**
     * Pages with the side menu sit right of the collapsed rail, as the Compose NavDrawer's content did: WeaselTV theme
     * 72dp + 24dp (half the overscan), 8dp from the end except on Home; other themes 64dp + 8dp and 16dp
     */
    private fun layoutPage(
        fragment: DestinationFragment,
        view: View,
    ) {
        val lp =
            (view.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (fragment.showsNavDrawer) {
            val neon = AppTheme.isNeon
            lp.marginStart = if (neon) menu.collapsedWidthPx + context.dp(24) else menu.collapsedWidthPx + context.dp(8)
            lp.marginEnd =
                when {
                    !neon -> context.dp(16)
                    fragment.destinationOrNull is Destination.Home -> 0
                    else -> context.dp(8)
                }
            view.translationX = menu.openFraction * (menu.expandedWidthPx - menu.collapsedWidthPx)
        } else {
            lp.marginStart = 0
            lp.marginEnd = 0
            view.translationX = 0f
        }
        view.layoutParams = lp
    }

    /** The open menu pushes the pages right by the difference between its widths */
    private fun slidePages(fraction: Float) {
        val dx = fraction * (menu.expandedWidthPx - menu.collapsedWidthPx)
        navHost.pages.forEach { f -> if (f.showsNavDrawer) f.view?.translationX = dx }
    }

    /** Home was chosen in the menu: the Home page shown next takes focus ([onTopChanged]) */
    private var focusHomeFromMenu = false

    private fun onTopChanged(
        top: DestinationFragment,
        pop: Boolean,
    ) {
        val destination = top.destinationOrNull ?: return
        if (!mayOpenPage(destination)) kidGuard.leave(destination)
        if (focusHomeFromMenu) {
            focusHomeFromMenu = false
            if (destination is Destination.Home) navHost.focusTop(top, restoring = true, park = false)
        }
        val showClock = preferences?.appPreferences?.interfacePreferences?.showClock == true
        menuHideJob?.cancel()
        if (top.showsNavDrawer) {
            if (menu.visibility != View.VISIBLE) {
                menu.visibility = View.VISIBLE
                if (showClock) clock.visibility = View.VISIBLE
                if (!pop) {
                    // The page fades in with its menu, as each Compose page carried its own
                    listOf<View>(menu, clock).forEach {
                        it.alpha = 0f
                        it
                            .animate()
                            .alpha(1f)
                            .setDuration(PAGE_MS)
                            .setInterpolator(FAST_OUT_SLOW_IN)
                            .start()
                    }
                } else {
                    menu.alpha = 1f
                    clock.alpha = 1f
                }
            } else {
                clock.visibility = if (showClock) View.VISIBLE else View.GONE
            }
            menuViewModel?.updateSelectedIndex()
        } else if (menu.visibility == View.VISIBLE) {
            // A full-screen page covers the menu as it fades in, then the menu goes; going back to one, the menu fades
            // out with the page that carried it
            val hide = {
                menu.closeNow()
                menu.visibility = View.GONE
                clock.visibility = View.GONE
                menu.alpha = 1f
                clock.alpha = 1f
            }
            if (pop) {
                listOf<View>(menu, clock).forEach {
                    it
                        .animate()
                        .alpha(0f)
                        .setDuration(PAGE_MS)
                        .start()
                }
            }
            menuHideJob =
                activity.lifecycleScope.launch(Dispatchers.Main) {
                    delay(PAGE_MS)
                    hide()
                }
        }
    }

    // ---- Focus ----

    private fun onGlobalFocus(newFocus: View?) {
        navHost.onFocusChanged(newFocus)
        val inMenu = newFocus != null && menu.contains(newFocus)
        if (menu.visibility == View.VISIBLE || !inMenu) {
            menu.setOpen(inMenu)
            backdrop.setDrawerOpen(inMenu)
        }
        chips?.environment?.let { it.value = it.value + 1 }
    }

    /**
     * Focus rules between the side menu and the page: coming into the menu lands on the current section's row, RIGHT
     * from the menu returns to the page (its last focused view), and focus never leaves the menu up, down or left
     */
    private fun route(
        focused: View?,
        direction: Int,
        result: View?,
    ): View? {
        if (focused == null) return result
        if (menu.contains(focused)) {
            return when (direction) {
                View.FOCUS_RIGHT -> {
                    val top = navHost.top
                    if (top != null && top.showsNavDrawer) {
                        navHost.focusTop(top, restoring = true, park = false)
                        val now = root.findFocus()
                        if (now != null && !menu.contains(now)) now else result
                    } else {
                        result
                    }
                }

                View.FOCUS_UP, View.FOCUS_DOWN -> {
                    // Within the menu only: the open menu lies over the page's left edge, so a search from Search
                    // found a page's top controls (eg Watchlist's ALL tab) before the profile row above it
                    FocusFinder.getInstance().findNextFocus(menu, focused, direction) ?: focused
                }

                View.FOCUS_LEFT -> {
                    if (result != null && menu.contains(result)) result else focused
                }

                else -> {
                    result
                }
            }
        }
        if (result != null && menu.contains(result)) {
            if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && isInPages(focused)) {
                // UP/DOWN stay in the page; only LEFT opens the menu. The menu's rows span its open width under the
                // page, so a search from a page's top controls (eg Search's type toggles) found them before the
                // results; the Compose rail was only as wide as its icons
                return FocusFinder.getInstance().findNextFocus(pages, focused, direction) ?: focused
            }
            // Coming into the menu from the page lands on the current page's button (owner, 2026-11-11)
            return menu.entryTarget() ?: result
        }
        return result
    }

    private fun isInPages(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === pages) return true
            parent = parent.parent
        }
        return false
    }

    /** Open the side menu on the current section, for screens (eg LEFT at a row's first card) */
    fun focusNavDrawer(): Boolean = appActive && menu.visibility == View.VISIBLE && menu.focusEntry()

    // ---- Keys & BACK ----

    /** The top page's `onPreviewKeyEvent`, when focus is in it (the old page-level onPreviewKeyEvent) */
    fun dispatchPreviewKey(event: KeyEvent): Boolean {
        if (!appActive) return false
        val top = navHost.top ?: return false
        val focused = root.findFocus()
        if (focused == null || navHost.inTopPage(focused) || pages.parked) return top.onPreviewKeyEvent(event)
        return false
    }

    private fun onBack() {
        if (!appActive) {
            passBack()
            return
        }
        val top = navHost.top
        if (top != null && top.onBackPressed()) return
        val vm = menuViewModel
        if (menu.isOpen && vm != null && vm.state.value.moreExpanded) {
            vm.setShowMore(false)
            return
        }
        // If the user presses back while on the home page, open the nav drawer, another back press will quit the app
        if (!menu.isOpen && top != null && top.showsNavDrawer && top.destinationOrNull is Destination.Home) {
            menu.focusEntry()
            return
        }
        if (nav.backStack.size > 1) {
            nav.goBack()
            return
        }
        passBack()
    }

    /** Let the activity do its default BACK (leave the app) */
    private fun passBack() {
        backCallback.isEnabled = false
        activity.onBackPressedDispatcher.onBackPressed()
        backCallback.isEnabled = true
    }

    // ---- Menu actions ----

    private val menuListener =
        object : SideMenuView.Listener {
            override fun onProfileClick() {
                val server = appUser?.server ?: return
                menuViewModel?.navigateToSetup(SetupDestination.UserList(server))
            }

            override fun onSearchClick() {
                menuViewModel?.setIndex(SEARCH_INDEX)
                nav.navigateToFromDrawer(Destination.Search())
            }

            override fun onHomeClick() {
                menuViewModel?.setIndex(HOME_INDEX)
                if (navHost.top?.destinationOrNull is Destination.Home) {
                    nav.reloadHome()
                    clearBackdrop()
                } else {
                    // Going back to Home keeps focus in the menu; from the menu's Home it moves to Home's top row, as
                    // the Compose Home took focus when it came back on screen
                    focusHomeFromMenu = true
                    nav.goToHome()
                }
            }

            override fun onItemClick(
                index: Int,
                item: NavDrawerItem,
            ) {
                menuViewModel?.onClickDrawerItem(index, item)
            }

            override fun onSettingsClick() {
                nav.navigateTo(Destination.Settings(PreferenceScreenOption.BASIC))
            }
        }

    // ---- Backdrop ----

    fun clearBackdrop() {
        activity.lifecycleScope.launchIO { deps.backdropService.clearBackdrop() }
    }

    // ---- Layouts with the focus rules ----

    /** The root: asked for focus (eg the focused view went away), it tries the visible layer first */
    @SuppressLint("ViewConstructor")
    inner class ShellRootLayout(
        context: Context,
    ) : FrameLayout(context) {
        override fun onRequestFocusInDescendants(
            direction: Int,
            previouslyFocusedRect: Rect?,
        ): Boolean {
            if (appActive && appLayer.isShown && appLayer.requestFocus(direction, previouslyFocusedRect)) return true
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
    }

    /** The signed-in app layer: applies [route] to every focus search that passes through it */
    @SuppressLint("ViewConstructor")
    private inner class AppLayerLayout(
        context: Context,
    ) : FrameLayout(context) {
        init {
            clipChildren = false
        }

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? = route(focused, direction, super.focusSearch(focused, direction))

        override fun onRequestFocusInDescendants(
            direction: Int,
            previouslyFocusedRect: Rect?,
        ): Boolean {
            if (appActive && navHost.requestFocusForLayer(direction, previouslyFocusedRect)) return true
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
    }

    companion object {
        private const val SETUP_TAG = "lb_setup:"
        private const val SETUP_FADE_MS = 700L
        private const val PAGE_MS = 150L
        private val FAST_OUT_SLOW_IN = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)

        /** Setup fragments a recreated activity restored: the shell shows the current one again itself */
        fun dropRestoredSetup(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            val restored = fm.fragments.filter { it.tag?.startsWith(SETUP_TAG) == true }
            if (restored.isEmpty()) return
            val tx = fm.beginTransaction()
            restored.forEach { tx.remove(it) }
            tx.commitNowAllowingStateLoss()
        }
    }
}
