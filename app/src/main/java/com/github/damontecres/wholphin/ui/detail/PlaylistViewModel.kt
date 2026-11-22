package com.github.damontecres.wholphin.ui.detail

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.filter.DefaultPlaylistItemsOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlaylistCreator
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuDialog
import com.github.damontecres.wholphin.ui.components.DialogListEdge
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.ExpandablePlayButton
import com.github.damontecres.wholphin.ui.components.FilterByButton
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.Optional
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.SortByButton
import com.github.damontecres.wholphin.ui.components.keepFocusedItemVisible
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.BoxSetSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.enableMarquee
import com.github.damontecres.wholphin.ui.formatDateTime
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemColors
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.LocalClock
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

/*
 * The playlist page's view model and state, moved out of the Compose file PlaylistDetails.kt (no-Compose UI,
 * 2026-11-22). FilterAndSort lost its Compose @Immutable annotation; nothing else changed.
 */

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext internal val context: Context,
        internal val api: ApiClient,
        internal val navigationManager: NavigationManager,
        internal val mediaManagementService: MediaManagementService,
        private val backdropService: BackdropService,
        private val serverRepository: ServerRepository,
        private val libraryDisplayInfoDao: LibraryDisplayInfoDao,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val serverReportService: ServerReportService,
        private val filterOptionCache: FilterOptionCache,
        private val playlistCreator: PlaylistCreator,
        @Assisted internal val itemId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(itemId: UUID): PlaylistViewModel
        }

        val state = MutableStateFlow(PlaylistDetailsState())

        init {
            init()
        }

        private fun init() {
            state.update { it.copy(loading = LoadingState.Loading) }
            viewModelScope.launchDefault {
                try {
                    val playlist =
                        api.userLibraryApi
                            .getItem(itemId)
                            .content
                            .let { BaseItem(it, false) }
                    val user = serverRepository.currentUser
                    val canEdit =
                        user?.let { user ->
                            try {
                                val permission by api.playlistsApi.getPlaylistUser(itemId, user.id)
                                permission.canEdit
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: InvalidStatusException) {
                                if (ex.status == 404) {
                                    // Server will return this if no permission exists
                                    Timber.w(
                                        "User doesn't have permission to edit playlist %s",
                                        itemId,
                                    )
                                } else {
                                    Timber.e(
                                        ex,
                                        "Error checking user permission for playlist %s",
                                        itemId,
                                    )
                                }
                                false
                            } catch (ex: Exception) {
                                Timber.e(
                                    ex,
                                    "Error checking user permission for playlist %s",
                                    itemId,
                                )
                                false
                            }
                        } ?: false
                    state.update {
                        it.copy(
                            playlist = playlist,
                            canEdit = canEdit,
                        )
                    }

                    val libraryDisplayInfo =
                        user?.let { user ->
                            libraryDisplayInfoDao.getItem(user, itemId)
                        }
                    val filter = libraryDisplayInfo?.filter ?: GetItemsFilter()
                    val sortAndDirection =
                        libraryDisplayInfo?.sortAndDirection ?: SortAndDirection(
                            ItemSortBy.DEFAULT,
                            SortOrder.ASCENDING,
                        )
                    loadItems(filter, sortAndDirection).join()
                    determineMediaType()
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching playlist %s", itemId)
                    state.update { it.copy(loading = LoadingState.Error(ex)) }
                }
            }
        }

        fun loadItems(
            filter: GetItemsFilter,
            sortAndDirection: SortAndDirection,
        ) = viewModelScope.launchIO {
            backdropService.clearBackdrop()
            state.update {
                it.copy(
                    loading = LoadingState.Loading,
                    filterAndSort = FilterAndSort(filter, sortAndDirection),
                )
            }

            serverRepository.currentUser?.let { user ->
                viewModelScope.launchIO {
                    val libraryDisplayInfo =
                        libraryDisplayInfoDao.getItem(user, itemId)?.copy(
                            filter = filter,
                            sort = sortAndDirection.sort,
                            direction = sortAndDirection.direction,
                        )
                            ?: LibraryDisplayInfo(
                                userId = user.rowId,
                                itemId = itemId.toServerString(),
                                sort = sortAndDirection.sort,
                                direction = sortAndDirection.direction,
                                filter = filter,
                                viewOptions = null,
                            )
                    libraryDisplayInfoDao.saveItem(libraryDisplayInfo)
                }

                val request =
                    filter.applyTo(
                        GetItemsRequest(
                            parentId = itemId,
                            userId = user.id,
                            fields = SlimItemFields,
                            sortBy = listOf(sortAndDirection.sort),
                            sortOrder = listOf(sortAndDirection.direction),
                        ),
                    )
                try {
                    val pager =
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            viewModelScope,
                        ).init()
                    state.update {
                        it.copy(
                            items = pager,
                            loading = LoadingState.Success,
                        )
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching playlist %s", itemId)
                    state.update {
                        it.copy(
                            items = emptyList(),
                            loading = LoadingState.Error(ex),
                        )
                    }
                }
            }
        }

        /**
         * This method tries to determine the [MediaType] of a playlist
         *
         * In theory, the server will set the type, but sometimes it doesn't
         */
        private suspend fun determineMediaType() {
            // Use the type the server says
            var mediaType =
                state.value.playlist
                    ?.data
                    ?.mediaType ?: MediaType.UNKNOWN
            mediaType =
                if (mediaType == MediaType.UNKNOWN) {
                    // Otherwise, if a most of the list is one type, we can assume that type
                    val pager = (state.value.items as? ApiRequestPager<*>)
                    if (pager != null && pager.size <= 50) {
                        val types =
                            (0..<50.coerceAtMost(pager.size)).groupBy { index ->
                                val pagerItem = pager.getBlocking(index)
                                when (pagerItem?.type) {
                                    BaseItemKind.AUDIO -> MediaType.AUDIO

                                    BaseItemKind.VIDEO,
                                    BaseItemKind.EPISODE,
                                    BaseItemKind.MOVIE,
                                    BaseItemKind.BOX_SET,
                                    -> MediaType.VIDEO

                                    else -> MediaType.UNKNOWN
                                }
                            }
                        if (types.keys.size == 1) {
                            types.keys.first()
                        } else {
                            MediaType.UNKNOWN
                        }
                    } else {
                        MediaType.UNKNOWN
                    }
                } else {
                    mediaType
                }
            Timber.d("mediaType=%s", mediaType)
            state.update {
                it.copy(mediaType = mediaType)
            }
        }

        suspend fun getFilterOptionValues(filterOption: ItemFilterBy<*>): List<FilterValueOption> =
            filterOptionCache.getFilterOptionValues(
                itemId,
                filterOption,
            )

        fun updateBackdrop(item: BaseItem) {
            viewModelScope.launchIO {
                backdropService.submit(item)
            }
        }

        fun setWatched(
            itemId: UUID,
            played: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setWatched(itemId, played)
        }

        fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ) = viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            favoriteWatchManager.setFavorite(itemId, favorite)
        }

        fun sendMediaReport(itemId: UUID) {
            viewModelScope.launchDefault { serverReportService.sendMediaReportFor(itemId) }
        }

        fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        fun deleteItem(item: BaseItem) {
            deleteItem(context, mediaManagementService, item) {
                if (item.id == itemId) {
                    navigationManager.goBack()
                } else {
                    init()
                }
            }
        }

        /** The video player is the only player, and it skips audio tracks one at a time: say so once instead. */
        fun audioNotSupported() {
            viewModelScope.launchIO { showToast(context, "Audio playlists are not supported", Toast.LENGTH_SHORT) }
        }

        fun removeFromPlaylist(
            index: Int,
            itemId: UUID,
        ) {
            viewModelScope.launchIO {
                try {
                    playlistCreator.removeFromServerPlaylist(
                        playlistId = this@PlaylistViewModel.itemId,
                        itemId = itemId,
                    )
                    (state.value.items as? ApiRequestPager<*>)?.refreshPagesAfter(index)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(
                        ex,
                        "Error removing %s from playlist %s",
                        itemId,
                        this@PlaylistViewModel.itemId,
                    )
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        fun onMoveItem(
            index: Int,
            itemId: UUID,
            direction: MoveDirection,
        ) {
            viewModelScope.launchIO {
                val newIndex = index + if (direction == MoveDirection.UP) -1 else 1
                api.playlistsApi.moveItem(
                    playlistId = this@PlaylistViewModel.itemId.toServerString(),
                    itemId = itemId.toServerString(),
                    newIndex = newIndex,
                )
                (state.value.items as? ApiRequestPager<*>)?.refreshPagesAfter(index - 1)
            }
        }
    }

data class FilterAndSort(
    val filter: GetItemsFilter,
    val sortAndDirection: SortAndDirection,
)

data class PlaylistDetailsState(
    val playlist: BaseItem? = null,
    val mediaType: MediaType = MediaType.UNKNOWN,
    val items: List<BaseItem?> = emptyList(),
    val filterAndSort: FilterAndSort =
        FilterAndSort(
            filter = GetItemsFilter(),
            sortAndDirection =
                SortAndDirection(
                    ItemSortBy.DEFAULT,
                    SortOrder.ASCENDING,
                ),
        ),
    val loading: LoadingState = LoadingState.Pending,
    val canEdit: Boolean = false,
)

