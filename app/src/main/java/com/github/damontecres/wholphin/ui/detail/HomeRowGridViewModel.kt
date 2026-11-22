package com.github.damontecres.wholphin.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/*
 * The More (MoreHomeRow) page's ViewModel and state, Compose-free (moved unchanged from HomeRowGrid.kt for the
 * no-Compose UI, 2026-11-22).
 */

@HiltViewModel(assistedFactory = HomeRowGridViewModel.Factory::class)
class HomeRowGridViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        val serverRepository: ServerRepository,
        private val userPreferencesService: UserPreferencesService,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val backdropService: BackdropService,
        private val navigationManager: NavigationManager,
        private val themeSongPlayer: ThemeSongPlayer,
        private val mediaManagementService: MediaManagementService,
        val streamChoiceService: StreamChoiceService,
        val serverReportService: ServerReportService,
        @Assisted private val title: StringProvider,
        @Assisted private val rowConfig: HomeRowConfig,
    ) : ViewModel(),
        ContextMenuProvider {
        @AssistedFactory
        interface Factory {
            fun create(
                title: StringProvider,
                rowConfig: HomeRowConfig,
            ): HomeRowGridViewModel
        }

        private val _state = MutableStateFlow(HomeRowGridState())
        val state: StateFlow<HomeRowGridState> = _state
        val navigationLibraries = navDrawerService.state

        init {
            viewModelScope.launchDefault {
                when (rowConfig) {
                    is HomeRowConfig.Genres,
                    is HomeRowConfig.Studios,
                    -> {
                        // Genres & Studios will be looked up by another ViewModel, so just return
                        _state.update {
                            it.copy(
                                loading =
                                    HomeRowLoadingState.Success(
                                        title,
                                        emptyList(),
                                        rowConfig.viewOptions,
                                    ),
                            )
                        }
                        return@launchDefault
                    }

                    else -> {}
                }
                try {
                    val preferences = userPreferencesService.getCurrent()
                    val prefs = preferences.appPreferences.homePagePreferences
                    serverRepository.currentUserDto?.let { userDto ->
                        val libraries =
                            navDrawerService.getAllUserLibraries(userDto.id)
                        val result =
                            homeSettingsService.fetchDataForRow(
                                row = rowConfig,
                                scope = viewModelScope,
                                prefs = prefs,
                                userDto = userDto,
                                libraries = libraries,
                                isRefresh = true,
                                limit = 1_000, // TODO
                                usePaging = true,
                            )
                        Timber.v(
                            "Got %s items for %s",
                            (result as? HomeRowLoadingState.Success)?.items?.size,
                            rowConfig,
                        )
                        _state.update { it.copy(loading = result) }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching: %s", rowConfig)
                    _state.update {
                        it.copy(
                            loading =
                                HomeRowLoadingState.Error(
                                    title,
                                    null,
                                    ex,
                                ),
                        )
                    }
                }
            }
        }

        override fun setWatched(
            position: Int,
            itemId: UUID,
            played: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setWatched(itemId, played)
                (state.value.loading as? HomeRowLoadingState.Success)?.let {
                    (it.items as? ApiRequestPager<*>)?.refreshItem(position, itemId)
                }
            }
        }

        override fun setFavorite(
            position: Int,
            itemId: UUID,
            favorite: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setFavorite(itemId, favorite)
                (state.value.loading as? HomeRowLoadingState.Success)?.let {
                    (it.items as? ApiRequestPager<*>)?.refreshItem(position, itemId)
                }
            }
        }

        override fun sendReportFor(itemId: UUID) = serverReportService.sendMediaReportFor(itemId)

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        /**
         * Navigate to a random item from this row
         */
        fun onClickRandom() {
            viewModelScope.launchIO {
                try {
                    val items = (state.value.loading as? HomeRowLoadingState.Success)?.items ?: return@launchIO

                    @Suppress("UNCHECKED_CAST")
                    val random =
                        (items as? BlockingList<BaseItem?>)?.randomBlocking()
                            ?: items.filterNotNull().randomOrNull()
                    Timber.d("Got random item: %s", random?.id)
                    random?.let { navigateTo(it.destination()) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error getting random")
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        override fun isAdministrator(): Boolean = serverRepository.currentUserDto?.policy?.isAdministrator == true

        override fun navigateTo(destination: Destination) {
            navigationManager.navigateTo(destination)
        }

        override fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        override fun deleteItem(
            index: Int,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    // TODO refresh
                }
            }
        }
    }

data class HomeRowGridState(
    val loading: HomeRowLoadingState = HomeRowLoadingState.Pending(StringStringProvider("")),
)
