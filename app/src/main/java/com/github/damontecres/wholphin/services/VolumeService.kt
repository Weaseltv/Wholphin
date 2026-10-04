package com.github.damontecres.wholphin.services

import android.content.Context
import android.media.AudioManager
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The device's media volume & mute state
 */
data class VolumeState(
    /**
     * 0-100
     */
    val volumeLevel: Int,
    val isMuted: Boolean,
)

/**
 * Controls the device's media volume, for remote control commands and for reporting playback state to the server
 *
 * Fixed-volume devices (eg boxes passing audio through HDMI) ignore the media stream volume, so on those the
 * current video player's gain is adjusted instead.
 */
@Singleton
class VolumeService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val playerFactory: PlayerFactory,
    ) {
        private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        private val stream = AudioManager.STREAM_MUSIC

        /**
         * Whether the device's media volume cannot be changed (so the player's gain is used instead)
         */
        val isFixed: Boolean get() = audioManager.isVolumeFixed

        @Volatile
        private var softMuted = false

        @Volatile
        private var softVolumeBeforeMute = 1f

        suspend fun state(): VolumeState =
            withContext(WholphinDispatchers.Main) {
                if (isFixed) {
                    val player = playerFactory.currentPlayer?.takeIf { !it.isReleased }
                    val volume = if (softMuted) softVolumeBeforeMute else (player?.volume ?: 1f)
                    VolumeState((volume * 100).roundToInt().coerceIn(0, 100), softMuted)
                } else {
                    VolumeState(systemVolumePercent(), audioManager.isStreamMute(stream))
                }
            }

        /**
         * Set the volume as a percentage (0-100)
         */
        suspend fun setVolume(percent: Int) =
            withContext(WholphinDispatchers.Main) {
                val clamped = percent.coerceIn(0, 100)
                Timber.d("setVolume %s%% (fixed=%s)", clamped, isFixed)
                if (isFixed) {
                    softMuted = false
                    setPlayerVolume(clamped / 100f)
                } else {
                    val max = audioManager.getStreamMaxVolume(stream)
                    val index = (clamped / 100f * max).roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
                }
            }

        /**
         * Raise or lower the volume by one step
         */
        suspend fun adjust(up: Boolean) =
            withContext(WholphinDispatchers.Main) {
                Timber.d("adjust volume up=%s (fixed=%s)", up, isFixed)
                if (isFixed) {
                    val current = (if (softMuted) softVolumeBeforeMute else (playerFactory.currentPlayer?.volume ?: 1f))
                    softMuted = false
                    setPlayerVolume((current + if (up) STEP else -STEP).coerceIn(0f, 1f))
                } else {
                    audioManager.adjustStreamVolume(
                        stream,
                        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
            }

        suspend fun setMuted(muted: Boolean) =
            withContext(WholphinDispatchers.Main) {
                Timber.d("setMuted %s (fixed=%s)", muted, isFixed)
                if (isFixed) {
                    val player = playerFactory.currentPlayer?.takeIf { !it.isReleased }
                    if (muted && !softMuted) {
                        softVolumeBeforeMute = player?.volume ?: 1f
                        softMuted = true
                        setPlayerVolume(0f)
                    } else if (!muted && softMuted) {
                        softMuted = false
                        setPlayerVolume(softVolumeBeforeMute)
                    }
                } else {
                    audioManager.adjustStreamVolume(
                        stream,
                        if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
            }

        suspend fun toggleMute() = setMuted(!state().isMuted)

        private fun systemVolumePercent(): Int {
            val max = audioManager.getStreamMaxVolume(stream)
            if (max <= 0) return 100
            return (audioManager.getStreamVolume(stream) * 100f / max).roundToInt().coerceIn(0, 100)
        }

        private fun setPlayerVolume(volume: Float) {
            val player = playerFactory.currentPlayer?.takeIf { !it.isReleased }
            if (player != null) {
                player.volume = volume
            } else {
                Timber.v("No active player to set volume on")
            }
        }

        companion object {
            private const val STEP = 0.1f
        }
    }
