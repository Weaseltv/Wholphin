package com.github.damontecres.wholphin.util.profile

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCodecSupportTest {
    private fun support(
        mediaCodec: Set<String> = emptySet(),
        ffmpeg: Set<String> = emptySet(),
        passthrough: Set<Int> = emptySet(),
    ) = AudioCodecSupport(
        hasMediaCodecDecoder = { it in mediaCodec },
        ffmpegSupports = { it in ffmpeg },
        passthroughSupports = { it in passthrough },
    )

    @Test
    fun `ungated codecs are always supported`() {
        val support = support()
        assertTrue(support.isSupported(Codec.Audio.AAC))
        assertTrue(support.isSupported(Codec.Audio.MP3))
        assertTrue(support.isSupported(Codec.Audio.FLAC))
        assertTrue(support.isSupported(Codec.Audio.OPUS))
    }

    @Test
    fun `dolby and dts codecs need a decode path`() {
        val support = support()
        assertFalse(support.isSupported(Codec.Audio.AC3))
        assertFalse(support.isSupported(Codec.Audio.EAC3))
        assertFalse(support.isSupported(Codec.Audio.DTS))
        assertFalse(support.isSupported(Codec.Audio.DCA))
        assertFalse(support.isSupported(Codec.Audio.TRUEHD))
        assertFalse(support.isSupported(Codec.Audio.ALAC))
    }

    @Test
    fun `mediacodec decoder enables codec`() {
        val support = support(mediaCodec = setOf(MimeTypes.AUDIO_E_AC3))
        assertTrue(support.isSupported(Codec.Audio.EAC3))
        assertFalse(support.isSupported(Codec.Audio.AC3))
    }

    @Test
    fun `ffmpeg extension enables codec`() {
        val support = support(ffmpeg = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS))
        assertTrue(support.isSupported(Codec.Audio.AC3))
        assertTrue(support.isSupported(Codec.Audio.EAC3))
        assertTrue(support.isSupported(Codec.Audio.DTS))
        assertFalse(support.isSupported(Codec.Audio.TRUEHD))
    }

    @Test
    fun `passthrough enables codec`() {
        val support = support(passthrough = setOf(C.ENCODING_E_AC3))
        assertTrue(support.isSupported(Codec.Audio.EAC3))
        assertFalse(support.isSupported(Codec.Audio.AC3))
    }

    @Test
    fun `device profile drops unsupported audio codecs`() {
        val support = support(ffmpeg = setOf(MimeTypes.AUDIO_AC3))
        val allowed = supportedAudioCodecs.filter { support.isSupported(it) }
        assertTrue(Codec.Audio.AC3 in allowed)
        assertFalse(Codec.Audio.EAC3 in allowed)
        assertTrue(Codec.Audio.AAC in allowed)
        assertEquals(supportedAudioCodecs.size - 6, allowed.size)
    }
}
