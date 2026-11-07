@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.test.TestTracks
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
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PreloadManagerEnabledTest {
    private val diagnostics = object : timber.log.Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { println("$tag: $message") }
    }
    @org.junit.Before fun startDiagnostics() { timber.log.Timber.plant(diagnostics) }
    @org.junit.After fun stopDiagnostics() { timber.log.Timber.uproot(diagnostics) }
    @Test(timeout = 30_000) fun `enabled factory owns one item and low-storage deletion resumes an open reader on the original network path`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ShadowStatFs.registerStats(context.filesDir.absolutePath, 2_000_000, 1_500_000, 1_500_000)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hello = AtomicInteger()
        val tagged = AtomicInteger()
        val normal = AtomicInteger()
        val data = ByteArray(1024) { (it % 127).toByte() }
        server.createContext("/ready") { e -> e.sendResponseHeaders(200, -1); e.close() }
        server.createContext("/weaselplex/pacer/v1/hello") { e ->
            hello.incrementAndGet()
            val body = """{"version":1,"reportIntervalSec":5}""".toByteArray()
            e.sendResponseHeaders(200, body.size.toLong()); e.responseBody.use { it.write(body) }
        }
        server.createContext("/weaselplex/pacer/v1/report") { e ->
            e.requestBody.readBytes(); e.sendResponseHeaders(200, -1); e.close()
        }
        server.createContext("/video") { e ->
            if (e.requestHeaders.getFirst("X-WeaselPlex-Session") != null) {
                tagged.incrementAndGet()
                assertEquals("bytes=0-1023", e.requestHeaders.getFirst("Range"))
                e.responseHeaders.add("Content-Range", "bytes 0-1023/1024")
                e.sendResponseHeaders(206, data.size.toLong())
            } else {
                normal.incrementAndGet(); e.sendResponseHeaders(200, data.size.toLong())
            }
            e.responseBody.use { it.write(data) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = mockk<ApiClient>(relaxed = true)
        every { api.baseUrl } returns base
        every { api.accessToken } returns "fake-token"
        val client = OkHttpClient()
        // Real playback has already used this client for Jellyfin metadata before hello.
        // Warm network class loading outside the deliberately strict three-second probe.
        client.newCall(okhttp3.Request.Builder().url("$base/ready").build()).execute().close()
        val manager = PreloadManager(context, client, api,
            mockk<ClientInfo> { every { name } returns "test"; every { version } returns "1" },
            mockk<DeviceInfo> { every { id } returns "id"; every { name } returns "device" })
        val source = manager.dataSources().createDataSource()
        try {
            val stale = Any(); val owner = Any()
            manager.begin(stale)
            manager.begin(owner)
            val playback = CurrentPlayback(BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)), emptyList(),
                PlayerBackend.EXO_PLAYER, PlayMethod.DIRECT_PLAY, "session", null,
                TestTracks.Builder().addVideo().buildMediaSourceInfo().copy(id = "source", size = 1024, runTimeTicks = 300_000_000))
            manager.prepare(playback, "$base/video", AppPreferences.getDefaultInstance(), 0, stale)
            assertEquals(0, hello.get())
            manager.prepare(playback, "$base/video", AppPreferences.getDefaultInstance(), 0, owner)
            assertTrue(manager.enabledFor(owner))
            manager.stop("late-old-page-release", stale)
            assertTrue(manager.enabledFor(owner))
            val folder = File(context.filesDir, "preload")
            val downloadDeadline = System.nanoTime() + 5_000_000_000
            while (!folder.walkTopDown().any { it.name.endsWith(".exo") } && System.nanoTime() < downloadDeadline) Thread.sleep(10)
            assertEquals(1, tagged.get())
            assertTrue(folder.walkTopDown().any { it.name.endsWith(".exo") })
            source.open(DataSpec(Uri.parse("$base/video")))
            // Keep this reader open while Android forces deletion of its backing store.
            context.sendBroadcast(Intent(Intent.ACTION_DEVICE_STORAGE_LOW))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val deadline = System.nanoTime() + 5_000_000_000
            while (folder.exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(folder.exists())
            assertFalse(manager.enabledFor(owner))
            val received = ByteArray(1024)
            var cursor = 0
            while (cursor < received.size) {
                val count = source.read(received, cursor, received.size - cursor)
                assertTrue(count > 0)
                cursor += count
            }
            assertArrayEquals(data, received)
            assertEquals(1, hello.get())
            assertEquals(1, tagged.get())
            assertEquals(1, normal.get())
        } finally { source.close(); manager.stop("test-finished"); server.stop(0) }
    }
}
