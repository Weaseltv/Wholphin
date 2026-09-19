package com.github.damontecres.wholphin.api.seerr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the generated coroutine client and real OkHttp calls against a local server. */
class SeerrSearchTransportTest {
    private val executor = Executors.newCachedThreadPool()
    private val requests = ConcurrentLinkedQueue<HttpExchange>()
    private val calls = ConcurrentLinkedQueue<Call>()
    private val received = CountDownLatch(1)
    private val releaseResponse = CountDownLatch(1)
    private lateinit var server: HttpServer
    private lateinit var client: SeerrApiClient

    @Volatile
    private var handler: (HttpExchange) -> Unit = { respond(it) }

    @Before
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") {
            requests.add(it)
            received.countDown()
            handler(it)
        }
        server.start()
        client =
            SeerrApiClient(
                "http://127.0.0.1:${server.address.port}/api/v1",
                "test-key",
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun callStart(call: Call) {
                                calls.add(call)
                            }
                        },
                    ).build(),
            )
    }

    @After
    fun cleanup() {
        releaseResponse.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `titles are encoded exactly once and only page one is fetched`() =
        runBlocking {
            for (title in listOf("dragon", "How to Train Your Dragon", "Fast & Furious", "Amélie + 100%")) {
                val result = client.search("  $title  ")
                assertEquals(1, result.results!!.size)
                val request = requests.remove()
                val url = "http://localhost${request.requestURI}".toHttpUrl()
                assertEquals("/api/v1/search", url.encodedPath)
                assertEquals(title, url.queryParameter("query"))
                assertEquals("1", url.queryParameter("page"))
                assertEquals("test-key", request.requestHeaders.getFirst("X-Api-Key"))
            }
            assertEquals(4, calls.size)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `search shares the authenticated session cookie`() =
        runBlocking {
            handler = {
                if (it.requestURI.path.endsWith("authenticate")) {
                    it.responseHeaders.add("Set-Cookie", "connect.sid=test-session; Path=/; HttpOnly")
                    respond(it, body = "{}")
                } else {
                    respond(it)
                }
            }
            client.quickConnectAuthenticate("test-secret")
            client.search("dragon")
            assertEquals("connect.sid=test-session", requests.last().requestHeaders.getFirst("Cookie"))
        }

    @Test
    fun `cancelling an obsolete query cancels its HTTP call and allows the next query`() =
        runBlocking {
            handler = {
                if (it.requestURI.rawQuery.contains("old")) releaseResponse.await(10, TimeUnit.SECONDS)
                respond(it)
            }
            val old = async { client.search("old") }
            // Yield to the child without blocking its coroutine dispatcher.
            withTimeout(5_000) {
                while (received.count > 0) kotlinx.coroutines.delay(10)
            }
            old.cancelAndJoin()
            assertTrue(calls.first().isCanceled())
            withTimeout(5_000) { client.search("new") }
            assertEquals(2, calls.size)
        }

    @Test
    fun `a stalled search has a 15 second total deadline and is not retried`() =
        runBlocking {
            handler = {
                releaseResponse.await(25, TimeUnit.SECONDS)
                respond(it)
            }
            val started = System.nanoTime()
            var failed = false
            try {
                withTimeout(20_000) { client.search("dragon") }
            } catch (_: IOException) {
                failed = true
            }
            assertTrue("Expected a transport timeout", failed)
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) in 14..19)
            assertEquals(TimeUnit.SECONDS.toNanos(15), calls.single().timeout().timeoutNanos())
            assertEquals(1, requests.size)
        }

    @Test
    fun `an unavailable server is not retried automatically even with retry after zero`() =
        runBlocking {
            handler = {
                it.responseHeaders.add("Retry-After", "0")
                respond(it, status = 503, body = "{}")
            }
            var failed = false
            try {
                client.search("dragon")
            } catch (_: com.github.damontecres.wholphin.api.seerr.infrastructure.ServerException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(1, requests.size)
        }

    private fun respond(
        exchange: HttpExchange,
        status: Int = 200,
        body: String = """{"page":1,"totalPages":4,"totalResults":80,"results":[{"id":1,"mediaType":"movie","title":"Dragon"}]}""",
    ) {
        exchange.use {
            it.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            it.sendResponseHeaders(status, bytes.size.toLong())
            it.responseBody.write(bytes)
        }
    }
}
