package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the active video playback so that singletons (eg remote control handling) can reach it.
 *
 * The playback view model is scoped to its navigation entry, so it registers itself here while it owns a player.
 * Transport controls (pause, seek, stop, next, previous) are handled by the view model's own `Playstate`
 * subscription; this covers the commands that need more than the player.
 */
@Singleton
class ActivePlaybackRegistry
    @Inject
    constructor() {
        interface Handler {
            /**
             * Switch to the audio stream with the given index in the current media source
             */
            suspend fun setAudioStream(index: Int)

            /**
             * Switch to the subtitle stream with the given index in the current media source, or a
             * [com.github.damontecres.wholphin.data.model.TrackIndex] constant
             */
            suspend fun setSubtitleStream(index: Int)

            /**
             * Add items to the queue, either right after the current item or at the end
             */
            suspend fun enqueue(
                itemIds: List<UUID>,
                playNext: Boolean,
            )
        }

        private val _active = MutableStateFlow<Handler?>(null)

        /**
         * The handler for the active playback, or null if nothing is playing
         */
        val active: StateFlow<Handler?> = _active

        fun register(handler: Handler) {
            _active.value = handler
        }

        /**
         * Unregister the handler, if it is still the active one
         */
        fun unregister(handler: Handler) {
            _active.compareAndSet(handler, null)
        }
    }
