package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.TrackIndex
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles "Play On" requests and remote control commands sent to this device from other Jellyfin clients (via the
 * server)
 *
 * The server only offers this device as a target when its capabilities say it supports media control and it has
 * an open web socket, so [capabilities] must be posted whenever the user toggles the feature.
 */
@Singleton
class RemotePlaybackService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
        private val setupNavigationManager: SetupNavigationManager,
        private val userPreferencesService: UserPreferencesService,
        private val activePlaybackRegistry: ActivePlaybackRegistry,
        private val volumeService: VolumeService,
    ) {
        /**
         * Whether the user allows other devices to start & control playback on this device
         */
        val enabledFlow: Flow<Boolean> =
            userPreferencesService.flow
                .map { it.appPreferences.playbackPreferences.remoteControlEnabled }
                .distinctUntilChanged()

        suspend fun isEnabled(): Boolean =
            userPreferencesService
                .getCurrent()
                .appPreferences.playbackPreferences.remoteControlEnabled

        /**
         * The capabilities to report to the server
         *
         * Only commands that the app actually handles are advertised.
         */
        fun capabilities(enabled: Boolean): ClientCapabilitiesDto =
            ClientCapabilitiesDto(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands = if (enabled) REMOTE_CONTROL_COMMANDS else BASE_COMMANDS,
                supportsMediaControl = enabled,
                supportsPersistentIdentifier = true,
            )

        private val inApp: Boolean
            get() = setupNavigationManager.backStack.firstOrNull() is SetupDestination.AppContent

        /**
         * Handle a `Play` message from the server by starting playback of the requested items, or queueing them
         * when something is already playing and the request is to play next/last
         */
        suspend fun onPlayRequest(request: PlayRequest) {
            if (!isEnabled()) {
                Timber.i("Ignoring remote play request, remote control is disabled")
                return
            }
            val current = serverRepository.current.value
            if (current == null) {
                Timber.w("Ignoring remote play request, no user is signed in")
                return
            }
            if (request.playCommand == PlayCommand.PLAY_NEXT || request.playCommand == PlayCommand.PLAY_LAST) {
                val handler = activePlaybackRegistry.active.value
                val itemIds = request.itemIds.orEmpty()
                if (handler != null && itemIds.isNotEmpty()) {
                    Timber.i("Remote request to queue %s items (%s)", itemIds.size, request.playCommand)
                    handler.enqueue(itemIds, playNext = request.playCommand == PlayCommand.PLAY_NEXT)
                    showToast(context, context.getString(R.string.remote_play_queued))
                    return
                }
                // Nothing is playing, so just play the items now
            }
            val destination = PlayRequestMapper.toDestination(request)
            if (destination == null) {
                Timber.w("Ignoring remote play request without items: %s", request)
                return
            }
            withContext(WholphinDispatchers.Main) {
                val inApp = inApp
                if (!inApp && current.user.isProtected) {
                    // Do not bypass the PIN for a protected profile
                    Timber.w("Ignoring remote play request, user is protected and not signed in")
                    showToast(context, context.getString(R.string.remote_play_locked))
                    return@withContext
                }
                Timber.i(
                    "Remote play request from user %s (%s): %s",
                    request.controllingUserId,
                    request.playCommand,
                    destination,
                )
                val last = navigationManager.backStack.lastOrNull()
                if (last is Destination.Playback || last is Destination.PlaybackList) {
                    // Already playing something, so swap it out rather than stacking players
                    navigationManager.replaceTop(destination)
                } else {
                    navigationManager.navigateTo(destination)
                }
                if (!inApp) {
                    setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                }
            }
        }

        /**
         * Handle a `GeneralCommand` message from the server
         *
         * @return whether the command was handled
         */
        suspend fun onGeneralCommand(command: GeneralCommand): Boolean {
            if (!isEnabled()) {
                Timber.i("Ignoring remote command %s, remote control is disabled", command.name)
                return false
            }
            val arguments = command.arguments
            Timber.i("Remote command %s from user %s: %s", command.name, command.controllingUserId, arguments)
            when (command.name) {
                GeneralCommandType.VOLUME_UP -> {
                    volumeService.adjust(true)
                }

                GeneralCommandType.VOLUME_DOWN -> {
                    volumeService.adjust(false)
                }

                GeneralCommandType.MUTE -> {
                    volumeService.setMuted(true)
                }

                GeneralCommandType.UNMUTE -> {
                    volumeService.setMuted(false)
                }

                GeneralCommandType.TOGGLE_MUTE -> {
                    volumeService.toggleMute()
                }

                GeneralCommandType.SET_VOLUME -> {
                    val volume = PlayRequestMapper.parseVolume(arguments)
                    if (volume != null) {
                        volumeService.setVolume(volume)
                    } else {
                        Timber.w("SetVolume without a valid volume: %s", arguments)
                        return false
                    }
                }

                GeneralCommandType.SET_AUDIO_STREAM_INDEX -> {
                    val index = PlayRequestMapper.parseIndex(arguments) ?: return false
                    val handler = activePlaybackRegistry.active.value ?: return false
                    handler.setAudioStream(index)
                }

                GeneralCommandType.SET_SUBTITLE_STREAM_INDEX -> {
                    val index = PlayRequestMapper.parseIndex(arguments) ?: return false
                    val handler = activePlaybackRegistry.active.value ?: return false
                    handler.setSubtitleStream(if (index == -1) TrackIndex.DISABLED else index)
                }

                GeneralCommandType.DISPLAY_CONTENT -> {
                    val itemId = PlayRequestMapper.parseItemId(arguments) ?: return false
                    return displayContent(itemId)
                }

                GeneralCommandType.GO_HOME -> {
                    return navigate { navigationManager.goToHome() }
                }

                GeneralCommandType.BACK -> {
                    return navigate { navigationManager.goBack() }
                }

                GeneralCommandType.GO_TO_SEARCH -> {
                    return navigate { navigationManager.navigateTo(Destination.Search()) }
                }

                GeneralCommandType.GO_TO_SETTINGS -> {
                    return navigate {
                        navigationManager.navigateTo(Destination.Settings(PreferenceScreenOption.BASIC))
                    }
                }

                else -> {
                    return false
                }
            }
            return true
        }

        private suspend fun displayContent(itemId: java.util.UUID): Boolean {
            val current = serverRepository.current.value ?: return false
            val item = BaseItem(api.userLibraryApi.getItem(itemId).content)
            return navigate {
                navigationManager.navigateTo(item.destination())
                if (!inApp) {
                    setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                }
            }
        }

        /**
         * Run a navigation action on the main thread, but only if a non-protected (or already unlocked) user is signed in
         */
        private suspend fun navigate(block: () -> Unit): Boolean =
            withContext(WholphinDispatchers.Main) {
                val current = serverRepository.current.value
                if (current == null || (!inApp && current.user.isProtected)) {
                    Timber.w("Ignoring remote navigation, no unlocked user is signed in")
                    false
                } else {
                    block.invoke()
                    true
                }
            }

        companion object {
            /**
             * Commands handled even when remote control is disabled
             */
            val BASE_COMMANDS =
                listOf(
                    GeneralCommandType.DISPLAY_MESSAGE,
                    GeneralCommandType.SEND_STRING,
                )

            /**
             * Commands handled when remote control is enabled
             */
            val REMOTE_CONTROL_COMMANDS =
                BASE_COMMANDS +
                    listOf(
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.DISPLAY_CONTENT,
                        GeneralCommandType.GO_HOME,
                        GeneralCommandType.GO_TO_SEARCH,
                        GeneralCommandType.GO_TO_SETTINGS,
                        GeneralCommandType.BACK,
                    )
        }
    }
