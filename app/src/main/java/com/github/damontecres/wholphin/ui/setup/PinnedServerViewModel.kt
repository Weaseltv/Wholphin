package com.github.damontecres.wholphin.ui.setup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Whether this build is locked to the server in [BuildConfig.DEFAULT_SERVER_URL]. If so, the
 * server list is never shown; [PinnedServerContent] takes its place.
 */
val isServerPinned: Boolean get() = BuildConfig.DEFAULT_SERVER_URL.isNotBlank()

/**
 * Replaces the server list for builds pinned to one server: goes straight to that server's user
 * list, adding the server first if this is a fresh install.
 */
@HiltViewModel
class PinnedServerViewModel
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
        private val serverRepository: ServerRepository,
        private val serverDao: JellyfinServerDao,
        private val navigationManager: SetupNavigationManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<LoadingState>(LoadingState.Pending)
        val state: StateFlow<LoadingState> = _state

        fun connect() {
            if (_state.value == LoadingState.Loading) return
            _state.value = LoadingState.Loading
            viewModelScope.launchIO {
                try {
                    val pinnedUrl = BuildConfig.DEFAULT_SERVER_URL
                    val pinnedHost = Uri.parse(pinnedUrl).host
                    val saved =
                        serverDao
                            .getServers()
                            .map { it.server }
                            .firstOrNull { Uri.parse(it.url).host.equals(pinnedHost, ignoreCase = true) }
                    // The user list contacts the server itself, so a saved server needs no check here
                    val server = saved ?: addPinnedServer(pinnedUrl)
                    navigationManager.navigateTo(SetupDestination.UserList(server))
                    _state.value = LoadingState.Success
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Error connecting to pinned server")
                    _state.value = LoadingState.Error(exception = ex)
                }
            }
        }

        private suspend fun addPinnedServer(url: String): JellyfinServer {
            val systemInfo =
                jellyfin
                    .createApi(
                        url,
                        httpClientOptions =
                            HttpClientOptions(
                                requestTimeout = 15.seconds,
                                connectTimeout = 15.seconds,
                                socketTimeout = 15.seconds,
                            ),
                    ).systemApi
                    .getPublicSystemInfo()
                    .content
            val id =
                systemInfo.id?.toUUIDOrNull()
                    ?: throw IllegalStateException("Server returned invalid response")
            val server =
                JellyfinServer(
                    id = id,
                    name = systemInfo.serverName,
                    url = url,
                    version = systemInfo.version,
                )
            serverRepository.addAndChangeServer(server)
            return server
        }
    }
