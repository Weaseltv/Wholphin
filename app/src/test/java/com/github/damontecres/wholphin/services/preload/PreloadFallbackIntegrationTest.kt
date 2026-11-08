@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PreloadFallbackIntegrationTest {
    @Test fun `stock installs use ExoPlayer and existing preferences get pre-load enabled`() {
        val prefs = AppPreferences.getDefaultInstance()
        assertEquals(PlayerBackend.EXO_PLAYER, prefs.playbackPreferences.playerBackend)
        assertTrue(AppPreference.PreloadVideo.getter(prefs))
        assertFalse(AppPreference.PreloadVideo.getter(AppPreference.PreloadVideo.setter(prefs, false)))
    }

    @Test fun `hello 404 keeps the original network path headers retries and no heartbeat or stored video`() =
        runBlocking {
            val count = AtomicInteger()
            val reports = AtomicInteger()
            val video = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/weaselplex/pacer/v1/hello") { exchange ->
                count.incrementAndGet()
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            server.createContext("/weaselplex/pacer/v1/report") { exchange ->
                reports.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.createContext("/video") { exchange ->
                video.incrementAndGet()
                assertTrue(exchange.requestHeaders.keys.none { it.startsWith("X-WeaselPlex", true) })
                exchange.sendResponseHeaders(200, 4)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }
            server.start()
            val context = ApplicationProvider.getApplicationContext<Application>()
            org.robolectric.Shadows
                .shadowOf(context)
                .grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            val base = "http://127.0.0.1:${server.address.port}"
            val api = mockk<ApiClient>(relaxed = true)
            every { api.baseUrl } returns base
            every { api.accessToken } returns "test-token"
            val clientInfo =
                mockk<ClientInfo> {
                    every { name } returns "test"
                    every { version } returns "1"
                }
            val deviceInfo =
                mockk<DeviceInfo> {
                    every { id } returns "id"
                    every { name } returns "device"
                }
            val manager = PreloadManager(context, OkHttpClient(), api, clientInfo, deviceInfo)
            val playback =
                CurrentPlayback(
                    BaseItem(BaseItemDto(id = UUID.randomUUID(), type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE)),
                    emptyList(),
                    PlayerBackend.EXO_PLAYER,
                    PlayMethod.DIRECT_PLAY,
                    "session",
                    null,
                    com.github.damontecres.wholphin.test.TestTracks.Builder().addVideo().buildMediaSourceInfo().copy(
                        id = "source",
                        size = 1024,
                        runTimeTicks = 300_000_000,
                    ),
                )
            val prefs = AppPreferences.getDefaultInstance()
            try {
                // MPV must make no capability call, even when the preference is on.
                manager.prepare(playback.copy(backend = PlayerBackend.MPV), "$base/video", prefs, 0)
                assertEquals(0, count.get())
                manager.prepare(playback, "$base/video", prefs, 0)
                manager.prepare(playback, "$base/video", prefs, 0)
                assertEquals(1, count.get())
                val source = manager.dataSources().createDataSource()
                source.open(DataSpec(Uri.parse("$base/video")))
                try {
                    val data = ByteArray(4)
                    assertEquals(4, source.read(data, 0, 4))
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), data)
                } finally {
                    source.close()
                }
                assertEquals(1, video.get())
                assertEquals(0, reports.get())
                assertFalse(File(context.filesDir, "preload").exists())
                assertEquals(
                    DefaultLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(1),
                    manager.errorPolicy.getMinimumLoadableRetryCount(1),
                )
            } finally {
                manager.stop("test-finished")
                server.stop(0)
            }
        }
}
