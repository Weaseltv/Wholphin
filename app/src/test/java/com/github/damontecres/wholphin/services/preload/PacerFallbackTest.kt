package com.github.damontecres.wholphin.services.preload

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class PacerFallbackTest {
    @Test fun `404 auth errors and malformed responses all keep the existing player path`() {
        for ((code, body) in listOf(404 to "missing", 401 to "unauthorized", 500 to "error", 200 to "broken")) {
            val count = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/weaselplex/pacer/v1/hello") { exchange ->
                assertEquals("MediaBrowser test-token", exchange.requestHeaders.getFirst("Authorization"))
                count.incrementAndGet()
                exchange.sendResponseHeaders(code, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            server.start()
            try {
                val client = PacerClient(OkHttpClient(), "http://127.0.0.1:${server.address.port}".toHttpUrl(), "MediaBrowser test-token")
                assertFalse(client.hello())
                assertEquals(1, count.get())
            } finally { server.stop(0) }
        }
    }

    @Test fun `hello has a three second total deadline with no duplicate retry`() {
        val count = AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/weaselplex/pacer/v1/hello") { exchange ->
            count.incrementAndGet()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val client = PacerClient(OkHttpClient(), "http://127.0.0.1:${server.address.port}".toHttpUrl(), "test")
            val start = System.nanoTime()
            assertFalse(client.hello())
            val elapsed = (System.nanoTime() - start) / 1e9
            assertTrue("deadline elapsed=$elapsed seconds", elapsed < 5.0)
            assertEquals(1, count.get())
        } finally { release.countDown(); server.stop(0) }
    }
}
