package com.github.damontecres.wholphin.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

/*
 * The series removed from Next Up, Compose-free (moved unchanged from RemovedNextUpContent.kt, 2026-11-22).
 */

/** Loads the series removed from Next Up and puts one back */
@HiltViewModel
class RemovedNextUpContentViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
    ) : ViewModel() {
        private val mutex = Mutex()

        private val _state = MutableStateFlow(RemovedNextUpState())
        val state: StateFlow<RemovedNextUpState> = _state

        init {
            viewModelScope.launchDefault {
                serverRepository.currentUserFlow.collectLatest { user ->
                    _state.update { RemovedNextUpState() }
                    if (user == null) {
                        return@collectLatest
                    }
                    try {
                        val removed = latestNextUpService.getRemovedFromNextUp(user.id)
                        val series = mutableListOf<RemovedItem>()
                        removed.keys.chunked(50).forEach { ids ->
                            val results =
                                api.itemsApi
                                    .getItems(
                                        userId = user.id,
                                        ids = ids,
                                    ).toBaseItems(api, false)
                            results.forEach {
                                val imageUrl = imageUrlService.getItemImageUrl(it, ImageType.PRIMARY)
                                series.add(RemovedItem(it, imageUrl, removed[it.id]!!))
                            }
                        }
                        _state.update { it.copy(loading = DataLoadingState.Success(series)) }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error fetching removed series")
                        _state.update { it.copy(loading = DataLoadingState.Error(ex)) }
                    }
                }
            }
        }

        fun remove(item: RemovedItem) {
            serverRepository.currentUser?.let { user ->
                viewModelScope.launchDefault {
                    mutex.withLock {
                        _state.update { it.copy(removedEnabled = false) }
                        try {
                            latestNextUpService.allowSeriesRemovedFromNextUp(user.id, item.series.id)
                            val newItems =
                                (_state.value.loading as? DataLoadingState.Success<List<RemovedItem>>)
                                    ?.data
                                    ?.toMutableList()
                                    ?.apply {
                                        removeIf { it.series.id == item.series.id }
                                    }
                            val loading =
                                if (newItems != null) {
                                    DataLoadingState.Success(newItems)
                                } else {
                                    DataLoadingState.Error("Error occurred")
                                }
                            _state.update {
                                it.copy(
                                    loading = loading,
                                    removedEnabled = true,
                                )
                            }
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error removing %s from removed next up", item.series.id)
                        } finally {
                            _state.update { it.copy(removedEnabled = true) }
                        }
                    }
                }
            }
        }
    }

data class RemovedItem(
    val series: BaseItem,
    val imageUrl: String?,
    val datetime: LocalDateTime,
)

data class RemovedNextUpState(
    val loading: DataLoadingState<List<RemovedItem>> = DataLoadingState.Pending,
    val removedEnabled: Boolean = true,
)
