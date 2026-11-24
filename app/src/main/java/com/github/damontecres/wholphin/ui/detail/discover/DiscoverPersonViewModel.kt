package com.github.damontecres.wholphin.ui.detail.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.DataLoadingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A Requests person page: their credits (moved out of DiscoverPersonPage.kt) */
@HiltViewModel(assistedFactory = DiscoverPersonViewModel.Factory::class)
class DiscoverPersonViewModel
    @AssistedInject
    constructor(
        val navigationManager: NavigationManager,
        val serverRepository: ServerRepository,
        val seerrService: SeerrService,
        private val backdropService: BackdropService,
        @Assisted val item: DiscoverItem,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(item: DiscoverItem): DiscoverPersonViewModel
        }

        val credits = MutableStateFlow<DataLoadingState<List<DiscoverItem>>>(DataLoadingState.Pending)

        init {
            viewModelScope.launchIO {
                backdropService.clearBackdrop()

                val credits = seerrService.personCredits(item.id)
                this@DiscoverPersonViewModel.credits.update {
                    DataLoadingState.Success(credits)
                }
            }
        }
    }
