package com.github.damontecres.wholphin.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Sends the app logs from an error message's "Send app logs" button (moved out of ErrorMessage.kt, no Compose) */
@HiltViewModel
class ErrorViewModel
    @Inject
    constructor(
        private val serverReportService: ServerReportService,
    ) : ViewModel() {
        fun sendLogs() {
            viewModelScope.launchIO { serverReportService.sendAppLogs() }
        }
    }
