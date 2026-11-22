package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.GetItemsFilterOverride
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.kids.isStaleSession
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.CollectionGroups
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.services.ThemeSongPlayer
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.equalsNotNull
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.theme.ApprovedCollectionCardAppearance
import com.github.damontecres.wholphin.ui.theme.LocalHidePosterTypeBadge
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.BlockingList
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetPersonsHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.successValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows a collection folder as a grid
 *
 * This is the "Library" tab for Movies or TV shows
 */
@Composable
fun CollectionFolderView(
    preferences: UserPreferences,
    itemId: UUID,
    initialFilter: CollectionFolderFilter,
    recursive: Boolean,
    onClickItem: (Int, BaseItem) -> Unit,
    sortOptions: List<ItemSortBy>,
    playEnabled: Boolean,
    defaultViewOptions: ViewOptions,
    modifier: Modifier = Modifier,
    viewModelKey: String? = itemId.toServerString(),
    initialSortAndDirection: SortAndDirection? = null,
    showTitle: Boolean = true,
    positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    useSeriesForPrimary: Boolean = true,
    filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    focusRequesterOnEmpty: FocusRequester? = null,
) = CollectionFolderView(
    preferences,
    itemId.toServerString(),
    initialFilter,
    recursive,
    GridClickActions(onClickItem = onClickItem),
    sortOptions,
    playEnabled,
    viewModelKey = viewModelKey,
    defaultViewOptions = defaultViewOptions,
    modifier = modifier,
    initialSortAndDirection = initialSortAndDirection,
    showTitle = showTitle,
    positionCallback = positionCallback,
    useSeriesForPrimary = useSeriesForPrimary,
    filterOptions = filterOptions,
    focusRequesterOnEmpty = focusRequesterOnEmpty,
)

@Composable
fun CollectionFolderView(
    preferences: UserPreferences,
    itemId: UUID,
    initialFilter: CollectionFolderFilter,
    recursive: Boolean,
    actions: GridClickActions,
    sortOptions: List<ItemSortBy>,
    playEnabled: Boolean,
    defaultViewOptions: ViewOptions,
    modifier: Modifier = Modifier,
    viewModelKey: String? = itemId.toServerString(),
    initialSortAndDirection: SortAndDirection? = null,
    showTitle: Boolean = true,
    positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    useSeriesForPrimary: Boolean = true,
    filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    focusRequesterOnEmpty: FocusRequester? = null,
) = CollectionFolderView(
    preferences,
    itemId.toServerString(),
    initialFilter,
    recursive,
    actions,
    sortOptions,
    playEnabled,
    viewModelKey = viewModelKey,
    defaultViewOptions = defaultViewOptions,
    modifier = modifier,
    initialSortAndDirection = initialSortAndDirection,
    showTitle = showTitle,
    positionCallback = positionCallback,
    useSeriesForPrimary = useSeriesForPrimary,
    filterOptions = filterOptions,
    focusRequesterOnEmpty = focusRequesterOnEmpty,
)

@Composable
fun CollectionFolderView(
    preferences: UserPreferences,
    itemId: String,
    initialFilter: CollectionFolderFilter,
    recursive: Boolean,
    actions: GridClickActions,
    sortOptions: List<ItemSortBy>,
    playEnabled: Boolean,
    defaultViewOptions: ViewOptions,
    modifier: Modifier = Modifier,
    viewModelKey: String? = itemId,
    initialSortAndDirection: SortAndDirection? = null,
    showTitle: Boolean = true,
    positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    useSeriesForPrimary: Boolean = true,
    filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    focusRequesterOnEmpty: FocusRequester? = null,
    viewModel: CollectionFolderViewModel =
        hiltViewModel<CollectionFolderViewModel, CollectionFolderViewModel.Factory>(
            key = viewModelKey,
        ) {
            it.create(
                itemId = itemId,
                initialSortAndDirection = initialSortAndDirection,
                recursive = recursive,
                collectionFilter = initialFilter,
                useSeriesForPrimary = useSeriesForPrimary,
                defaultViewOptions = defaultViewOptions,
            )
        },
) {
    val state by viewModel.state.collectAsState()
    val homeSettings by viewModel.homeSettingsService.currentSettings.collectAsState()
    // The values every saved Picks row held when it was retired, so collection posters look the same
    val collectionHomeOptions =
        remember { ApprovedHomeLayout.apply(HomeRowViewOptions(useSeries = false, cardAppearance = ApprovedCollectionCardAppearance)) }
    val streamingCardAppearance =
        homeSettings.rows
            .firstOrNull { StreamingCollections.isStreamingRow(it.config) }
            ?.config
            ?.viewOptions
            ?.cardAppearance
            ?: StreamingCollections.row.viewOptions.cardAppearance
    LifecycleResumeEffect(itemId) {
        viewModel.onResumePage()

        onPauseOrDispose {
            viewModel.release()
        }
    }
    CollectionFolderViewContent(
        preferences = preferences,
        state = state,
        savedPosition = viewModel.position,
        itemId = itemId,
        initialFilter = initialFilter,
        recursive = recursive,
        actions = actions,
        sortOptions = sortOptions,
        playEnabled = playEnabled,
        defaultViewOptions = defaultViewOptions,
        viewActions = viewModel,
        modifier = modifier,
        provider = viewModel,
        showTitle = showTitle,
        positionCallback = { columns, index ->
            viewModel.position = index
            positionCallback?.invoke(columns, index)
        },
        filterOptions = filterOptions,
        focusRequesterOnEmpty = focusRequesterOnEmpty,
        collectionHomeOptions = collectionHomeOptions,
        streamingCardAppearance = streamingCardAppearance,
    )
}

@Composable
fun CollectionFolderViewContent(
    preferences: UserPreferences,
    state: CollectionFolderState,
    savedPosition: Int,
    itemId: String,
    initialFilter: CollectionFolderFilter,
    recursive: Boolean,
    actions: GridClickActions,
    sortOptions: List<ItemSortBy>,
    playEnabled: Boolean,
    defaultViewOptions: ViewOptions,
    viewActions: CollectionFolderViewActions,
    modifier: Modifier = Modifier,
    provider: ContextMenuProvider,
    showTitle: Boolean = true,
    positionCallback: ((columns: Int, position: Int) -> Unit)? = null,
    filterOptions: List<ItemFilterBy<*>> = DefaultFilterOptions,
    focusRequesterOnEmpty: FocusRequester? = null,
    collectionHomeOptions: HomeRowViewOptions =
        ApprovedHomeLayout.apply(
            HomeRowViewOptions(cardAppearance = ApprovedCollectionCardAppearance),
        ),
    streamingCardAppearance: HomeCardAppearance = StreamingCollections.row.viewOptions.cardAppearance,
    watchlistLibraries: List<Library>? = null,
) {
    var position by rememberInt(savedPosition)

    // Track if leaving page due to clicking the random button so focus can be restored there
    var clickedRandom by rememberSaveable { mutableStateOf(false) }

    // Keep focus on a group button while its collections load
    var clickedGroup by rememberSaveable { mutableStateOf(false) }
    val groupButtonFocusRequester = remember { FocusRequester() }

    val contextMenu = rememberContextMenu(preferences, provider)
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var filterDropdownShowing by remember { mutableStateOf(false) }
    val headerRowFocusRequester = remember { FocusRequester() }
    val filterButtonFocusRequester = remember { FocusRequester() }
    val randomButtonFocusRequester = remember { FocusRequester() }

    val gridActions =
        remember(actions) {
            GridClickActions(
                onClickItem = { index, item ->
                    position = index
                    actions.onClickItem.invoke(index, item)
                },
                onLongClickItem = { index, item ->
                    position = index
                    if (actions.onLongClickItem != null) {
                        actions.onLongClickItem.invoke(index, item)
                    } else {
                        contextMenu.showContextMenu(index, item)
                    }
                },
                onClickPlayAll =
                    actions.onClickPlayAll ?: { shuffle ->
                        itemId.toUUIDOrNull()?.let {
                            val destination =
                                if (state.item.successValue?.type == BaseItemKind.PHOTO_ALBUM) {
                                    Destination.Slideshow(
                                        parentId = it,
                                        index = 0,
                                        filter = CollectionFolderFilter(filter = state.filter),
                                        sortAndDirection = state.sortAndDirection,
                                        recursive = true,
                                        startSlideshow = true,
                                    )
                                } else {
                                    Destination.PlaybackList(
                                        itemId = it,
                                        startIndex = 0,
                                        shuffle = shuffle,
                                        recursive = recursive,
                                        sortAndDirection = state.sortAndDirection,
                                        filter = state.filter,
                                    )
                                }
                            viewActions.navigateTo(destination)
                        }
                        Unit
                    },
                onClickPlayRemoteButton =
                    actions.onClickPlayRemoteButton ?: { index, item ->
                        val destination =
                            if (item.type == BaseItemKind.PHOTO_ALBUM) {
                                Destination.Slideshow(
                                    parentId = item.id,
                                    index = index,
                                    filter = CollectionFolderFilter(filter = state.filter),
                                    sortAndDirection = state.sortAndDirection,
                                    recursive = true,
                                    startSlideshow = true,
                                )
                            } else {
                                Destination.Playback(item)
                            }
                        viewActions.navigateTo(destination)
                    },
            )
        }
    Box(modifier = modifier) {
        when (val st = state.item) {
            DataLoadingState.Loading,
            DataLoadingState.Pending,
            -> {
                LoadingPage(Modifier.fillMaxSize())
            }

            is DataLoadingState.Error -> {
                ErrorMessage(st, Modifier.fillMaxSize())
            }

            is DataLoadingState.Success<BaseItem?> -> {
                val item = st.successValue
                val title =
                    initialFilter.nameOverride
                        ?: item?.name
                        ?: item?.data?.collectionType?.name
                        ?: stringResource(R.string.collection)
                val collectionsLayout =
                    isWeaselTv() && (
                        item?.data?.collectionType == CollectionType.BOXSETS ||
                            initialFilter.filter.includeItemTypes == listOf(BaseItemKind.BOX_SET)
                    )
                Column(modifier = Modifier.fillMaxSize()) {
                    var showHeader by rememberSaveable { mutableStateOf(true) }
                    val gridFocusRequester = remember { FocusRequester() }
                    val pager = remember(state.items) { state.items.successValue }

                    val focusedItem = pager?.getOrNull(position)
                    if (state.viewOptions.showBackdrop) {
                        LaunchedEffect(focusedItem) {
                            focusedItem?.let(viewActions::updateBackdrop)
                        }
                    }
                    CollectionFolderHeader(
                        listIsNotEmpty = state.items.successValue?.isNotEmpty() == true,
                        showHeader = showHeader || state.items !is DataLoadingState.Success,
                        showTitle = showTitle,
                        title = title,
                        collectionsOptions = collectionHomeOptions.takeIf { collectionsLayout },
                        sortAndDirection = state.sortAndDirection,
                        onSortChange = {
                            viewActions.onSortChange(it, recursive, state.filter)
                        },
                        sortOptions = sortOptions,
                        currentFilter = state.filter,
                        onFilterChange = {
                            viewActions.onFilterChange(it, recursive)
                        },
                        getPossibleFilterValues = {
                            viewActions.getFilterOptionValues(it)
                        },
                        filterOptions = filterOptions,
                        onClickShowViewOptions = { showViewOptions = true },
                        onClickRandom = {
                            clickedRandom = true
                            viewActions.onClickRandom()
                        },
                        modifier = Modifier.focusRequester(headerRowFocusRequester),
                        onShowFilterDropdown = { filterDropdownShowing = it },
                        filterButtonFocusRequester = filterButtonFocusRequester,
                        randomButtonFocusRequester = randomButtonFocusRequester,
                        collectionGroups = if (collectionsLayout) state.collectionGroups else emptyList(),
                        collectionGroup = state.collectionGroup,
                        onCollectionGroupChange = {
                            clickedGroup = true
                            viewActions.onCollectionGroupChange(it)
                        },
                        groupButtonFocusRequester = groupButtonFocusRequester,
                    )

                    when (val pager = state.items) {
                        is DataLoadingState.Error -> {
                            LaunchedEffect(Unit) {
                                (focusRequesterOnEmpty ?: headerRowFocusRequester).tryRequestFocus()
                            }
                            ErrorMessage(pager, Modifier.fillMaxSize())
                        }

                        DataLoadingState.Loading,
                        DataLoadingState.Pending,
                        -> {
                            LoadingPage(Modifier.fillMaxSize())
                        }

                        is DataLoadingState.Success<List<BaseItem?>> -> {
                            LaunchedEffect(Unit) {
                                val focusRequester =
                                    when {
                                        contextMenu.isShowing -> null
                                        filterDropdownShowing -> filterButtonFocusRequester
                                        clickedRandom -> randomButtonFocusRequester
                                        clickedGroup -> groupButtonFocusRequester
                                        pager.data.isNotEmpty() -> gridFocusRequester
                                        else -> focusRequesterOnEmpty ?: headerRowFocusRequester
                                    }
                                focusRequester?.tryRequestFocus()
                                clickedRandom = false
                                clickedGroup = false
                            }
                            Box(Modifier.fillMaxSize()) {
                                if (state.viewOptions.type == ViewOptionsType.GRID) {
                                    CompositionLocalProvider(
                                        LocalHidePosterTypeBadge provides
                                            (
                                                isWeaselTv() &&
                                                    item?.data?.collectionType in listOf(CollectionType.MOVIES, CollectionType.TVSHOWS)
                                            ),
                                    ) {
                                        CollectionFolderGrid(
                                            preferences = preferences,
                                            collectionType = item?.data?.collectionType,
                                            initialPosition = savedPosition,
                                            items = pager.data,
                                            sortAndDirection = state.sortAndDirection,
                                            modifier = Modifier.fillMaxSize(),
                                            gridFocusRequester = gridFocusRequester,
                                            onClickItem = gridActions.onClickItem,
                                            onLongClickItem = gridActions.onLongClickItem!!,
                                            positionCallback = { columns, pos ->
                                                showHeader = pos < columns
                                                position = pos
                                                positionCallback?.invoke(columns, pos)
                                            },
                                            letterPosition = { viewActions.positionOfLetter(it) ?: -1 },
                                            viewOptions =
                                                if (collectionsLayout) {
                                                    state.viewOptions.copy(
                                                        showTitles = false,
                                                    )
                                                } else {
                                                    state.viewOptions
                                                },
                                            onClickPlay = gridActions.onClickPlayRemoteButton!!,
                                            focusedItem = focusedItem,
                                            collectionCardAppearance = collectionHomeOptions.cardAppearance,
                                            streamingCardAppearance = streamingCardAppearance,
                                            watchlistLibraries = watchlistLibraries,
                                        )
                                    }
                                } else {
                                    CollectionFolderList(
                                        preferences = preferences,
                                        collectionType = item?.data?.collectionType,
                                        initialPosition = savedPosition,
                                        items = pager.data,
                                        sortAndDirection = state.sortAndDirection,
                                        modifier = Modifier.fillMaxSize(),
                                        gridFocusRequester = gridFocusRequester,
                                        onClickItem = gridActions.onClickItem,
                                        onLongClickItem = gridActions.onLongClickItem!!,
                                        positionCallback = { columns, pos ->
                                            showHeader = pos < columns
                                            position = pos
                                            positionCallback?.invoke(columns, pos)
                                        },
                                        letterPosition = { viewActions.positionOfLetter(it) ?: -1 },
                                        viewOptions = state.viewOptions,
                                        onClickPlay = gridActions.onClickPlayRemoteButton!!,
                                        focusedItem = focusedItem,
                                    )
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    state.backgroundLoading == LoadingState.Loading,
                                    modifier =
                                        Modifier
                                            .align(Alignment.Center)
                                            .padding(16.dp),
                                ) {
                                    CircularProgress(
                                        Modifier
                                            .background(
                                                MaterialTheme.colorScheme.background.copy(alpha = .25f),
                                                shape = CircleShape,
                                            ).size(64.dp)
                                            .padding(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    contextMenu.Compose()
    AnimatedVisibility(showViewOptions) {
        ViewOptionsDialog(
            viewOptions = state.viewOptions,
            defaultViewOptions = defaultViewOptions,
            onDismissRequest = {
                showViewOptions = false
                viewActions.saveViewOptions(state.viewOptions)
            },
            onViewOptionsChange = viewActions::saveViewOptions,
        )
    }
}
