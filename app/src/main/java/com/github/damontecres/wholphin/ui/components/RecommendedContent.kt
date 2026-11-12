package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.kids.KidContentGuard
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.JustWatchRowService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RecommendedRowsMemory
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamingOrderService
import com.github.damontecres.wholphin.services.StreamingServicesCache
import com.github.damontecres.wholphin.services.SuggestionService
import com.github.damontecres.wholphin.services.SuggestionsResource
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.WeaselHomeRows
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialog
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.detail.music.addToQueue
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.main.HomePageHeader
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.theme.LocalHomeCardAppearance
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.RequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@HiltViewModel(assistedFactory = RecommendedViewModel.Factory::class)
class RecommendedViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
        private val userPreferencesService: UserPreferencesService,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val musicService: MusicService,
        private val backdropService: BackdropService,
        private val mediaManagementService: MediaManagementService,
        private val suggestionService: SuggestionService,
        private val justWatchRowService: JustWatchRowService,
        private val streamingOrderService: StreamingOrderService,
        private val streamingServices: StreamingServicesCache,
        private val rowsMemory: RecommendedRowsMemory,
        private val kidContentGuard: KidContentGuard,
        val serverReportService: ServerReportService,
        @Assisted private val parentId: UUID,
        @Assisted("suggestionsType") private val suggestionsType: BaseItemKind,
        @Assisted private val recommendedRows: List<RecommendedRow<*>>,
        @Assisted private val viewOptions: HomeRowViewOptions,
        /** Movies or shows for the streaming services' Top 10 rows; null for libraries without them. */
        @Assisted("serviceTop10Type") private val serviceTop10Type: BaseItemKind?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                parentId: UUID,
                @Assisted("suggestionsType") suggestionsType: BaseItemKind,
                recommendedRows: List<RecommendedRow<*>>,
                viewOptions: HomeRowViewOptions,
                @Assisted("serviceTop10Type") serviceTop10Type: BaseItemKind?,
            ): RecommendedViewModel
        }

        private val _state = MutableStateFlow(RecommendedState())
        val state: StateFlow<RecommendedState> = _state

        /** The rows this library shows, before Suggestions. */
        private var shownRows: List<RecommendedRow<*>> = recommendedRows

        /** Where [rowsMemory] keeps this page; null for a kid session, which is never remembered */
        private var memoryKey: String? = null

        /** Whether [shownRows] is Movies' and TV Shows' short list */
        private var mainLibraryRows = false

        /** Rows still showing what was loaded last time: each keeps that until its fresh answer arrives */
        private val keptRows: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        fun init() {
            viewModelScope.launchDefault {
                // Show what this library showed last time at once; everything below then refreshes it in place
                memoryKey = memoryKey()
                val remembered = memoryKey?.let(rowsMemory::get)
                if (remembered != null) {
                    shownRows = mainRows(remembered.mainLibraryRows)
                    remembered.rows.forEachIndexed { index, row ->
                        if (row is HomeRowLoadingState.Success) keptRows.add(index)
                    }
                    _state.update { it.copy(loading = LoadingState.Success, rows = remembered.rows) }
                } else {
                    _state.update { it.copy(loading = LoadingState.Loading) }
                }
                val limit =
                    userPreferencesService.flow
                        .first()
                        .appPreferences.homePagePreferences.maxItemsPerRow
                // WeaselPlex's Movies and TV Shows (owner, 2026-10-16): Just Released, Just Added, Your
                // Suggestions, then each streaming service's Top 10. Stand Up Comedy and Sports keep every row.
                val services = if (serviceTop10Type != null && isMainLibrary()) streamingServices() else null
                val rows = mainRows(services != null)
                shownRows = rows
                mainLibraryRows = services != null
                val serviceTitles =
                    services.orEmpty().map { (_, name) -> ResArgStringProvider(R.string.recommended_title_on_service, name) }
                val loadingRows =
                    rows.map { HomeRowLoadingState.Loading(ResStringProvider(it.title)) } +
                        listOf(HomeRowLoadingState.Loading(ResStringProvider(R.string.suggestions))) +
                        serviceTitles.map { HomeRowLoadingState.Loading(it) }
                // The remembered rows stay up only while the page still has the same rows in the same places
                if (remembered == null ||
                    remembered.mainLibraryRows != mainLibraryRows ||
                    remembered.rows.map { it.title } != loadingRows.map { it.title }
                ) {
                    keptRows.clear()
                    _state.update { it.copy(loading = LoadingState.Loading, rows = loadingRows) }
                }
                val jobs =
                    rows.mapIndexed { index, row ->
                        val title = ResStringProvider(row.title)
                        viewModelScope.launchIO {
                            val result =
                                try {
                                    val items = execute(row, limit)
                                    HomeRowLoadingState.Success(
                                        title,
                                        items,
                                        viewOptions,
                                        showViewMore = items.size >= limit,
                                    )
                                } catch (ex: Exception) {
                                    Timber.e(ex, "Exception fetching %s", title)
                                    HomeRowLoadingState.Error(title, null, ex)
                                }
                            setRow(index, result)
                        }
                    }
                fetchSuggestions(rows.size)
                val serviceJobs =
                    services.orEmpty().mapIndexed { index, (code, _) ->
                        val rowIndex = rows.size + 1 + index
                        val title = serviceTitles[index]
                        viewModelScope.launchIO {
                            val result =
                                try {
                                    val items = justWatchRowService.serviceTop10(userId(), code, serviceTop10Type == BaseItemKind.MOVIE)
                                    HomeRowLoadingState.Success(title, items, viewOptions, showViewMore = false)
                                } catch (ex: Exception) {
                                    Timber.e(ex, "Exception fetching the %s Top 10", code)
                                    HomeRowLoadingState.Error(title, null, ex)
                                }
                            setRow(rowIndex, result)
                        }
                    }
                (jobs + serviceJobs).forEach { job ->
                    job.join()
                    if (state.value.rows.any { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }) {
                        // Report loading success once the first non-empty row is ready
                        _state.update { it.copy(loading = LoadingState.Success) }
                        remember()
                    }
                }
            }
        }

        /** The library's own rows: Movies' and TV Shows' short list when [mainLibrary], else all of them */
        private fun mainRows(mainLibrary: Boolean): List<RecommendedRow<*>> =
            if (mainLibrary) recommendedRows.filter { it.title in MAIN_LIBRARY_ROWS } else recommendedRows

        /** Adults only, like the saved Home in the speed plan: a kid's rows follow rules that can change between visits */
        private suspend fun memoryKey(): String? =
            try {
                val server = serverRepository.currentServer
                val user = serverRepository.currentUser
                if (server == null || user == null || kidContentGuard.currentPolicy() != null) {
                    null
                } else {
                    "${server.id}_${user.id}_$parentId"
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Not remembering the rows of %s", parentId)
                null
            }

        /** Put a row's answer in its place. A row showing last time's titles keeps them unless the answer has titles. */
        private fun setRow(
            index: Int,
            result: HomeRowLoadingState,
        ) {
            if (result !is HomeRowLoadingState.Success && index in keptRows) return
            keptRows.remove(index)
            _state.update {
                it.copy(
                    rows =
                        it.rows.toMutableList().apply {
                            set(index, result)
                        },
                )
            }
            remember()
        }

        /** Keep the page as it is now for the next visit, once it is showing */
        private fun remember() {
            val key = memoryKey ?: return
            val current = state.value
            if (current.loading != LoadingState.Success) return
            // A row that pages through the server lives only as long as this page, so it can't be shown again later
            if (current.rows.any { it is HomeRowLoadingState.Success && it.items is ApiRequestPager<*> }) return
            rowsMemory.put(
                key,
                RecommendedRowsMemory.Page(
                    // A row that failed is asked for again next time instead of showing its error first
                    rows = current.rows.map { if (it is HomeRowLoadingState.Error) HomeRowLoadingState.Loading(it.title) else it },
                    mainLibraryRows = mainLibraryRows,
                ),
            )
        }

        private fun userId() = requireNotNull(serverRepository.currentUser).id

        /** Whether this is Movies or TV Shows, rather than Stand Up Comedy or Sports. */
        private suspend fun isMainLibrary(): Boolean =
            BuildConfig.FLAVOR == "weaselfin" &&
                try {
                    val library = api.userLibraryApi.getItem(itemId = parentId).content
                    WeaselHomeRows.isMainLibrary(library.name.orEmpty(), library.collectionType)
                } catch (ex: Exception) {
                    Timber.w(ex, "Couldn't read library %s", parentId)
                    false
                }

        /**
         * The member's streaming services with lists, in their Streaming Services order, as (JustWatch code,
         * name) pairs. The server says which collection has which lists.
         */
        private suspend fun streamingServices(): List<Pair<String, String>> =
            try {
                val userId = userId()
                // Only each service's id and name are used here: Home's recent answer does, or else the light version
                streamingOrderService.sort(userId, streamingServices.get(api, userId, namesOnly = true)).mapNotNull { service ->
                    val name = service.name ?: return@mapNotNull null
                    val code = justWatchRowService.serviceLists(userId, service.id, name)?.code ?: return@mapNotNull null
                    code to name
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't read the streaming services")
                emptyList()
            }

        private suspend fun <T> execute(
            row: RecommendedRow<T>,
            limit: Int?,
        ): List<BaseItem?> =
            if (limit != null) {
                val request = row.handler.prepare(row.request, 0, limit, false)
                row.handler
                    .execute(api, request)
                    .toBaseItems(api, true)
            } else {
                ApiRequestPager(
                    api,
                    row.request,
                    row.handler,
                    viewModelScope,
                    useSeriesForPrimary = true,
                ).init()
            }

        private fun fetchSuggestions(index: Int) {
            viewModelScope.launch(WholphinDispatchers.IO) {
                val limit =
                    userPreferencesService
                        .getCurrent()
                        .appPreferences.homePagePreferences.maxItemsPerRow
                val title = ResStringProvider(R.string.suggestions)
                try {
                    suggestionService
                        .getSuggestionsFlow(parentId, suggestionsType)
                        .collect { resource ->
                            val result =
                                when (resource) {
                                    is SuggestionsResource.Loading -> {
                                        HomeRowLoadingState.Loading(title)
                                    }

                                    is SuggestionsResource.Success -> {
                                        HomeRowLoadingState.Success(
                                            title,
                                            resource.items,
                                            viewOptions,
                                            showViewMore = resource.items.size >= limit,
                                        )
                                    }

                                    is SuggestionsResource.Empty -> {
                                        HomeRowLoadingState.Success(
                                            title,
                                            emptyList(),
                                            viewOptions,
                                            showViewMore = false,
                                        )
                                    }
                                }
                            setRow(index, result)
                        }
                } catch (_: CancellationException) {
                    // no-op
                } catch (ex: Exception) {
                    Timber.e(ex, "Failed to fetch suggestions")
                    setRow(index, HomeRowLoadingState.Error(title, null, ex))
                }
            }
        }

        fun refreshItem(
            position: RowColumn,
            itemId: UUID,
        ) {
            viewModelScope.launchIO {
                val row = state.value.rows.getOrNull(position.row)
                if (row is HomeRowLoadingState.Success) {
                    (row.items as? ApiRequestPager<*>)?.refreshItem(position.column, itemId)
                }
            }
        }

        fun setWatched(
            position: RowColumn,
            itemId: UUID,
            watched: Boolean,
        ) {
            viewModelScope.launchIO {
                favoriteWatchManager.setWatched(itemId, watched)
                refreshItem(position, itemId)
            }
        }

        fun setFavorite(
            position: RowColumn,
            itemId: UUID,
            watched: Boolean,
        ) {
            viewModelScope.launchIO {
                favoriteWatchManager.setFavorite(itemId, watched)
                refreshItem(position, itemId)
            }
        }

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        fun deleteItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    val row = state.value.rows.getOrNull(position.row)
                    if (row is HomeRowLoadingState.Success) {
                        (row.items as? ApiRequestPager<*>)?.refreshPagesAfter(position.column)
                    }
                }
            }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun addToQueue(
            item: BaseItem,
            index: Int,
        ) = addToQueue(api, musicService, item, index)

        fun onClickViewMore(
            position: RowColumn,
            row: HomeRowLoadingState.Success,
        ) {
            if (position.row in shownRows.indices) {
                val recommendedRow = shownRows[position.row] as RecommendedRow<Any>
                navigationManager.navigateTo(
                    Destination.ItemGrid(
                        title = row.title,
                        request = recommendedRow.request,
                        requestHandler = recommendedRow.handler,
                        initialPosition = row.items.size,
                        viewOptions =
                            ViewOptions(
                                aspectRatio = viewOptions.aspectRatio,
                            ),
                    ),
                )
            } else {
                // Suggestions
                navigationManager.navigateTo(
                    Destination.ItemGrid(
                        title = row.title,
                        request =
                            GetItemsRequest(
                                ids = row.items.mapNotNull { it?.id },
                            ),
                        requestHandler = GetItemsRequestHandler,
                        initialPosition = row.items.size,
                        viewOptions =
                            ViewOptions(
                                aspectRatio = viewOptions.aspectRatio,
                            ),
                    ),
                )
            }
        }
    }

/** The rows Movies and TV Shows keep from their library's list, before Suggestions and the Top 10 rows. */
private val MAIN_LIBRARY_ROWS = setOf(R.string.recently_released, R.string.recently_added)

data class RecommendedState(
    val loading: LoadingState = LoadingState.Pending,
    val rows: List<HomeRowLoadingState> = emptyList(),
)

data class RecommendedRow<T>(
    val title: Int,
    val handler: RequestHandler<T>,
    val request: T,
)

@Composable
private fun recommendedRowHeading(row: HomeRowLoadingState.Success): Pair<String, String?> =
    when ((row.title as? ResStringProvider)?.stringResId) {
        R.string.continue_watching -> {
            stringResource(R.string.continue_watching) to "${stringResource(R.string.next_up)} &"
        }

        R.string.next_up -> {
            stringResource(R.string.continue_watching) to "${stringResource(R.string.next_up)} &"
        }

        R.string.recently_released -> {
            stringResource(R.string.recommended_title_released) to
                stringResource(R.string.recommended_kicker_just)
        }

        R.string.recently_added -> {
            stringResource(R.string.recommended_title_added) to stringResource(R.string.recommended_kicker_just)
        }

        R.string.top_unwatched -> {
            stringResource(R.string.recommended_title_unwatched) to
                stringResource(R.string.recommended_kicker_top_rated)
        }

        R.string.suggestions -> {
            stringResource(R.string.suggestions) to stringResource(R.string.recommended_kicker_your)
        }

        else -> {
            if ((row.title as? ResArgStringProvider)?.stringResId == R.string.recommended_title_on_service) {
                // A streaming service's Top 10: "TOP 10" over "ON NETFLIX"
                row.title.getString() to stringResource(R.string.quick_list_top_10)
            } else {
                row.title.getString() to null
            }
        }
    }

@Composable
fun RecommendedContent(
    preferences: UserPreferences,
    viewModel: RecommendedViewModel,
    modifier: Modifier = Modifier,
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    onFocusPosition: ((RowColumn) -> Unit)? = null,
    headerModifier: Modifier = HeaderUtils.homeModifier(),
    splitRecommendedTitles: Boolean = false,
) {
    var showContextMenu by remember { mutableStateOf<ContextMenu?>(null) }
    var overviewDialog by remember { mutableStateOf<ItemDetailsDialogInfo?>(null) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    val playlistState by playlistViewModel.playlistState.collectAsState()

    OneTimeLaunchedEffect {
        viewModel.init()
    }
    val state by viewModel.state.collectAsState()
    // Recommended rows own their view options; apply the live library Home appearance here
    // so their card provider cannot replace it with stock border/glow defaults.
    val libraryCardAppearance = LocalHomeCardAppearance.current
    val homeRows =
        if (isWeaselTv()) {
            state.rows.map { row ->
                if (row is HomeRowLoadingState.Success) {
                    row.copy(
                        viewOptions = ApprovedHomeLayout.applyPresentation(row.viewOptions).copy(cardAppearance = libraryCardAppearance),
                    )
                } else {
                    row
                }
            }
        } else {
            state.rows
        }

    when (val st = state.loading) {
        is LoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        LoadingState.Pending -> {
            // The few moments before it is known whether last time's rows can be shown: hold focus as the loading
            // page does, without flashing the loading picture in front of rows that are about to appear
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
            Box(modifier.fillMaxSize().focusRequester(focusRequester).focusable())
        }

        LoadingState.Loading -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            var position by rememberPosition()
            val contextActions =
                remember {
                    ContextMenuActions(
                        navigateTo = viewModel.navigationManager::navigateTo,
                        onClickWatch = { itemId, watched ->
                            viewModel.setWatched(position, itemId, watched)
                        },
                        onClickFavorite = { itemId, favorite ->
                            viewModel.setFavorite(position, itemId, favorite)
                        },
                        onClickAddPlaylist = { itemId ->
                            playlistViewModel.loadPlaylists()
                            showPlaylistDialog.makePresent(itemId)
                        },
                        onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                        onDeleteItem = { viewModel.deleteItem(position, it) },
                        onShowOverview = { overviewDialog = ItemDetailsDialogInfo(it) },
                        onChooseVersion = { _, _ ->
                            // Not supported on this page
                        },
                        onChooseTracks = { result ->
                            // Not supported on this page
                        },
                        onClearChosenStreams = {
                            // Not supported on this page
                        },
                    )
                }

            HomePageContent(
                homeRows = homeRows,
                rowHeading =
                    if (splitRecommendedTitles && isWeaselTv()) {
                        { row -> recommendedRowHeading(row) }
                    } else {
                        null
                    },
                position = position,
                onClickItem = { _, item ->
                    viewModel.navigationManager.navigateTo(item.destination())
                },
                onLongClickItem = { position, item ->
                    showContextMenu =
                        ContextMenu.ForBaseItem(
                            fromLongClick = true,
                            item = item,
                            chosenStreams = null,
                            showGoTo = true,
                            showStreamChoices = false,
                            canDelete = viewModel.canDelete(item, preferences.appPreferences),
                            canRemoveContinueWatching = false,
                            canRemoveNextUp = false,
                            actions = contextActions,
                        )
                },
                onClickPlay = { _, item ->
                    viewModel.navigationManager.navigateTo(Destination.Playback(item))
                },
                onFocusPosition = {
                    position = it
                    val nonEmptyRowBefore =
                        state.rows
                            .subList(0, it.row)
                            .count {
                                it is HomeRowLoadingState.Success && it.items.isEmpty()
                            }
                    onFocusPosition?.invoke(
                        RowColumn(
                            it.row - nonEmptyRowBefore,
                            it.column,
                        ),
                    )
                },
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                showViewMore = true,
                onClickViewMore = viewModel::onClickViewMore,
                modifier = modifier,
                headerComposable = { focusedItem ->
                    HomePageHeader(
                        item = focusedItem,
                        showLogo = preferences.appPreferences.interfacePreferences.showLogos,
                        modifier =
                            if (splitRecommendedTitles && isWeaselTv() &&
                                position.row >
                                homeRows.indexOfFirst {
                                    it is HomeRowLoadingState.Success && it.items.isNotEmpty()
                                }
                            ) {
                                HeaderUtils.homeModifier()
                            } else {
                                headerModifier
                            },
                        compactLogo = splitRecommendedTitles && isWeaselTv(),
                        compactDetails =
                            splitRecommendedTitles && isWeaselTv() &&
                                position.row <=
                                homeRows.indexOfFirst {
                                    it is HomeRowLoadingState.Success && it.items.isNotEmpty()
                                },
                    )
                },
            )
        }
    }
    overviewDialog?.let { info ->
        ItemDetailsDialog(
            info = info,
            showFilePath =
                viewModel.serverRepository.currentUserDto
                    ?.policy
                    ?.isAdministrator == true,
            onDismissRequest = { overviewDialog = null },
        )
    }
    showContextMenu?.let { contextMenu ->
        ContextMenuDialog(
            onDismissRequest = { showContextMenu = null },
            getMediaSource = null,
            contextMenu = contextMenu,
            preferredSubtitleLanguage = null,
        )
    }
    showPlaylistDialog.compose { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { showPlaylistDialog.makeAbsent() },
            onClick = {
                playlistViewModel.addToPlaylist(it.id, itemId)
                showPlaylistDialog.makeAbsent()
            },
            createEnabled = true,
            onCreatePlaylist = {
                playlistViewModel.createPlaylistAndAddItem(it, itemId)
                showPlaylistDialog.makeAbsent()
            },
            onSearch = playlistViewModel::loadPlaylists,
            elevation = 3.dp,
        )
    }
}
