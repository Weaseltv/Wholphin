package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayRequest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles "Play On" requests sent to this device from other Jellyfin clients (via the server)
 *
 * The server only offers this device as a target when its capabilities say it supports media control and it has
 * an open web socket, so [capabilities] must be posted whenever the user toggles the feature.
 */
@Singleton
class RemotePlaybackService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val navigationManager: NavigationManager,
        private val setupNavigationManager: SetupNavigationManager,
        private val userPreferencesService: UserPreferencesService,
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

        /**
         * Handle a `Play` message from the server by starting playback of the requested items
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
            val destination = PlayRequestMapper.toDestination(request)
            if (destination == null) {
                Timber.w("Ignoring remote play request without items: %s", request)
                return
            }
            withContext(WholphinDispatchers.Main) {
                val inApp = setupNavigationManager.backStack.firstOrNull() is SetupDestination.AppContent
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
                    )
        }
    }
