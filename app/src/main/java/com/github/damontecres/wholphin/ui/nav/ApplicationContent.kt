package com.github.damontecres.wholphin.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.ui.theme.LocalHomeMediaSettings
import com.github.damontecres.wholphin.ui.theme.LocalPosterCountAppearance
import com.github.damontecres.wholphin.ui.theme.LocalHomeCardAppearance
import com.github.damontecres.wholphin.ui.theme.homeMediaAppearance
import com.github.damontecres.wholphin.ui.theme.libraryAccent
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.rememberDrawerState
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.ui.theme.ProvideNeonSectionAccent
import com.github.damontecres.wholphin.ui.theme.navigationSectionAccent
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Top scrim configuration for text readability (clock, season tabs)
const val TOP_SCRIM_ALPHA = 0.55f
const val TOP_SCRIM_END_FRACTION = 0.25f // Fraction of backdrop image height

@HiltViewModel
class ApplicationContentViewModel
    @Inject
    constructor(
        val backdropService: BackdropService,
        val navDrawerService: NavDrawerService,
        val homeSettingsService: HomeSettingsService,
    ) : ViewModel() {
        fun clearBackdrop() {
            viewModelScope.launchIO { backdropService.clearBackdrop() }
        }
    }

/**
 * This is generally the root composable of the of the app
 *
 * Here the navigation backstack is used and pages are rendered in the nav drawer or full screen
 */
@Composable
fun ApplicationContent(
    server: JellyfinServer,
    user: JellyfinUser,
    navigationManager: NavigationManager,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    enableTopScrim: Boolean = true,
    viewModel: ApplicationContentViewModel = hiltViewModel(),
) {
    val navItems by viewModel.navDrawerService.state.collectAsState()
    val homeSettings by viewModel.homeSettingsService.currentSettings.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    CompositionLocalProvider(
        LocalHomeMediaSettings provides homeSettings,
        LocalPosterCountAppearance provides homeMediaAppearance(homeSettings).takeIf { isWeaselTv() },
    ) {
    Box(
        modifier = modifier,
    ) {
        val backdropStyle = preferences.appPreferences.interfacePreferences.backdropStyle
        Backdrop(
            drawerIsOpen = drawerState.isOpen,
            backdropStyle = backdropStyle,
            enableTopScrim = enableTopScrim,
            viewModel = viewModel,
        )
        val navDrawerListState = rememberLazyListState()
        NavDisplay(
            backStack = navigationManager.backStack,
            onBack = { navigationManager.goBack() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { key ->
                key as Destination
                val contentKey = "${key}_${server?.id}_${user?.id}"
                NavEntry(key, contentKey = contentKey) {
                    val keyIndex = navigationManager.backStack.indexOf(key)
                    val entryStack = navigationManager.backStack.take(if (keyIndex >= 0) keyIndex + 1 else navigationManager.backStack.size)
                    val sectionAccent = navigationSectionAccent(entryStack, navItems.items + navItems.moreItems)
                    val libraryId = (navItems.items + navItems.moreItems).filterIsInstance<ServerNavDrawerItem>()
                        .firstOrNull { libraryAccent(it.name, it.type) == sectionAccent }?.itemId
                    CompositionLocalProvider(LocalHomeCardAppearance provides if (isWeaselTv()) homeMediaAppearance(homeSettings, libraryId).copy(accentIndex = 0) else LocalHomeCardAppearance.current) {
                    ProvideNeonSectionAccent(sectionAccent) {
                        if (key.fullScreen) {
                            DestinationContent(
                                destination = key,
                                preferences = preferences,
                                onClearBackdrop = viewModel::clearBackdrop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (user != null && server != null) {
                            NavDrawer(
                                destination = key,
                                preferences = preferences,
                                user = user,
                                server = server,
                                drawerState = drawerState,
                                navDrawerListState = navDrawerListState,
                                onClearBackdrop = viewModel::clearBackdrop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ErrorMessage("Trying to go to $key without a user logged in", null)
                        }
                    }
                    }
                }
            },
        )
    }
    }
}
