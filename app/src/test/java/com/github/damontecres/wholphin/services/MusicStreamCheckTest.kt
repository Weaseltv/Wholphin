package com.github.damontecres.wholphin.services

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Kid content rules (review round 2): every music stream is checked by the item id in its URL */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MusicStreamCheckTest {
    @Test
    fun `the item id comes from the audio stream URL`() {
        val id = UUID.randomUUID()
        val dashless = id.toString().replace("-", "")
        assertEquals(id, audioItemId(Uri.parse("https://media.example/Audio/$dashless/universal?container=mp3")))
        assertEquals(id, audioItemId(Uri.parse("https://media.example/jellyfin/Audio/$id/stream")))
        assertNull(audioItemId(Uri.parse("https://media.example/Videos/$dashless/stream")))
        assertNull(audioItemId(Uri.parse("https://media.example/Audio/")))
    }
}
