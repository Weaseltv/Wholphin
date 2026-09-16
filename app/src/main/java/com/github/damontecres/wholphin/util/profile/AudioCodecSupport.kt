package com.github.damontecres.wholphin.util.profile

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import timber.log.Timber

/**
 * Determines whether the ExoPlayer backend has any way to play a given Jellyfin audio codec.
 *
 * A codec is playable when at least one of these holds:
 * - the device exposes a MediaCodec decoder for it
 * - the Media3 ffmpeg extension is bundled, enabled, and can decode it
 * - the current audio output can pass the bitstream through (e.g. HDMI to an AV receiver)
 *
 * Codecs not listed in [gatedCodecs] are assumed playable (AAC, MP3, FLAC, Opus, PCM, ...)
 * since Android ships decoders for them.
 *
 * Advertising a codec the device cannot play makes Jellyfin copy the audio stream while
 * transcoding, which results in video with no sound.
 */
class AudioCodecSupport(
    private val hasMediaCodecDecoder: (mimeType: String) -> Boolean,
    private val ffmpegSupports: (mimeType: String) -> Boolean,
    private val passthroughSupports: (encoding: Int) -> Boolean,
) {
    private val cache = mutableMapOf<String, Boolean>()

    /**
     * Whether the given Jellyfin codec name (e.g. `eac3`) is playable
     */
    fun isSupported(codec: String): Boolean {
        val gate = gatedCodecs[codec] ?: return true
        return cache.getOrPut(codec) {
            val decoder = hasMediaCodecDecoder(gate.mimeType)
            val ffmpeg = ffmpegSupports(gate.mimeType)
            val passthrough = gate.encoding?.let(passthroughSupports) ?: false
            val supported = decoder || ffmpeg || passthrough
            Timber.i(
                "Audio codec %s: supported=%s (mediacodec=%s, ffmpeg=%s, passthrough=%s)",
                codec,
                supported,
                decoder,
                ffmpeg,
                passthrough,
            )
            supported
        }
    }

    private data class Gate(
        val mimeType: String,
        val encoding: Int?,
    )

    companion object {
        private const val FFMPEG_LIBRARY_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

        /**
         * Codecs which many Android TV devices cannot decode natively and so depend on the ffmpeg extension
         */
        @OptIn(UnstableApi::class)
        private val gatedCodecs: Map<String, Gate> =
            mapOf(
                Codec.Audio.AC3 to Gate(MimeTypes.AUDIO_AC3, C.ENCODING_AC3),
                Codec.Audio.EAC3 to Gate(MimeTypes.AUDIO_E_AC3, C.ENCODING_E_AC3),
                Codec.Audio.DCA to Gate(MimeTypes.AUDIO_DTS, C.ENCODING_DTS),
                Codec.Audio.DTS to Gate(MimeTypes.AUDIO_DTS, C.ENCODING_DTS),
                Codec.Audio.TRUEHD to Gate(MimeTypes.AUDIO_TRUEHD, C.ENCODING_DOLBY_TRUEHD),
                Codec.Audio.MLP to Gate(MimeTypes.AUDIO_TRUEHD, C.ENCODING_DOLBY_TRUEHD),
                Codec.Audio.ALAC to Gate(MimeTypes.AUDIO_ALAC, null),
            )

        /**
         * Everything is supported, e.g. for a backend with its own decoders such as MPV
         */
        val ALL = AudioCodecSupport({ true }, { true }, { true })

        /**
         * Whether the Media3 ffmpeg extension is bundled in this build and its native library loaded
         */
        val ffmpegAvailable: Boolean by lazy {
            try {
                val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
                clazz.getMethod("isAvailable").invoke(null) as Boolean
            } catch (ex: Exception) {
                Timber.w("Media3 ffmpeg extension not available: %s", ex.toString())
                false
            }
        }

        private fun ffmpegSupportsMimeType(mimeType: String): Boolean =
            if (!ffmpegAvailable) {
                false
            } else {
                try {
                    val clazz = Class.forName(FFMPEG_LIBRARY_CLASS)
                    clazz.getMethod("supportsFormat", String::class.java).invoke(null, mimeType) as Boolean
                } catch (ex: Exception) {
                    Timber.w(ex, "Error querying ffmpeg support for %s", mimeType)
                    false
                }
            }

        @OptIn(UnstableApi::class)
        private fun passthroughSupported(
            context: Context,
            encoding: Int,
        ): Boolean =
            try {
                AudioCapabilities.getCapabilities(context).supportsEncoding(encoding)
            } catch (ex: Exception) {
                Timber.w(ex, "Error querying passthrough support for encoding %s", encoding)
                false
            }

        /**
         * Create for the ExoPlayer backend on this device
         *
         * @param ffmpegEnabled whether the user has the ffmpeg extension renderer enabled
         */
        fun forExoPlayer(
            context: Context,
            mediaTest: MediaCodecCapabilitiesTest,
            ffmpegEnabled: Boolean,
        ): AudioCodecSupport =
            AudioCodecSupport(
                hasMediaCodecDecoder = { mediaTest.supportsAudioMime(it) },
                ffmpegSupports = { ffmpegEnabled && ffmpegSupportsMimeType(it) },
                passthroughSupports = { passthroughSupported(context, it) },
            )
    }
}
