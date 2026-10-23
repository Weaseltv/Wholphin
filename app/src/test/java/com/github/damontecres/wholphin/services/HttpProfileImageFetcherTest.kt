package com.github.damontecres.wholphin.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The picker's picture download (review): cancellable, capped, and 404 means no picture. Against a tiny local HTTP server
 * on a real socket, in real time, so a blocking, uncancellable read would show.
 */
class HttpProfileImageFetcherTest {
    private val server = ServerSocket(0)
    private val accepted = mutableListOf<Socket>()

    @After
    fun tearDown() {
        server.close()
        synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
    }

    /** Answers every request with [respond] (or, when it returns null, never answers) */
    private fun serve(respond: () -> ByteArray?) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(accepted) { accepted += socket }
                thread(isDaemon = true) {
                    try {
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) {
                            // Request line and headers
                        }
                        respond()?.let {
                            socket.getOutputStream().write(it)
                            socket.getOutputStream().flush()
                        }
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun response(
        status: String,
        body: ByteArray,
    ) = "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + body

    private val fetcher = HttpProfileImageFetcher(OkHttpClient()) { serverUrl, _, tag -> "$serverUrl/UserImage?tag=$tag" }
    private val url get() = "http://127.0.0.1:${server.localPort}"

    private suspend fun fetch() = fetcher.fetch(url, UUID.randomUUID(), "tag")

    @Test
    fun `a small picture is read, and a 404 is no picture`() =
        runBlocking {
            val png = ByteArray(2_000) { it.toByte() }
            val status = AtomicReference("200 OK")
            serve { status.get().let { response(it, if (it.startsWith("200")) png else ByteArray(0)) } }
            assertArrayEquals(png, fetch())
            status.set("404 Not Found")
            assertNull(fetch())
        }

    @Test
    fun `a picture larger than the cap isn't read`() =
        runBlocking {
            serve { response("200 OK", ByteArray((HttpProfileImageFetcher.MAX_BYTES + 1).toInt())) }
            assertNull(fetch())
        }

    @Test
    fun `cancelling stops a request the server never answers, straight away`() =
        runBlocking {
            serve { null }
            val request = async(Dispatchers.IO) { fetch() }
            delay(300)
            val started = System.nanoTime()
            request.cancelAndJoin()
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            // A blocking read would wait for the 5 s call timeout
            assertTrue("cancel took $tookMillis ms", tookMillis < 1_000)
        }

    @Test
    fun `cancelling stops a body the server stalls on, straight away`() =
        runBlocking {
            // Headers promise a body that never comes
            serve { "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\nabc".encodeToByteArray() }
            val request = launch(Dispatchers.IO) { fetch() }
            delay(300)
            val started = System.nanoTime()
            request.cancelAndJoin()
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took $tookMillis ms", tookMillis < 1_000)
        }
}
