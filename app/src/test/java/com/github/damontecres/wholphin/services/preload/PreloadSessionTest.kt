@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PreloadSessionTest {
    private val diagnosticMessages = Collections.synchronizedList(mutableListOf<String>())
    private val diagnostics =
        object : timber.log.Timber.Tree() {
            override fun log(
                priority: Int,
                tag: String?,
                message: String,
                t: Throwable?,
            ) {
                diagnosticMessages.add(message)
                println("$tag: $message")
            }
        }

    @org.junit.Before fun startDiagnostics() {
        timber.log.Timber.plant(diagnostics)
    }

    @org.junit.After fun stopDiagnostics() {
        timber.log.Timber.uproot(diagnostics)
    }

    private fun logged(text: String): Boolean = synchronized(diagnosticMessages) { diagnosticMessages.any { it.contains(text) } }

    private data class Video(
        val path: String,
        val range: String?,
        val headers: Map<String, String>,
    )

    private class FakePacer : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Video>())
        val reports = Collections.synchronizedList(mutableListOf<String>())
        val inFlight = AtomicInteger()
        val maximum = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var serve: (HttpExchange) -> Unit = { respond(it, ByteArray(1024)) }
        val base get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                when (exchange.requestURI.path) {
                    "/weaselplex/pacer/v1/hello" -> {
                        respond(exchange, """{"version":1,"reportIntervalSec":5}""".toByteArray())
                    }

                    "/weaselplex/pacer/v1/report" -> {
                        reports.add(exchange.requestBody.bufferedReader().readText())
                        respond(exchange, """{"ok":true}""".toByteArray())
                    }

                    else -> {
                        val count = inFlight.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, count) }
                        requests.add(
                            Video(
                                exchange.requestURI.path,
                                exchange.requestHeaders.getFirst("Range"),
                                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                            ),
                        )
                        try {
                            serve(exchange)
                        } finally {
                            inFlight.decrementAndGet()
                            exchange.close()
                        }
                    }
                }
            }
            server.start()
        }

        fun client() = PacerClient(OkHttpClient(), base.toHttpUrl(), "MediaBrowser test")

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private fun respond(
            exchange: HttpExchange,
            bytes: ByteArray,
            code: Int = 200,
        ) {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000
            while (!condition()) {
                if (System.nanoTime() >= deadline) fail("Condition did not become true")
                Thread.sleep(10)
            }
        }

        private fun read(
            source: DataSource,
            url: String,
            position: Long = 0,
            length: Long = C.LENGTH_UNSET.toLong(),
        ): ByteArray {
            source.open(
                DataSpec
                    .Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(position)
                    .setLength(length)
                    .build(),
            )
            try {
                val out = ByteArrayOutputStream()
                val block = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(block, 0, block.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(block, 0, n)
                }
                return out.toByteArray()
            } finally {
                source.close()
            }
        }
    }

    private fun directory() = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "preload-test-${UUID.randomUUID()}")

    private fun session(
        fake: FakePacer,
        store: PreloadStore,
        hls: Boolean,
        size: Long = 1024,
        stallMs: Long = 60_000,
    ) = PreloadSession(
        PreloadMedia(
            fake.base + if (hls) "/master.m3u8" else "/video",
            "session-1",
            "item-1",
            "source-1",
            hls,
            size,
            30.0,
            if (hls) 3_400_000 else PreloadPolicy.averageBitrate(size, 30.0),
        ),
        fake.client(),
        store,
        { 20_000_000_000 },
        { 30_000_000_000 },
        retryDelayMs = 20,
        stallMs = stallMs,
        watchdogIntervalMs = 20,
    )

    @Test(timeout = 30_000)
    fun `direct download uses sequential 8 MiB ranges and player uses stored bytes with no second transfer`() {
        FakePacer().use { fake ->
            assertTrue(fake.client().hello())
            val size = (PreloadPolicy.CHUNK * 2 + 34567).toInt()
            val data = ByteArray(size) { (it % 251).toByte() }
            fake.serve = { exchange ->
                val bounds =
                    exchange.requestHeaders
                        .getFirst("Range")
                        .removePrefix("bytes=")
                        .split('-')
                val start = bounds[0].toInt()
                val end = bounds[1].toInt()
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                respond(exchange, data.copyOfRange(start, end + 1), 206)
            }
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, false, size.toLong())
            session.start()
            try {
                await { store.bytes == size.toLong() }
                val source = session.factory().createDataSource()
                assertArrayEquals(data, read(source, fake.base + "/video"))
                session.update(4.0, true)
                session.seek(8.0)
                assertArrayEquals(data.copyOfRange(5000, size), read(source, fake.base + "/video", 5000))
                assertEquals(
                    listOf("bytes=0-8388607", "bytes=8388608-16777215", "bytes=16777216-${size - 1}"),
                    fake.requests.map { it.range },
                )
                assertEquals(1, fake.maximum.get())
                assertTrue(session.bufferedAhead() > 0)
                for (request in fake.requests) {
                    assertEquals("session-1", request.headers["x-weaselplex-session"])
                    assertEquals(PreloadPolicy.averageBitrate(size.toLong(), 30.0).toString(), request.headers["x-weaselplex-bitrate"])
                    assertNotNull(request.headers["x-weaselplex-buffered"])
                    assertNotNull(request.headers["x-weaselplex-kind"])
                }
            } finally {
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
            assertTrue(
                fake.reports.any {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["state"]
                        ?.jsonPrimitive
                        ?.content == "stopped"
                },
            )
        }
    }

    @Test(timeout = 30_000)
    fun `HLS never skips a failed segment and an uncached seek discards old output before jumping`() {
        FakePacer().use { fake ->
            val segment1 = AtomicInteger()
            fake.serve = { exchange ->
                when (exchange.requestURI.path) {
                    "/master.m3u8" -> {
                        respond(exchange, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3400000\nstream.m3u8\n".toByteArray())
                    }

                    "/stream.m3u8" -> {
                        respond(
                            exchange,
                            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10,\n0.ts\n#EXTINF:10,\n1.ts\n#EXTINF:10,\n2.ts\n#EXT-X-ENDLIST\n"
                                .toByteArray(),
                        )
                    }

                    "/1.ts" -> {
                        segment1.incrementAndGet()
                        respond(exchange, "unavailable".toByteArray(), 500)
                    }

                    else -> {
                        respond(
                            exchange,
                            ByteArray(2048) {
                                exchange.requestURI.path[1]
                                    .code
                                    .toByte()
                            },
                        )
                    }
                }
            }
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, true)
            session.start()
            val source = session.factory().createDataSource()
            try {
                read(source, fake.base + "/master.m3u8")
                read(source, fake.base + "/stream.m3u8")
                await { segment1.get() == 2 }
                Thread.sleep(100)
                assertFalse(fake.requests.any { it.path == "/2.ts" })
                assertTrue(store.bytes > 0)
                session.seek(25.0)
                assertArrayEquals(ByteArray(2048) { '2'.code.toByte() }, read(source, fake.base + "/2.ts"))
                assertEquals(listOf("/0.ts", "/1.ts", "/1.ts", "/2.ts"), fake.requests.filter { it.path.endsWith(".ts") }.map { it.path })
                assertTrue(store.cache.keys.none { it.startsWith("0:") })
                val seekRequest = fake.requests.last { it.path == "/2.ts" }
                assertEquals("play", seekRequest.headers["x-weaselplex-kind"])
                assertEquals("0", seekRequest.headers["x-weaselplex-buffered"])
                assertEquals(1, fake.maximum.get())
            } finally {
                session.stop("new-playback")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `slow paced responses survive a long total time when bytes keep arriving`() {
        FakePacer().use { fake ->
            fake.serve = { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 0-1023/1024")
                exchange.sendResponseHeaders(206, 1024)
                exchange.responseBody.use { output ->
                    repeat(32) {
                        output.write(ByteArray(32) { 7 })
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            }
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, false, stallMs = 1000)
            session.start()
            try {
                assertArrayEquals(ByteArray(1024) { 7 }, read(session.factory().createDataSource(), fake.base + "/video"))
                assertEquals(1, fake.requests.size)
                await { fake.reports.size >= 2 }
                val report = Json.parseToJsonElement(fake.reports.first()).jsonObject
                assertEquals(
                    setOf(
                        "playSessionId",
                        "itemId",
                        "mediaSourceId",
                        "mode",
                        "state",
                        "positionSec",
                        "bufferedAheadSec",
                        "bytesStored",
                        "complete",
                        "bitrate",
                        "stallsSinceLast",
                    ),
                    report.keys,
                )
            } finally {
                session.stop("playback-ended")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `a real byte stall retries the identical chunk once and never loops beyond that`() {
        FakePacer().use { fake ->
            fake.serve = { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 0-1023/1024")
                exchange.sendResponseHeaders(206, 1024)
                try {
                    exchange.responseBody.write(ByteArray(64))
                    exchange.responseBody.flush()
                    Thread.sleep(500)
                } finally {
                    exchange.close()
                }
            }
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, false, stallMs = 150)
            session.start()
            try {
                assertThrows(java.io.IOException::class.java) { read(session.factory().createDataSource(), fake.base + "/video") }
                Thread.sleep(100)
                assertEquals(listOf("bytes=0-1023", "bytes=0-1023"), fake.requests.map { it.range })
                // Twice-failed direct chunk: stream normally rather than wait for a playback error.
                assertTrue(session.networkFallback)
            } finally {
                session.stop("player-left")
                session.join()
            }
        }
    }

    private fun FakePacer.serveRanges(
        size: Long,
        fill: Byte,
    ) {
        serve = { exchange ->
            val range =
                exchange.requestHeaders
                    .getFirst("Range")
                    .removePrefix("bytes=")
                    .split('-')
            val start = range[0].toLong()
            val end = range[1].toLong()
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
            respond(exchange, ByteArray((end - start + 1).toInt()) { fill }, 206)
        }
    }

    // Regression, 2026-11-07 "Garage TV": the player's read-only CacheDataSource takes the hole
    // lock for a moment when it opens a read at the write frontier. The worker used to treat
    // that as "multiple writers", die, and leave playback to drain and fail.
    @Test(timeout = 30_000)
    fun `a reader briefly holding the cache hole lock never stops the writer`() {
        FakePacer().use { fake ->
            val size = 2L * PreloadPolicy.CHUNK
            fake.serveRanges(size, 5)
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, false, size)
            val url = fake.base + "/video"
            val held = store.cache.startReadWriteNonBlocking(url, 0, size)
            assertNotNull(held)
            val release =
                Thread {
                    Thread.sleep(200)
                    store.cache.releaseHoleSpan(held!!)
                }
            release.start()
            session.start()
            try {
                await { store.bytes == size }
                release.join()
                assertFalse(logged("worker stopped"))
                assertFalse(session.networkFallback)
                assertArrayEquals(ByteArray(64 * 1024) { 5 }, read(session.factory().createDataSource(), url, length = 64L * 1024))
            } finally {
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `at capacity watched data gives way and background pre-load resumes for the playing item`() {
        FakePacer().use { fake ->
            val size = 40L * 1024 * 1024
            fake.serveRanges(size, 3)
            val store = PreloadStore(directory(), 12L * 1024 * 1024)
            val url = fake.base + "/video"
            val session =
                PreloadSession(
                    PreloadMedia(
                        url,
                        "session",
                        "item",
                        "source",
                        false,
                        size,
                        1200.0,
                        PreloadPolicy.averageBitrate(size, 1200.0),
                    ),
                    fake.client(),
                    store,
                    { 20_000_000_000 },
                    { 30_000_000_000 },
                    retryDelayMs = 20,
                )
            session.start()
            try {
                await { logged("background pre-load stopped") }
                val before = fake.requests.size
                // Playback moves on (about 12 MiB = 360 s here): what was watched gives way.
                session.update(420.0, false)
                await { logged("background pre-load resumed") }
                await { fake.requests.size > before }
                await { store.available(url, 16L * 1024 * 1024) > 0 }
                assertTrue(logged("watched-gives-way-to-read-ahead"))
                assertTrue(store.bytes <= store.limit)
                assertFalse(session.networkFallback)
            } finally {
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `repeated stalls with little stored fall back to normal streaming and delete the item`() {
        FakePacer().use { fake ->
            val release = java.util.concurrent.CountDownLatch(1)
            fake.serve = { release.await() }
            val store = PreloadStore(directory(), 100_000_000)
            val session = session(fake, store, false, PreloadPolicy.CHUNK)
            session.start()
            try {
                session.rebuffer()
                session.rebuffer()
                assertFalse(session.networkFallback)
                session.rebuffer()
                assertTrue(session.networkFallback)
                assertTrue(logged("falling back to normal streaming: repeated-stalls"))
            } finally {
                release.countDown()
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
            assertTrue(
                fake.reports.any {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["state"]
                        ?.jsonPrimitive
                        ?.content == "stopped"
                },
            )
        }
    }

    @Test(timeout = 30_000)
    fun `at capacity background stops and urgent playback replaces stored data within the limit`() {
        FakePacer().use { fake ->
            val size = 24 * 1024 * 1024
            fake.serve = { exchange ->
                val bounds =
                    exchange.requestHeaders
                        .getFirst("Range")
                        .removePrefix("bytes=")
                        .split('-')
                val start = bounds[0].toInt()
                val end = bounds[1].toInt()
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                respond(exchange, ByteArray(end - start + 1) { 42 }, 206)
            }
            val store = PreloadStore(directory(), 12L * 1024 * 1024)
            val session = session(fake, store, false, size.toLong())
            session.start()
            try {
                await { store.bytes >= store.limit - PreloadPolicy.FRAGMENT }
                Thread.sleep(100)
                assertEquals(2, fake.requests.size)
                assertTrue(store.bytes <= store.limit)
                session.seek(20.0)
                session.directSeek(16L * 1024 * 1024, 20_000_000)
                val tail = read(session.factory().createDataSource(), fake.base + "/video", 16L * 1024 * 1024)
                assertEquals(8 * 1024 * 1024, tail.size)
                assertTrue(tail.all { it == 42.toByte() })
                assertTrue(store.bytes <= store.limit)
            } finally {
                session.stop("new-playback")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `metadata headroom below the payload budget pauses pre-load and retains playable cached ranges`() {
        FakePacer().use { fake ->
            val size = 24L * 1024 * 1024
            fake.serve = { exchange ->
                val range =
                    exchange.requestHeaders
                        .getFirst("Range")
                        .removePrefix("bytes=")
                        .split('-')
                val start = range[0].toLong()
                val end = range[1].toLong()
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                respond(exchange, ByteArray((end - start + 1).toInt()) { 17 }, 206)
            }
            val store = PreloadStore(directory(), 100_000_000)
            val reserve = 3_000_000_000L
            // Model a TV whose usable space runs out before the advertised payload cap,
            // including bytes consumed by metadata outside the payload cache counter.
            val physicalAllowance = 12L * 1024 * 1024
            val session =
                PreloadSession(
                    PreloadMedia(
                        fake.base + "/video",
                        "session",
                        "item",
                        "source",
                        false,
                        size,
                        1200.0,
                        PreloadPolicy.averageBitrate(size, 1200.0),
                    ),
                    fake.client(),
                    store,
                    { reserve + physicalAllowance - store.bytes - 128 * 1024 },
                    { 30_000_000_000 },
                    retryDelayMs = 20,
                )
            session.start()
            try {
                await { logged("background pre-load stopped") }
                val retained = store.bytes
                val requests = fake.requests.size
                assertTrue(retained > 1024 * 1024)
                assertTrue(retained < store.limit)
                assertTrue(store.directory.exists())
                assertFalse(session.networkFallback)
                assertArrayEquals(
                    ByteArray(1024 * 1024) { 17 },
                    read(
                        session.factory().createDataSource(),
                        fake.base + "/video",
                        length =
                            1024L * 1024,
                    ),
                )
                Thread.sleep(150)
                assertEquals(retained, store.bytes)
                assertEquals(requests, fake.requests.size)
                assertFalse(logged("delete reason=low-storage-reserve"))
            } finally {
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `physical capacity evicts data over five minutes behind before pausing without deleting the item`() {
        FakePacer().use { fake ->
            val size = 24L * 1024 * 1024
            fake.serve = { exchange ->
                val range =
                    exchange.requestHeaders
                        .getFirst("Range")
                        .removePrefix("bytes=")
                        .split('-')
                val start = range[0].toLong()
                val end = range[1].toLong()
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                respond(exchange, ByteArray((end - start + 1).toInt()) { 9 }, 206)
            }
            val store = PreloadStore(directory(), 100_000_000)
            store.write(fake.base + "/video", 0, ByteArray(1024 * 1024), 1024 * 1024)
            val session =
                PreloadSession(
                    PreloadMedia(
                        fake.base + "/video",
                        "session",
                        "item",
                        "source",
                        false,
                        size,
                        1200.0,
                        PreloadPolicy.averageBitrate(size, 1200.0),
                    ),
                    fake.client(),
                    store,
                    { 3_000_000_000L + 2L * 1024 * 1024 - store.bytes },
                    { 30_000_000_000 },
                    retryDelayMs = 20,
                )
            session.update(900.0, true)
            session.start()
            try {
                await { logged("background pre-load stopped") }
                assertTrue(logged("delete reason=more-than-five-minutes-behind"))
                assertEquals(0L, store.available(fake.base + "/video", 0))
                assertTrue(store.available(fake.base + "/video", 18L * 1024 * 1024) > 0)
                assertTrue(store.directory.exists())
                assertFalse(session.networkFallback)
            } finally {
                session.stop("player-left")
                session.join()
            }
            assertFalse(store.directory.exists())
        }
    }

    @Test(timeout = 30_000)
    fun `crossing the storage reserve cancels the writer and removes the whole stored item`() {
        FakePacer().use { fake ->
            val release = java.util.concurrent.CountDownLatch(1)
            val free =
                java.util.concurrent.atomic
                    .AtomicLong(20_000_000_000)
            val size = PreloadPolicy.CHUNK.toInt()
            fake.serve = { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 0-${size - 1}/$size")
                exchange.sendResponseHeaders(206, size.toLong())
                try {
                    exchange.responseBody.write(ByteArray(64 * 1024))
                    exchange.responseBody.flush()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    exchange.responseBody.write(ByteArray(size - 64 * 1024))
                } catch (_: java.io.IOException) {
                } finally {
                    exchange.close()
                }
            }
            val store = PreloadStore(directory(), 100_000_000)
            val session =
                PreloadSession(
                    PreloadMedia(
                        fake.base + "/video",
                        "session",
                        "item",
                        "source",
                        false,
                        size.toLong(),
                        30.0,
                        PreloadPolicy.averageBitrate(size.toLong(), 30.0),
                    ),
                    fake.client(),
                    store,
                    free::get,
                    { 30_000_000_000 },
                    retryDelayMs = 20,
                )
            session.start()
            try {
                await { store.bytes > 0 }
                free.set(0)
                release.countDown()
                await { !store.directory.exists() }
                assertTrue(session.networkFallback)
                // Late player/timer events must be harmless after the cache has been released.
                session.update(2.0, false)
                session.seek(3.0)
            } finally {
                release.countDown()
                session.stop("test-finished")
                session.join()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `startup cleanup removes crash leftovers`() {
        val folder = directory()
        folder.mkdirs()
        File(folder, "crash-leftover.video").writeBytes(ByteArray(1024))
        PreloadStore.deleteDirectory(folder, "app-start")
        assertFalse(folder.exists())
    }

    @Test(timeout = 30_000)
    fun `storage budget reserves headroom and direct estimates use the approved ten percent margin`() {
        assertEquals(4_000_000_000, PreloadPolicy.budget(20_000_000_000, 30_000_000_000))
        assertEquals(200_000_000, PreloadPolicy.budget(1_700_000_000, 8_000_000_000))
        assertEquals(0, PreloadPolicy.budget(100_000_000, 8_000_000_000))
        assertEquals(8_000_000, PreloadPolicy.averageBitrate(100_000_000, 100.0))
        assertEquals(9, PreloadPolicy.directAhead(10_000_000, 100_000_000, 100.0, 0.0))
        assertEquals(3, PreloadPolicy.directAhead(10_000_000, 100_000_000, 100.0, 97.0))
    }
}
