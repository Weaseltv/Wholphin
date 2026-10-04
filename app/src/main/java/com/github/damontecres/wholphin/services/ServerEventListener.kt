package com.github.damontecres.wholphin.services

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.UserUpdatedMessage
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for messages from the server such as display messages, user configuration changes, and remote "Play On"
 * requests. Also reports this device's capabilities to the server.
 */
@ActivityScoped
class ServerEventListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val remotePlaybackService: RemotePlaybackService,
    ) : DefaultLifecycleObserver {
        private val activity = (context as AppCompatActivity)

        private var listenJob: Job? = null
        private var capabilitiesJob: Job? = null

        init {
            activity.lifecycle.addObserver(this)
            serverRepository.current.collectLatestIn(activity.lifecycleScope) {
                Timber.d("New user/server: %s", it)
                cancelJobs()
                if (it != null) {
                    init(it.server, it.user)
                }
            }
        }

        fun init(
            server: JellyfinServer?,
            user: JellyfinUser?,
        ) {
            if (server != null && user != null && api.baseUrl != null && api.accessToken != null) {
                capabilitiesJob?.cancel()
                capabilitiesJob =
                    activity.lifecycleScope.launchIO {
                        var subscribed = false
                        // Post capabilities now and again whenever the user toggles remote control
                        remotePlaybackService.enabledFlow.collect { enabled ->
                            postCapabilities(enabled)
                            if (!subscribed) {
                                subscribed = true
                                subscribeToWebSocket()
                            }
                        }
                    }
            }
        }

        private suspend fun postCapabilities(remoteControlEnabled: Boolean) {
            try {
                api.sessionApi.postFullCapabilities(
                    data = remotePlaybackService.capabilities(remoteControlEnabled),
                )
                Timber.v("Posted capabilities, remoteControlEnabled=%s", remoteControlEnabled)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Error posting capabilities")
            }
        }

        fun subscribeToWebSocket() {
            Timber.v("Subscribing to WebSocket")
            listenJob?.cancel()
            listenJob =
                activity.lifecycleScope.launchDefault {
                    try {
                        // Launch multiple listeners, but stop all if one fails
                        coroutineScope {
                            api.webSocket
                                .subscribe<GeneralCommandMessage>()
                                .onEach { message ->
                                    Timber.v(
                                        "Got GeneralCommandMessage: %s",
                                        message.data?.name,
                                    )
                                    when (message.data?.name) {
                                        GeneralCommandType.DISPLAY_MESSAGE,
                                        GeneralCommandType.SEND_STRING,
                                        -> {
                                            val header = message.data?.arguments["Header"]
                                            val text =
                                                message.data?.arguments["Text"]
                                                    ?: message.data?.arguments["String"]
                                            val toast =
                                                listOfNotNull(header, text)
                                                    .joinToString("\n")
                                            if (toast.isNotBlank()) {
                                                showToast(context, toast, Toast.LENGTH_LONG)
                                            }
                                        }

                                        else -> {
                                            Timber.v(
                                                "Ignoring GeneralCommandMessage: %s",
                                                message.data?.name,
                                            )
                                        }
                                    }
                                }.catch { ex ->
                                    Timber.e(ex, "Error in general message websocket subscription")
                                }.launchIn(this@coroutineScope)

                            api.webSocket
                                .subscribe<PlayMessage>()
                                .onEach { message ->
                                    Timber.v("Got PlayMessage: %s", message.data)
                                    message.data?.let { request ->
                                        try {
                                            remotePlaybackService.onPlayRequest(request)
                                        } catch (ex: CancellationException) {
                                            throw ex
                                        } catch (ex: Exception) {
                                            Timber.e(ex, "Error handling remote play request")
                                            showToast(
                                                context,
                                                context.getString(R.string.remote_play_failed),
                                                Toast.LENGTH_LONG,
                                            )
                                        }
                                    }
                                }.catch { ex ->
                                    Timber.e(ex, "Error in play websocket subscription")
                                }.launchIn(this@coroutineScope)

                            api.webSocket
                                .subscribe<UserUpdatedMessage>()
                                .catch { ex ->
                                    Timber.e(ex, "Error in user updated websocket subscription")
                                }.collectLatestIn(this@coroutineScope) { msg ->
                                    Timber.v("Got updated user: %s", msg.data?.id)
                                    msg.data?.let { serverRepository.updateUserDto(it) }
                                }
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error in websocket connection")
                        if (activity.lifecycleScope.isActive) {
                            subscribeToWebSocket()
                        }
                    }
                }
        }

        private fun cancelJobs() {
            listenJob?.cancel()
            listenJob = null
            capabilitiesJob?.cancel()
            capabilitiesJob = null
        }

        override fun onResume(owner: LifecycleOwner) {
            serverRepository.current.value?.let { init(it.server, it.user) }
        }

        override fun onPause(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            cancelJobs()
        }

        override fun onStop(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            cancelJobs()
        }
    }
