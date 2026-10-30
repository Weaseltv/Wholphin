package com.github.damontecres.wholphin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.components.RequestChipHost
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkOverlay
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.coachmark.LocalCoachMarkTour
import com.github.damontecres.wholphin.ui.components.AppScreensaver
import com.github.damontecres.wholphin.ui.nav.ApplicationContent
import com.github.damontecres.wholphin.ui.setup.PinnedServerContent
import com.github.damontecres.wholphin.ui.setup.SwitchServerContent
import com.github.damontecres.wholphin.ui.setup.SwitchUserContent
import com.github.damontecres.wholphin.ui.setup.UpdatePrompt
import com.github.damontecres.wholphin.ui.setup.isServerPinned
import com.github.damontecres.wholphin.ui.util.InterfaceCustomization
import com.github.damontecres.wholphin.ui.util.LocalInterfaceCustomization

@Composable
fun MainContent(
    backStack: MutableList<SetupDestination>,
    navigationManager: NavigationManager,
    userPreferences: UserPreferences,
    backdropService: BackdropService,
    screensaverService: ScreensaverService,
    updateChecker: UpdateChecker,
    requestStatusRepository: com.github.damontecres.wholphin.services.RequestStatusRepository,
    coachMarkTour: CoachMarkTour,
    modifier: Modifier = Modifier,
    // Kid profiles (plan 4.5): also hide the profile's screen on stop when the profile screen will show on return
    hideContentOnStop: () -> Boolean = { false },
) {
    val preferences by rememberUpdatedState(userPreferences)
    Surface(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
        shape = RectangleShape,
    ) {
//                            val backStack = rememberNavBackStack(SetupDestination.Loading)
//                            setupNavigationManager.backStack = backStack
        val interfaceCustomization =
            remember(userPreferences.appPreferences) { InterfaceCustomization(userPreferences.appPreferences) }
        val requestUi = remember { com.github.damontecres.wholphin.ui.components.RequestUiState() }
        CompositionLocalProvider(LocalInterfaceCustomization provides interfaceCustomization,
            com.github.damontecres.wholphin.ui.components.LocalRequestUiState provides requestUi) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = { key ->
                    key as SetupDestination
                    NavEntry(key) {
                        when (key) {
                            SetupDestination.Loading -> {
                                Box(
                                    modifier = Modifier.size(200.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.border,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }

                            SetupDestination.ServerList -> {
                                if (isServerPinned) {
                                    PinnedServerContent(Modifier.fillMaxSize())
                                } else {
                                    SwitchServerContent(Modifier.fillMaxSize())
                                }
                            }

                            is SetupDestination.UserList -> {
                                SwitchUserContent(
                                    server = key.server,
                                    Modifier.fillMaxSize(),
                                )
                            }

                            is SetupDestination.AppContent -> {
                                LaunchedEffect(Unit) {
                                    backdropService.clearBackdrop()
                                }
                                val current = key.current
                                var showContent by remember {
                                    mutableStateOf(true)
                                }
                                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                                    if (!userPreferences.appPreferences.signInAutomatically || hideContentOnStop()) {
                                        showContent = false
                                    }
                                }

                                if (showContent) {
                                    val pageFocus = remember(current.user.rowId) { FocusRequester() }
                                    Box(Modifier.fillMaxSize()) {
                                        Box(Modifier.fillMaxSize().focusRequester(pageFocus).focusGroup()) {
                                            CompositionLocalProvider(
                                                LocalCoachMarkTour provides coachMarkTour.takeIf { BuildConfig.COACH_MARK_TOUR_ENABLED },
                                            ) {
                                                ApplicationContent(
                                                    user = current.user,
                                                    server = current.server,
                                                    navigationManager = navigationManager,
                                                    preferences = preferences,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        if (BuildConfig.COACH_MARK_TOUR_ENABLED) {
                                            CoachMarkOverlay(coachMarkTour, navigationManager)
                                        }
                                        UpdatePrompt(updateChecker, navigationManager)
                                        RequestChipHost(
                                            repository = requestStatusRepository,
                                            navigation = navigationManager,
                                            screensaver = screensaverService,
                                            pageFocus = pageFocus,
                                            ownerRowId = current.user.rowId,
                                            presentationBlocked = BuildConfig.COACH_MARK_TOUR_ENABLED && coachMarkTour.active,
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.size(200.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.border,
                                            modifier = Modifier.align(Alignment.Center),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )
            val screenSaverState by screensaverService.state.collectAsState()
            if (screenSaverState.enabled || screenSaverState.enabledTemp) {
                AnimatedVisibility(
                    visible = screenSaverState.show,
                    enter = ScreensaverService.enterAnimation,
                    exit = ScreensaverService.exitAnimation,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppScreensaver(userPreferences.appPreferences, Modifier.fillMaxSize())
                }
            }
            AnimatedVisibility(
                visible = screenSaverState.showDim || (screenSaverState.dimEnabled && screenSaverState.show),
                enter = ScreensaverService.enterAnimation,
                exit = ScreensaverService.exitAnimation,
                modifier = Modifier.fillMaxSize(),
            ) {
                val alpha =
                    userPreferences.appPreferences.interfacePreferences.screensaverPreference.dimPercent / 100f
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = alpha)),
                )
            }
        }
    }
}
