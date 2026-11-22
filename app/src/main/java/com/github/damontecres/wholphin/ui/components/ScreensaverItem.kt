package com.github.damontecres.wholphin.ui.components

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ScreensaverService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Feeds the in-app screensaver (no-Compose UI: moved out of the old Compose `AppScreensaver.kt`) */
@HiltViewModel
class ScreensaverViewModel
    @Inject
    constructor(
        private val screensaverService: ScreensaverService,
        val preferencesDataStore: DataStore<AppPreferences>,
    ) : ViewModel() {
        val currentItem = screensaverService.createItemFlow(viewModelScope)
    }

/** What the screensaver shows next */
sealed interface ScreensaverItem {
    data class Error(
        val exception: Exception,
    ) : ScreensaverItem

    data object Empty : ScreensaverItem

    data class CurrentItem(
        val item: BaseItem,
        val backdropUrl: String,
        val logoUrl: String?,
        val title: String,
    ) : ScreensaverItem
}
