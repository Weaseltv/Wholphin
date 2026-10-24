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
import com.github.damontecres.wholphin.services.HomeTiming
import com.github.damontecres.wholphin.services.KidHomeContent
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

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

        /** The load or kids Home update holding [loadMutex], cancelled with its row requests when the session moves on */
        @Volatile
        private var running: Job? = null

        /** The settings the running load took, before it shows them; the watcher checks them like the shown ones */
        @Volatile
        private var loadingSettings: HomePageResolvedSettings? = null

        private var settingsWatch: Job? = null

        fun init() {
            watchSettings()
            // Entering Home: the kids Home refreshes its rows in place (the collection list throttled)
            homeSettingsService.refreshKidHome()
            requestLoad()
        }

        private fun requestLoad() {
            viewModelScope.launchIO {
                exclusive { load(it) }
            }
        }

        /** Runs [block] under [loadMutex] for the generation current then, as the [running] job */
        private suspend fun exclusive(block: suspend (generation: Long) -> Unit) {
            loadMutex.withLock {
                running = currentCoroutineContext().job
                try {
                    block(state.value.generation)
                } finally {
                    running = null
                    loadingSettings = null
                }
            }
        }

        /**
         * Kid content rules (PR #97 review round 2): [block]'s state, only while [generation] is still Home's and the
         * settings it was made for are still the session's. False when it was dropped.
         */
        private fun update(
            generation: Long,
            settings: HomePageResolvedSettings,
            block: (HomeState) -> HomeState,
        ): Boolean {
            var applied = false
            _state.update {
                applied = it.generation == generation && homeSettingsService.isCurrent(settings)
                if (applied) block(it) else it
            }
            return applied
        }

        /**
         * Settings for the session as it is now, with that session's user. Waits while there are none (eg a restored
         * process before the listener loads them), and marks them as [loadingSettings] while they are still current.
         */
        private suspend fun currentSession(): Pair<HomePageResolvedSettings, UserDto> {
            while (true) {
                val (settings, userDto) =
                    homeSettingsService.settingsUpdates
                        .mapNotNull { settings ->
                            val userDto = serverRepository.currentUserDto
                            val owner = settings.owner
                            if (userDto != null && homeSettingsService.isCurrent(settings) &&
                                (owner == null || owner.userId == userDto.id)
                            ) {
                                settings to userDto
                            } else {
                                null
                            }
                        }.first()
                loadingSettings = settings
                // A session change from here on finds them in loadingSettings; one before, and they're taken again
                if (homeSettingsService.isCurrent(settings)) return settings to userDto
            }
        }

        private suspend fun load(generation: Long) {
            Timber.d("init HomeViewModel")
            try {
                // One generation: these settings, this user, its libraries and these preferences. The session first:
                // Home's preferences are the device's (AppPreferences, not per profile), read once it's settled.
                val (settings, userDto) =
                    withTimeoutOrNull(SESSION_WAIT) { currentSession() } ?: throw HomeSessionTimeout()
                if (settings.owner?.kid == true) {
                    followKidHome(generation, settings, userDto)
                    return
                }
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences
                val libraries = navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
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
                val started =
                    update(generation, settings) {
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
                if (!started) return

                val semaphore = Semaphore(4)
                // The row requests are this load's children: cancelled with it
                coroutineScope {
                    val deferred =
                        settings.rows
                            .map { row ->
                                async(WholphinDispatchers.IO) {
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
                            update(generation, settings) { state ->
                                val newRows =
                                    state.homeRows.toMutableList().apply {
                                        set(rowIndex, rowData)
                                    }
                                state.copy(
                                    homeRows = newRows,
                                )
                            }
                        }
                        update(generation, settings) {
                            it.copy(
                                loadingState = LoadingState.Success,
                                refreshState = LoadingState.Success,
                            )
                        }
                    } else {
                        val rows = deferred.awaitAll()
                        Timber.v("Got all rows")
                        update(generation, settings) {
                            it.copy(
                                loadingState = LoadingState.Success,
                                refreshState = LoadingState.Success,
                                homeRows = rows,
                            )
                        }
                    }
                }
                Timber.d("Home page load complete")
                prefetchStreamingCollections(userDto.id)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Exception during home page loading")
                _state.update {
                    when {
                        it.generation != generation -> {
                            it
                        }

                        it.loadingState == LoadingState.Success -> {
                            if (!ex.isStaleSession()) showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                            it.copy(refreshState = LoadingState.Error(ex))
                        }

                        else -> {
                            it.copy(loadingState = LoadingState.Error(ex))
                        }
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
         * Kid content rules (PR #97 review rounds 1 and 2): as soon as what Home shows, or what its running load took,
         * stops being for the session (a policy refresh made the profile a kid, another profile, a new epoch), Home
         * drops it, cancels that load and its row requests, and loads again under a new generation. A kids Home also
         * takes its collection rows as they're found, and later additions and removals, keeping the rows it has.
         */
        private fun watchSettings() {
            if (settingsWatch != null) return
            settingsWatch =
                viewModelScope.launchIO {
                    homeSettingsService.settingsUpdates.collect { settings ->
                        val shown = state.value.settings
                        val inUse =
                            listOfNotNull(loadingSettings, kidFollowing, shown.takeIf { it != HomePageResolvedSettings.EMPTY })
                        if (inUse.any { !homeSettingsService.isCurrent(it) }) {
                            Timber.i("Kid content rules: Home settings are for an earlier session, reloading")
                            invalidate()
                            requestLoad()
                        } else if (state.value.loadingState is LoadingState.Error && running == null &&
                            homeSettingsService.isCurrent(settings)
                        ) {
                            // Review round 3: a load that gave up waiting (or failed) tries again once settings arrive
                            requestLoad()
                        }
                    }
                }
        }

        /** A new generation: nothing shown, and whatever was loading for the old one stops */
        private fun invalidate() {
            _state.update { HomeState.EMPTY.copy(loadingState = LoadingState.Loading, generation = it.generation + 1) }
            loadingSettings = null
            running?.cancel()
            kidFollowing = null
            kidJob?.cancel()
        }

        /** The kids Home being shown ([followKidHome]), and its job */
        @Volatile
        private var kidFollowing: HomePageResolvedSettings? = null
        private var kidJob: Job? = null
        private var kidGeneration: Long? = null

        /**
         * A kids Home (owner, 2026-10-23: kids Home speed): its rows come from [HomeSettingsService.kidHome] as they
         * arrive (saved ones first, then fetched ones in place), already loading while the loading picture shows.
         * The loading picture stays until a row has something to show, or everything has loaded, so the page is
         * never just the side menu. Only under [generation] and while [settings]'s owner is the session.
         */
        private fun followKidHome(
            generation: Long,
            settings: HomePageResolvedSettings,
            userDto: UserDto,
        ) {
            val owner = settings.owner ?: return
            if (kidJob?.isActive == true && kidFollowing?.owner == owner && kidGeneration == generation) return
            kidJob?.cancel()
            kidFollowing = settings
            kidGeneration = generation
            _state.update {
                if (it.generation != generation || it.settings.owner == owner) {
                    it
                } else {
                    it.copy(settings = settings, homeRows = settings.rows.map { row -> HomeRowLoadingState.Pending(row.title) })
                }
            }
            kidJob =
                viewModelScope.launchIO {
                    launch {
                        val libraries =
                            try {
                                navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                Timber.w(ex, "Kids Home: libraries")
                                emptyList()
                            }
                        _state.update { if (it.generation == generation) it.copy(libraries = libraries) else it }
                    }
                    combine(homeSettingsService.currentSettings, homeSettingsService.kidHome) { current, content -> current to content }
                        .collect { (current, content) ->
                            if (current.owner != owner || !homeSettingsService.isCurrent(current)) return@collect
                            val rows = content?.takeIf { it.owner == owner } ?: KidHomeContent(owner)
                            val homeRows = current.rows.map { rows.rows[it.config] ?: HomeRowLoadingState.Pending(it.title) }
                            val ready = rows.hasContent(current) || rows.loaded
                            var firstShown = false
                            _state.update {
                                if (it.generation != generation) {
                                    it
                                } else {
                                    val shown = ready || it.loadingState == LoadingState.Success
                                    firstShown = shown && it.loadingState != LoadingState.Success
                                    it.copy(
                                        settings = current,
                                        homeRows = homeRows,
                                        loadingState = if (shown) LoadingState.Success else LoadingState.Loading,
                                        refreshState = if (rows.loaded) LoadingState.Success else LoadingState.Loading,
                                    )
                                }
                            }
                            if (firstShown) HomeTiming.mark(owner.userId, "kids Home first content shown")
                            if (rows.loaded) HomeTiming.mark(owner.userId, "kids Home all rows shown")
                        }
                }
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
    /** Kid content rules (PR #97 review round 2): goes up whenever what Home shows stops being for the session */
    val generation: Long = 0,
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

/** How long a Home load waits for settings made for the session before it shows an error (review round 3) */
private val SESSION_WAIT = 30.seconds

/** No settings for the session arrived in [SESSION_WAIT]; Home shows an error and tries again when they do */
internal class HomeSessionTimeout : IllegalStateException("Home is still waiting for this profile. Please try again.")

/**
 * Stable keys for a kids Home's rows (PR #97 review round 2), so a collection row keeps its focus and scroll state
 * when rows are added or removed around it: a collection row by its collection, the two rows before them by place.
 * Null for any other Home, which keeps its positional rows.
 */
internal fun kidHomeRowKeys(settings: HomePageResolvedSettings): List<String>? {
    if (settings.owner?.kid != true) return null
    return settings.rows.mapIndexed { index, row ->
        (row.config as? HomeRowConfig.ByParent)?.parentId?.let { "collection:$it" } ?: "row:$index"
    }
}

/**
 * Kid content rules (PR #97 review rounds 2 and 3): a kids Home's focused row, by key. [onRows] says what to do when the
 * rows or their content change: follow the row to its new index, or, when it's gone, recover focus on the nearest row
 * with content. With none yet (eg the new rows are still loading) the target stays unresolved and the next content
 * change tries again.
 */
internal class KidRowFocus {
    sealed interface Action {
        data object None : Action

        data class Follow(
            val row: Int,
        ) : Action

        data class Recover(
            val row: Int,
        ) : Action
    }

    var focusedKey: String? = null
        private set

    /** The focused row is gone and no row could take focus yet */
    var unresolved = false
        private set

    fun onFocusedRow(
        row: Int,
        keys: List<String>,
    ) {
        keys.getOrNull(row)?.let {
            focusedKey = it
            unresolved = false
        }
    }

    fun onRows(
        keys: List<String>,
        currentRow: Int,
        hasContent: (Int) -> Boolean,
    ): Action {
        val key = focusedKey ?: return Action.None
        val index = keys.indexOf(key)
        if (index >= 0) return if (index != currentRow) Action.Follow(index) else Action.None
        val target = remapFocusedRow(key, currentRow, keys, hasContent)
        if (target == null) {
            unresolved = true
            return Action.None
        }
        focusedKey = keys[target]
        unresolved = false
        return Action.Recover(target)
    }
}

/**
 * Where the focused row [focusedKey] (at [focusedRow] before) is now in [keys]: its new index, or when it's gone, the
 * nearest row with content ([hasContent]) to where it was, the one now in its place first. Null if no row has content.
 */
internal fun remapFocusedRow(
    focusedKey: String,
    focusedRow: Int,
    keys: List<String>,
    hasContent: (Int) -> Boolean,
): Int? {
    val index = keys.indexOf(focusedKey)
    if (index >= 0) return index
    if (keys.isEmpty()) return null
    val start = focusedRow.coerceIn(0, keys.lastIndex)
    for (distance in 0..keys.size) {
        listOf(start + distance, start - distance - 1)
            .firstOrNull { it in keys.indices && hasContent(it) }
            ?.let { return it }
    }
    return null
}
