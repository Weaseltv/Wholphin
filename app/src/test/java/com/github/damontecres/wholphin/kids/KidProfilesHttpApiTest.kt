package com.github.damontecres.wholphin.kids

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Contract sections 3 and 5 against a local server, the way SeerrSearchTransportTest does it. */
class KidProfilesHttpApiTest {
    private lateinit var server: HttpServer
    private lateinit var api: KidProfilesHttpApi
    private val requests = ConcurrentLinkedQueue<Recorded>()

    private data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String?>,
        val body: String,
    )

    @Volatile
    private var reply: (HttpExchange) -> Unit = { respond(it, 200, """{"data":{}}""") }

    @Before
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests +=
                Recorded(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    headers =
                        listOf(
                            "Authorization",
                            "Idempotency-Key",
                            "X-Request-Id",
                            "X-WeaselPlex-Client",
                            "Content-Type",
                        ).associateWith { exchange.requestHeaders.getFirst(it) },
                    body = exchange.requestBody.readBytes().decodeToString(),
                )
            reply(exchange)
        }
        server.start()
        api =
            KidProfilesHttpApi(
                okHttpClient =
                    OkHttpClient
                        .Builder()
                        .callTimeout(5, TimeUnit.SECONDS)
                        .build(),
                baseUrl = "http://127.0.0.1:${server.address.port}/",
                clientHeader = "android-tv/9.9.9",
                newRequestId = { "req-1" },
            )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun problem(
        status: Int,
        title: String,
        extra: String = "",
    ) = """{"type":"https://theweasel.tv/problems/${title.lowercase()}","title":"$title","status":$status,"detail":"x"$extra}"""

    private val kidJson =
        """{"id":"6f1c7a8e-2b5d-4c1e-9a0b-3d2e1f4a5b6c","displayName":"Emma","level":"older_kids","state":"active",
            "mediaUserId":"0123456789abcdef0123456789abcdef","mediaUserName":"Emma 2","createdAt":"2026-10-21T19:00:00.000Z","futureField":1}"""

    @Test
    fun `list sends the parent token and parses kids`() =
        runBlocking {
            reply = { respond(it, 200, """{"data":{"kids":[$kidJson],"limit":5}}""") }
            val result = api.list("parent-token")
            val ok = result as KidApiResult.Ok
            assertEquals(5, ok.value.limit)
            val kid = ok.value.kids.single()
            assertEquals("Emma", kid.displayName)
            assertEquals(KidLevel.OLDER_KIDS, kid.level)
            assertEquals("Emma 2", kid.mediaUserName)
            assertEquals("0123456789abcdef0123456789abcdef", kid.mediaUserId)
            val req = requests.single()
            assertEquals("GET", req.method)
            assertEquals("/api/v1/weaselplex/tv/kids", req.path)
            assertEquals("Bearer parent-token", req.headers["Authorization"])
            assertEquals("req-1", req.headers["X-Request-Id"])
            assertEquals("android-tv/9.9.9", req.headers["X-WeaselPlex-Client"])
            assertNull(req.headers["Idempotency-Key"])
        }

    @Test
    fun `create sends idempotency key and strict body`() =
        runBlocking {
            reply = { respond(it, 201, """{"data":{"kid":$kidJson}}""") }
            val result = api.create("parent-token", "key-0123456789abcdef", "Emma", KidLevel.KIDS)
            assertEquals("Emma", (result as KidApiResult.Ok).value.displayName)
            val req = requests.single()
            assertEquals("POST", req.method)
            assertEquals("/api/v1/weaselplex/tv/kids", req.path)
            assertEquals("key-0123456789abcdef", req.headers["Idempotency-Key"])
            assertTrue(req.headers["Content-Type"]!!.startsWith("application/json"))
            assertEquals("""{"displayName":"Emma","level":"kids"}""", req.body)
        }

    @Test
    fun `connect posts the code for the kid`() =
        runBlocking {
            reply = { respond(it, 200, """{"data":{"authorized":true,"kid":$kidJson}}""") }
            val result = api.connect("parent-token", "kid-1", "123456")
            assertTrue(result is KidApiResult.Ok)
            val req = requests.single()
            assertEquals("/api/v1/weaselplex/tv/kids/kid-1/connect", req.path)
            assertEquals("""{"code":"123456"}""", req.body)
            assertNull(req.headers["Idempotency-Key"])
        }

    @Test
    fun `every contract error code surfaces its title and status`() =
        runBlocking {
            val codes =
                listOf(
                    400 to "INVALID_REQUEST",
                    400 to "INVALID_JSON",
                    400 to "INVALID_IDEMPOTENCY_KEY",
                    400 to "QUICK_CONNECT_INVALID_CODE",
                    401 to "AUTHENTICATION_REQUIRED",
                    403 to "WEASELPLEX_ACCESS_REQUIRED",
                    403 to "KID_CALLER_FORBIDDEN",
                    404 to "WEASELPLEX_KIDS_UNAVAILABLE",
                    404 to "KID_NOT_FOUND",
                    409 to "KID_LIMIT_REACHED",
                    409 to "KID_NAME_TAKEN",
                    409 to "KID_NOT_ACTIVE",
                    409 to "IDEMPOTENCY_CONFLICT",
                    409 to "REQUEST_IN_PROGRESS",
                    503 to "KID_PROVISIONING_FAILED",
                    503 to "MEDIA_ACCESS_PROVIDER_UNAVAILABLE",
                    503 to "WEASELPLEX_UNAVAILABLE",
                    500 to "INTERNAL_ERROR",
                )
            for ((status, title) in codes) {
                reply = { respond(it, status, problem(status, title)) }
                val result = api.create("t", "key-0123456789abcdef", "Emma", KidLevel.TEENS)
                assertEquals(title, KidApiResult.Problem(status, title), result)
            }
        }

    @Test
    fun `rate limit prefers retryAfterMs from the body`() =
        runBlocking {
            reply = {
                respond(it, 429, problem(429, "RATE_LIMITED", ""","retryAfterMs":90000"""), mapOf("Retry-After" to "120"))
            }
            assertEquals(KidApiResult.Problem(429, "RATE_LIMITED", 90_000L), api.list("t"))
        }

    @Test
    fun `rate limit falls back to the Retry-After header`() =
        runBlocking {
            reply = { respond(it, 429, problem(429, "RATE_LIMITED"), mapOf("Retry-After" to "120")) }
            assertEquals(KidApiResult.Problem(429, "RATE_LIMITED", 120_000L), api.list("t"))
        }

    @Test
    fun `a 404 without a problem body means kid profiles are unavailable`() =
        runBlocking {
            reply = { respond(it, 404, "<html>not found</html>") }
            assertEquals(KidApiResult.Problem(404, "WEASELPLEX_KIDS_UNAVAILABLE"), api.list("t"))
        }

    @Test
    fun `a 502 without a problem body is a generic server error`() =
        runBlocking {
            reply = { respond(it, 502, "bad gateway") }
            assertEquals(KidApiResult.Problem(502, "INTERNAL_ERROR"), api.list("t"))
        }

    @Test
    fun `network failure is a NetworkError`() =
        runBlocking {
            server.stop(0)
            assertTrue(api.list("t") is KidApiResult.NetworkError)
        }

    @Test
    fun `malformed success body is a NetworkError`() =
        runBlocking {
            reply = { respond(it, 200, "not json") }
            assertTrue(api.list("t") is KidApiResult.NetworkError)
        }

    @Test
    fun `no website url means unavailable without a request`() =
        runBlocking {
            val noSite = KidProfilesHttpApi(OkHttpClient(), "", "android-tv/1", { "r" })
            assertEquals(KidApiResult.Problem(404, "WEASELPLEX_KIDS_UNAVAILABLE"), noSite.list("t"))
            assertTrue(requests.isEmpty())
        }
}
