package com.github.damontecres.wholphin.ui.setup

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.theme.NeonBrandRow
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
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

@Composable
fun PinnedServerContent(
    modifier: Modifier = Modifier,
    viewModel: PinnedServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.connect() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            if (isWeaselTv()) {
                NeonBrandRow(mascotSize = 40.dp, wordmarkSize = 34.sp)
            }
            if (state is LoadingState.Error) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                Text(
                    text = stringResource(R.string.pinned_server_error),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    stringRes = R.string.retry,
                    onClick = viewModel::connect,
                    modifier = Modifier.focusRequester(focusRequester),
                )
            } else {
                CircularProgress(Modifier.size(64.dp))
            }
        }
    }
}
