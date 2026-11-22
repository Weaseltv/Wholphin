package com.github.damontecres.wholphin.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.CancelResult
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RequestStatusRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import javax.inject.Inject

/** The Requests ACTIVITY queue's actions: cancel and open requests (moved out of SeerrRequestsPage.kt, no Compose state) */
@HiltViewModel
class SeerrRequestsViewModel
    @Inject
    constructor(
        val requests: RequestStatusRepository,
        val navigation: NavigationManager,
    ) : ViewModel() {
        private val _actionError = MutableStateFlow<String?>(null)

        /** The last action's failure message, shown above the queue */
        val actionError: StateFlow<String?> = _actionError.asStateFlow()

        /** Long-press → Cancel request on an Active or Waiting row (owner 2026-11-03) */
        fun cancel(requestId: Int) {
            viewModelScope.launch {
                _actionError.value = null
                _actionError.value =
                    when (requests.cancel(requestId)) {
                        CancelResult.CANCELLED, CancelResult.GONE -> null
                        CancelResult.FINISHED -> "This title is already ready to watch."
                        CancelResult.FAILED -> "Couldn't cancel this request. Please try again."
                    }
            }
        }

        fun open(
            requestId: Int,
            expectedScope: String?,
        ) {
            val state = requests.state.value
            if (state.scope != expectedScope) return
            val item = state.items.firstOrNull { it.request.id == requestId } ?: return
            val progress =
                requests.progress.state.value
                    .takeIf { state.connected && requests.acceptsProgress(it) }
            if (item.stage.ready || item.partlyWatchable(item.liveProgress(progress))) {
                viewModelScope.launch {
                    try {
                        val id = item.jellyfinId ?: requests.libraryItemId(requestId, expectedScope)
                        if (requests.state.value.scope != expectedScope) return@launch
                        if (id !=
                            null
                        ) {
                            navigation.navigateTo(
                                Destination.MediaItem(
                                    id,
                                    if (item.request.type ==
                                        "tv"
                                    ) {
                                        BaseItemKind.SERIES
                                    } else {
                                        BaseItemKind.MOVIE
                                    },
                                ),
                            )
                        } else {
                            _actionError.value = "Couldn't open this title right now. Please try again."
                        }
                    } catch (ex: kotlinx.coroutines.CancellationException) {
                        throw ex
                    } catch (_: Exception) {
                        if (requests.state.value.scope ==
                            expectedScope
                        ) {
                            _actionError.value = "Couldn't open this title right now. Please try again."
                        }
                    }
                }
            } else {
                val metadata =
                    item.item ?: item.request.media?.tmdbId?.let { tmdb ->
                        com.github.damontecres.wholphin.data.model.DiscoverItem(
                            id = tmdb,
                            type =
                                com.github.damontecres.wholphin.data.model.SeerrItemType
                                    .fromString(item.request.type),
                            title = item.title,
                            subtitle = null,
                            overview = null,
                            availability = com.github.damontecres.wholphin.data.model.SeerrAvailability.UNKNOWN,
                            releaseDate = null,
                            posterUrl = null,
                            backDropUrl = null,
                            logoUrl = null,
                            jellyfinItemId = null,
                        )
                    }
                if (metadata != null) {
                    navigation.navigateTo(Destination.DiscoveredItem(metadata))
                } else {
                    _actionError.value = "Title details are unavailable. Please try again."
                }
            }
        }
    }
