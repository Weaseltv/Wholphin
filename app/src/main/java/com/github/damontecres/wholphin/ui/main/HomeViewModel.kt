package com.github.damontecres.wholphin.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.kids.isStaleSession
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeRowConfigDisplay
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.StreamingOrderService
import com.github.damontecres.wholphin.services.StreamingQuickListService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.util.EmptyStringProvider
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val serverReportService: ServerReportService,
        private val navDrawerService: NavDrawerService,
        private val homeSettingsService: HomeSettingsService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val datePlayedService: DatePlayedService,
        private val backdropService: BackdropService,
        private val userPreferencesService: UserPreferencesService,
        private val mediaManagementService: MediaManagementService,
        private val latestNextUpService: LatestNextUpService,
        private val streamingOrderService: StreamingOrderService,
        private val quickListService: StreamingQuickListService,
    ) : ViewModel() {
        private val _state = MutableStateFlow(HomeState.EMPTY)
        val state: StateFlow<HomeState> = _state

        init {
            datePlayedService.invalidateAll()
//            init()
        }

        /** Serializes Home loads with the kids Home's row updates, so rows land at the index they were fetched for */
        private val loadMutex = Mutex()

        private var settingsWatch: Job? = null

        fun init() {
            watchSettings()
            viewModelScope.launchIO {
                // Entering Home: the kids Home looks for new or removed collections (throttled)
                try {
                    homeSettingsService.refreshKidCollections()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Kids collections refresh failed")
                }
            }
            viewModelScope.launchIO {
                loadMutex.withLock { load() }
            }
        }

        private suspend fun load() {
            Timber.d("init HomeViewModel")
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences

                serverRepository.currentUserDto?.let { userDto ->
                    val libraries =
                        navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                    // Kid content rules (PR #97 review round 1): only settings made for this session
                    val settings = homeSettingsService.awaitCurrentSettings()
                    val state = state.value

                    // Refreshing if a load has already occurred and the rows haven't significantly changed
                    val refresh =
                        state.loadingState == LoadingState.Success && state.settings == settings
                    Timber.v(
                        "refresh=%s, state.loadingState=%s, %s rows",
                        refresh,
                        state.loadingState,
                        settings.rows.size,
                    )
                    _state.update {
                        it.copy(
                            loadingState = if (refresh) LoadingState.Success else LoadingState.Loading,
                            refreshState = LoadingState.Loading,
                            settings = settings,
                            libraries = libraries,
                            homeRows =
                                if (refresh) {
                                    it.homeRows
                                } else {
                                    List(settings.rows.size) {
                                        HomeRowLoadingState.Pending(EmptyStringProvider)
                                    }
                                },
                        )
                    }

                    val semaphore = Semaphore(4)

                    val deferred =
                        settings.rows
                            .map { row ->
                                viewModelScope.async(WholphinDispatchers.IO) {
                                    semaphore.withPermit { fetchRow(row, prefs, userDto, libraries, refresh) }
                                }
                            }

                    if (refresh) {
                        // Replace rows as they complete
                        val remaining = deferred.withIndex().toMutableList()
                        while (remaining.isNotEmpty()) {
                            val (rowIndex, rowData) =
                                select {
                                    // "Return" the first remaining that is completed
                                    remaining
                                        .forEach { (rowIndex, deferred) ->
                                            deferred.onAwait { rowIndex to it }
                                        }
                                }
                            Timber.v("Got row data index=%s", rowIndex)
                            remaining.removeIf { it.index == rowIndex }
                            _state.update { state ->
                                val newRows =
                                    state.homeRows.toMutableList().apply {
                                        set(rowIndex, rowData)
                                    }
                                state.copy(
                                    homeRows = newRows,
                                )
                            }
                        }
                        _state.update {
                            it.copy(
                                loadingState = LoadingState.Success,
                                refreshState = LoadingState.Success,
                            )
                        }
                    } else {
                        val rows = deferred.awaitAll()
                        Timber.v("Got all rows")
                        _state.update {
                            it.copy(
                                loadingState = LoadingState.Success,
                                refreshState = LoadingState.Success,
                                homeRows = rows,
                            )
                        }
                    }
                    Timber.d("Home page load complete")
                    prefetchStreamingCollections(userDto.id)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Exception during home page loading")
                if (state.value.loadingState == LoadingState.Success) {
                    if (!ex.isStaleSession()) showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                    _state.update { it.copy(refreshState = LoadingState.Error(ex)) }
                } else {
                    _state.update {
                        it.copy(loadingState = LoadingState.Error(ex))
                    }
                }
            }
        }

        private suspend fun fetchRow(
            row: HomeRowConfigDisplay,
            prefs: HomePagePreferences,
            userDto: UserDto,
            libraries: List<Library>,
            refresh: Boolean,
        ): HomeRowLoadingState {
            Timber.v("Fetching row: %s", row)
            return try {
                homeSettingsService.fetchDataForRow(
                    row = row.config,
                    scope = viewModelScope,
                    prefs = prefs,
                    userDto = userDto,
                    libraries = libraries,
                    limit = prefs.maxItemsPerRow,
                    isRefresh = refresh,
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: InvalidStatusException) {
                if (ex.status == 404) {
                    Timber.w(ex, "404 on row %s", row)
                    HomeRowLoadingState.Success(
                        row.title,
                        emptyList(),
                    )
                } else {
                    Timber.e(
                        ex,
                        "Error %s on row %s",
                        ex.status,
                        row,
                    )
                    HomeRowLoadingState.Error(
                        row.title,
                        exception = ex,
                    )
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error on row %s", row)
                HomeRowLoadingState.Error(
                    row.title,
                    exception = ex,
                )
            }
        }

        /**
         * Kid content rules (PR #97 review round 1): Home drops what it shows as soon as it stops being for the
         * session (a policy refresh turned the profile into a kid, or a new epoch) and loads again. A kids Home also
         * takes its collection rows as they're found, and later additions and removals, keeping the rows it has.
         * Adults' Home is otherwise unchanged.
         */
        private fun watchSettings() {
            if (settingsWatch != null) return
            settingsWatch =
                viewModelScope.launchIO {
                    homeSettingsService.settingsUpdates.collect { settings ->
                        val shown = state.value.settings
                        if (shown == HomePageResolvedSettings.EMPTY) return@collect
                        if (!homeSettingsService.isCurrent(shown)) {
                            Timber.i("Kid content rules: Home settings are for an earlier session, reloading")
                            _state.update { HomeState.EMPTY.copy(loadingState = LoadingState.Loading) }
                            init()
                        } else if (settings.owner?.kid == true && settings != shown && homeSettingsService.isCurrent(settings)) {
                            loadMutex.withLock { applyKidSettings(settings) }
                        }
                    }
                }
        }

        /** Show the kids Home's [settings], keeping every row already loaded and fetching only the new ones */
        private suspend fun applyKidSettings(settings: HomePageResolvedSettings) {
            val current = state.value
            if (current.loadingState != LoadingState.Success || current.settings == settings) return
            if (!homeSettingsService.isCurrent(settings)) return
            val userDto = serverRepository.currentUserDto ?: return
            val prefs = userPreferencesService.getCurrent().appPreferences.homePagePreferences
            val rows = reconcileRows(current.settings, current.homeRows, settings)
            _state.update { it.copy(settings = settings, homeRows = rows) }
            val semaphore = Semaphore(4)
            settings.rows
                .withIndex()
                .filter { (index, _) -> rows[index] is HomeRowLoadingState.Pending }
                .map { (index, row) ->
                    viewModelScope.async(WholphinDispatchers.IO) {
                        val data = semaphore.withPermit { fetchRow(row, prefs, userDto, current.libraries, refresh = false) }
                        _state.update {
                            if (it.settings != settings) {
                                it
                            } else {
                                it.copy(homeRows = it.homeRows.toMutableList().apply { set(index, data) })
                            }
                        }
                    }
                }.awaitAll()
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
            withContext(WholphinDispatchers.Main) {
                init()
            }
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
            withContext(WholphinDispatchers.Main) {
                init()
            }
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submitHome(item)
            }
        }

        fun deleteItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    val row = state.value.homeRows.getOrNull(position.row)
                    if (row is HomeRowLoadingState.Success) {
                        _state.update {
                            val newRow =
                                row.items.toMutableList().apply {
                                    removeAt(position.column)
                                }
                            it.copy(
                                homeRows =
                                    it.homeRows.toMutableList().apply {
                                        set(position.row, row.copy(items = newRow))
                                    },
                            )
                        }
                    }
                }
            }
        }

        /** Move a card within its row, e.g. while reordering Streaming Services. */
        fun moveRowItem(
            rowIndex: Int,
            from: Int,
            to: Int,
        ) {
            _state.update { state ->
                val row = state.homeRows.getOrNull(rowIndex) as? HomeRowLoadingState.Success ?: return@update state
                if (from !in row.items.indices || to !in row.items.indices) return@update state
                val items = row.items.toMutableList().apply { add(to, removeAt(from)) }
                state.copy(homeRows = state.homeRows.toMutableList().apply { set(rowIndex, row.copy(items = items)) })
            }
        }

        /** Put a row's cards back, e.g. when a reorder is cancelled. */
        fun setRowItems(
            rowIndex: Int,
            items: List<BaseItem?>,
        ) {
            _state.update { state ->
                val row = state.homeRows.getOrNull(rowIndex) as? HomeRowLoadingState.Success ?: return@update state
                state.copy(homeRows = state.homeRows.toMutableList().apply { set(rowIndex, row.copy(items = items)) })
            }
        }

        /** Forget the member's Streaming Services order and reload, so the default order shows. */
        fun resetStreamingOrder(
            @Suppress("UNUSED_PARAMETER") rowIndex: Int,
        ) {
            viewModelScope.launchIO {
                serverRepository.currentUser?.id?.let { streamingOrderService.reset(it) }
                init()
            }
        }

        /** Save each streaming collection's titles in the background so opening one is instant. */
        private fun prefetchStreamingCollections(userId: UUID) {
            val current = state.value
            val index = current.settings.rows.indexOfFirst { StreamingCollections.isStreamingRow(it.config) }
            val row = current.homeRows.getOrNull(index) as? HomeRowLoadingState.Success ?: return
            quickListService.prefetchAll(userId, row.items.mapNotNull { it?.id })
        }

        /** Save the row's current order as the member's Streaming Services order. */
        fun saveStreamingOrder(rowIndex: Int) {
            val row = state.value.homeRows.getOrNull(rowIndex) as? HomeRowLoadingState.Success ?: return
            val ids = row.items.mapNotNull { it?.id }
            viewModelScope.launchIO {
                serverRepository.currentUser?.id?.let { streamingOrderService.save(it, ids) }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun removeFromNextUp(item: BaseItem) {
            if (item.type == BaseItemKind.EPISODE) {
                viewModelScope.launchDefault {
                    serverRepository.currentUser?.id?.let { userId ->
                        latestNextUpService.removeFromNextUp(userId, item)
                        init()
                    }
                }
            } else {
                Timber.w("Item is not an episode %s", item.id)
            }
        }
    }

data class HomeState(
    val loadingState: LoadingState,
    val refreshState: LoadingState,
    val homeRows: List<HomeRowLoadingState>,
    val settings: HomePageResolvedSettings,
    val libraries: List<Library> = emptyList(),
) {
    companion object {
        val EMPTY =
            HomeState(
                LoadingState.Pending,
                LoadingState.Pending,
                listOf(),
                HomePageResolvedSettings.EMPTY,
            )
    }
}

/**
 * Whether a row is a "is watching" type
 */
private fun isWatchingRow(row: HomeRowConfig) =
    row is HomeRowConfig.ContinueWatching ||
        row is HomeRowConfig.NextUp ||
        row is HomeRowConfig.ContinueWatchingCombined

/**
 * The rows for [new] settings: each row [old] already had (by its config) keeps its state, the others are pending
 */
internal fun reconcileRows(
    old: HomePageResolvedSettings,
    oldRows: List<HomeRowLoadingState>,
    new: HomePageResolvedSettings,
): List<HomeRowLoadingState> {
    val loaded =
        old.rows
            .map { it.config }
            .zip(oldRows)
            .toMap()
    return new.rows.map { loaded[it.config] ?: HomeRowLoadingState.Pending(it.title) }
}
