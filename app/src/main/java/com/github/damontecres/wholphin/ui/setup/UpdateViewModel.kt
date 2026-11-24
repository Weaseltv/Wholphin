package com.github.damontecres.wholphin.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.DownloadCallback
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

/*
 * The app update check, download and install state, Compose-free (moved unchanged from InstallUpdatePage.kt,
 * 2026-11-22). Used by Settings, the update page and the update prompt.
 */

/** Checks for, downloads and installs app updates */
@HiltViewModel
class UpdateViewModel
    @Inject
    constructor(
        private val userPreferencesService: UserPreferencesService,
        val updater: UpdateChecker,
        val navigationManager: NavigationManager,
    ) : ViewModel(),
        DownloadCallback {
        private val _state = MutableStateFlow(InstallUpdateState())
        val state: StateFlow<InstallUpdateState> = _state

        private val _bytesDownloaded = MutableStateFlow(0L)
        val bytesDownloaded: StateFlow<Long> = _bytesDownloaded

        val currentVersion = updater.getInstalledVersion()

        fun init() {
            _state.update { it.copy(loading = LoadingState.Loading) }
            viewModelScope.launchIO {
                val updateUrl = userPreferencesService.getCurrent().appPreferences.updateUrl
                try {
                    val release = updater.getLatestRelease(updateUrl)
                    _state.update {
                        it.copy(
                            loading = LoadingState.Success,
                            release = release,
                            contentLength = -1L,
                        )
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching release from %s", updateUrl)
                    _state.update { it.copy(loading = LoadingState.Error(ex)) }
                }
            }
        }

        private var downloadJob: Job? = null

        fun installRelease(release: Release) {
            // Ask for permission BEFORE downloading. Granting it restarts the app, so
            // doing this first means the restart costs nothing instead of stranding the
            // user on a spinner owned by a killed process.
            // Already downloading, e.g. both the page opening and returning to it asked
            if (downloadJob?.isActive == true) return
            if (!updater.canInstallPackages()) {
                Timber.i("Install permission not granted yet; sending user to settings first")
                _state.update { it.copy(needsInstallPermission = true) }
                // Remembered on disk, so the install carries on even if allowing it restarts the app
                updater.markPendingInstall()
                updater.requestInstallPermission()
                return
            }
            updater.clearPendingInstall()
            _state.update { it.copy(needsInstallPermission = false) }
            downloadJob =
                viewModelScope.launchIO {
                    try {
                        _bytesDownloaded.value = 0L
                        _state.update { it.copy(downloading = true) }
                        updater.installRelease(release, this@UpdateViewModel)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error downloading")
                        _state.update { it.copy(loading = LoadingState.Error(ex)) }
                    } finally {
                        _state.update { it.copy(downloading = false) }
                    }
                }
        }

        fun cancelDownload() {
            viewModelScope.launchIO {
                downloadJob?.cancel()
                _state.update {
                    it.copy(
                        downloading = false,
                        contentLength = -1L,
                    )
                }
            }
        }

        override fun contentLength(contentLength: Long) {
            _state.update { it.copy(contentLength = contentLength) }
        }

        override fun bytesDownloaded(bytes: Long) {
            _bytesDownloaded.value = bytes
        }
    }

data class InstallUpdateState(
    val loading: LoadingState = LoadingState.Pending,
    val downloading: Boolean = false,
    /** True once we have bounced the user to the install-permission screen. */
    val needsInstallPermission: Boolean = false,
    val release: Release? = null,
    val contentLength: Long = -1L,
)
