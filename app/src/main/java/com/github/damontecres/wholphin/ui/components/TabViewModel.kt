package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.RememberedTabService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.SortedMap

/*
 * The tabbed page's ViewModel, moved out of TabbedPage.kt for the no-Compose UI (2026-11-22). Unchanged.
 */

@HiltViewModel(assistedFactory = TabViewModel.Factory::class)
class TabViewModel
    @AssistedInject
    constructor(
        val userPreferencesService: UserPreferencesService,
        private val rememberedTabService: RememberedTabService,
        private val backdropService: BackdropService,
        @param:Assisted private val itemId: String,
        @param:Assisted private val tabCount: Int,
        @param:Assisted private val initialTab: Int?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                itemId: String,
                tabCount: Int,
                initialTab: Int?,
            ): TabViewModel
        }

        private val _state = MutableStateFlow<Int>(UNSET)
        val state: StateFlow<Int> = _state

        val isShowClock =
            userPreferencesService.flow.map { it.appPreferences.interfacePreferences.showClock }

        init {
            viewModelScope.launchIO {
                val startingTab =
                    initialTab?.coerceIn(0..<tabCount)
                        ?: if (shouldRememberTabs()) {
                            Timber.v("Getting remembered tab for %s", itemId)
                            try {
                                rememberedTabService
                                    .getRememberedTab(itemId)
                                    ?.coerceIn(0..<tabCount)
                            } catch (ex: Exception) {
                                Timber.e(ex, "Error getting tab for %s", itemId)
                                null
                            } ?: 0
                        } else {
                            0
                        }
                _state.value = startingTab
            }
        }

        private suspend fun shouldRememberTabs(): Boolean =
            userPreferencesService
                .getCurrent()
                .appPreferences.interfacePreferences.rememberSelectedTab

        fun updateSelectedTabIndex(tabIndex: Int) {
            _state.value = tabIndex
            viewModelScope.launchIO {
                backdropService.clearBackdrop()
                if (shouldRememberTabs()) {
                    Timber.v("Saving remembered tab for %s: %s", itemId, tabIndex)
                    rememberedTabService.saveRememberedTab(itemId, tabIndex)
                }
            }
        }
    }

const val UNSET = -1234
