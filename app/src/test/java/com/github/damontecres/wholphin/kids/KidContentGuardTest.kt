package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.util.CoroutineContextApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ItemFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** The kid content rules at the SDK choke point (contract section 10) */
class KidContentGuardTest {
    /** A Jellyfin server in memory: [items] by id, answering `/Items/{itemId}` and `/Items?ids=` */
    private class FakeServer(
        val items: Map<String, String>,
        var token: String?,
    ) : ApiClient() {
        val requests = mutableListOf<Pair<String, Map<String, Any?>>>()
        var listResponse: String = """{"Items":[],"TotalRecordCount":0}"""
        var contentType = "application/json; charset=utf-8"

        override val baseUrl: String = "https://media.example"
        override val accessToken: String? get() = token
        override val clientInfo = ClientInfo("test", "1")
        override val deviceInfo = DeviceInfo("device", "TV")
        override val httpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi get() = throw UnsupportedOperationException()

        override fun update(
            baseUrl: String?,
            accessToken: String?,
            clientInfo: ClientInfo,
            deviceInfo: DeviceInfo,
        ) {
            token = accessToken
        }

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            requests.add(pathTemplate to queryParameters)
            val body =
                when (pathTemplate) {
                    "/Items/{itemId}" -> {
                        items[KidJson.normalizeId(pathParameters["itemId"].toString())] ?: throw InvalidStatusException(404)
                    }

                    "/Items" -> {
                        @Suppress("UNCHECKED_CAST")
                        val ids = queryParameters["ids"] as? List<String>
                        if (ids != null) {
                            """{"Items":[${ids.mapNotNull { items[it] }.joinToString(",")}],"TotalRecordCount":${ids.size}}"""
                        } else {
                            listResponse
                        }
                    }

                    else -> {
                        listResponse
                    }
                }
            return RawResponse(body.encodeToByteArray(), 200, mapOf("content-type" to listOf(contentType)))
        }
    }

    private fun movie(
        id: String,
        rating: String?,
        year: Int,
        genres: List<String>,
    ) = """{"Id":"$id","Name":"$id","ServerId":"s","Type":"Movie",""" +
        (rating?.let { "\"OfficialRating\":\"$it\"," } ?: "") +
        """"ProductionYear":$year,"Genres":[${genres.joinToString(",") { "\"$it\"" }}],"LockedFields":[]}"""

    private val poltergeistId = UUID.randomUUID()
    private val moonstruckId = UUID.randomUUID()
    private val episodeId = UUID.randomUUID()
    private val ginnyId = UUID.randomUUID()

    private fun key(id: UUID) = KidJson.normalizeId(id.toString())

    private val items =
        mapOf(
            key(poltergeistId) to movie(key(poltergeistId), "PG", 1982, listOf("Horror")),
            key(moonstruckId) to movie(key(moonstruckId), "PG", 1987, listOf("Comedy")),
            key(ginnyId) to
                """{"Id":"${key(ginnyId)}","Name":"Ginny & Georgia","ServerId":"s","Type":"Series","OfficialRating":"TV-MA",""" +
                """"Genres":["Drama"],"LockedFields":["OfficialRating"]}""",
            key(episodeId) to
                """{"Id":"${key(episodeId)}","Name":"Pilot","ServerId":"s","Type":"Episode","OfficialRating":"TV-14",""" +
                """"SeriesId":"${key(ginnyId)}","Genres":[],"LockedFields":[]}""",
        )

    private val kid = testUser("emma").copy(policyState = PolicyState.KID, maxParentalRating = 14)
    private val adult = testUser("parent").copy(policyState = PolicyState.ADULT)
    private val current = MutableStateFlow<CurrentUser?>(CurrentUser(testServer, kid))
    private val dao = mockk<JellyfinServerDao>()
    private val store = mockk<KidProtectionStore>()
    private val repository = mockk<ServerRepository> { every { current } returns this@KidContentGuardTest.current }
    private val guard = KidContentGuard({ repository }, dao, store)
    private val server = FakeServer(items, kid.accessToken)
    private val api = CoroutineContextApiClient(server, Dispatchers.Unconfined, guard)

    private suspend fun ApiClient.get(
        path: String,
        pathParameters: Map<String, Any?> = emptyMap(),
        query: Map<String, Any?> = emptyMap(),
    ) = request(HttpMethod.GET, path, pathParameters, query, null)

    @Test
    fun `adults go straight through`() =
        runTest {
            current.value = CurrentUser(testServer, adult)
            server.token = adult.accessToken
            assertNull(guard.policyFor(adult.accessToken))
            server.listResponse = """{"Items":[${items.getValue(key(poltergeistId))}],"TotalRecordCount":1}"""
            val response = api.get("/Items", query = mapOf("fields" to null))
            assertEquals(
                1,
                Json
                    .parseToJsonElement(response.body.decodeToString())
                    .jsonObject["Items"]!!
                    .jsonArray.size,
            )
            // The request is untouched and nothing is looked up
            assertEquals(listOf("/Items" to mapOf<String, Any?>("fields" to null)), server.requests)
            assertNull(KidContentGuard.removedBy(response.headers))
            assertTrue(guard.isPlaybackAllowed(api, poltergeistId))
        }

    @Test
    fun `policy comes from the profile the credentials belong to`() =
        runTest {
            assertEquals(2, guard.policyFor(kid.accessToken)?.maxLevel)
            val teen = testUser("teen").copy(policyState = PolicyState.KID, maxParentalRating = 17)
            every { dao.getUserByAccessToken(teen.accessToken!!) } returns teen
            assertEquals(3, guard.policyFor(teen.accessToken)?.maxLevel)
            every { dao.getUserByAccessToken(adult.accessToken!!) } returns adult
            assertNull(guard.policyFor(adult.accessToken))
            // Unknown policy: filtered, no title shown
            val unknown = testUser("who").copy(policyState = PolicyState.UNKNOWN)
            every { dao.getUserByAccessToken(unknown.accessToken!!) } returns unknown
            assertEquals(KidContentGuard.Policy("${unknown.serverId}:${unknown.id}", null), guard.policyFor(unknown.accessToken))
            // Credentials no saved profile has: filtered as unknown on a protected TV only
            every { dao.getUserByAccessToken("stranger") } returns null
            coEvery { store.isProtected() } returns true
            assertNull(guard.policyFor("stranger")!!.maxLevel)
            coEvery { store.isProtected() } returns false
            assertNull(guard.policyFor("stranger"))
            assertNull(guard.policyFor(null))
        }

    @Test
    fun `kid lists ask for the rules' fields and lose hidden titles`() =
        runTest {
            server.listResponse =
                """{"Items":[${items.getValue(key(poltergeistId))},${items.getValue(key(moonstruckId))}],"TotalRecordCount":40}"""
            val response = api.get("/Items", query = mapOf("fields" to listOf(ItemFields.OVERVIEW), "limit" to 2))
            val root = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
            assertEquals(listOf(key(moonstruckId)), root["Items"]!!.jsonArray.map { it.jsonObject["Id"]!!.jsonPrimitive.content })
            assertEquals(39, root["TotalRecordCount"]!!.jsonPrimitive.int)
            assertEquals(1, KidContentGuard.removedBy(response.headers))
            assertEquals(listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.SETTINGS), server.requests.single().second["fields"])
            assertEquals(2, server.requests.single().second["limit"])
        }

    @Test
    fun `a hidden item is a 404`() =
        runTest {
            try {
                api.get("/Items/{itemId}", mapOf("itemId" to poltergeistId))
                fail("Expected a 404")
            } catch (ex: InvalidStatusException) {
                assertEquals(404, ex.status)
            }
            val shown = api.get("/Items/{itemId}", mapOf("itemId" to moonstruckId))
            assertEquals(200, shown.status)
        }

    @Test
    fun `item-scoped paths of hidden titles are refused before forwarding`() =
        runTest {
            for (path in listOf("/Items/{itemId}/Similar", "/Items/{itemId}/ThemeSongs", "/UserFavoriteItems/{itemId}")) {
                server.requests.clear()
                try {
                    api.get(path, mapOf("itemId" to episodeId))
                    fail("Expected a 404 for $path")
                } catch (ex: InvalidStatusException) {
                    assertEquals(404, ex.status)
                }
                assertFalse(server.requests.any { it.first == path })
            }
            try {
                api.get("/Shows/{seriesId}/Episodes", mapOf("seriesId" to ginnyId))
                fail("Expected a 404")
            } catch (ex: InvalidStatusException) {
                assertEquals(404, ex.status)
            }
            api.get("/Items/{itemId}/Similar", mapOf("itemId" to moonstruckId))
        }

    @Test
    fun `playback is always checked fresh`() =
        runTest {
            assertTrue(guard.isPlaybackAllowed(api, moonstruckId))
            assertTrue(guard.isPlaybackAllowed(api, moonstruckId))
            assertEquals(2, server.requests.count { it.first == "/Items/{itemId}" })
            assertFalse(guard.isPlaybackAllowed(api, poltergeistId))
            // An episode of a hidden series, and an item the server doesn't return
            assertFalse(guard.isPlaybackAllowed(api, episodeId))
            assertFalse(guard.isPlaybackAllowed(api, UUID.randomUUID()))
            server.requests.clear()
            try {
                api.request(HttpMethod.POST, "/Items/{itemId}/PlaybackInfo", mapOf("itemId" to episodeId), emptyMap(), null)
                fail("Expected a 404")
            } catch (ex: InvalidStatusException) {
                assertEquals(404, ex.status)
            }
            assertFalse(server.requests.any { it.first == "/Items/{itemId}/PlaybackInfo" })
        }

    @Test
    fun `lookups are cached per user`() =
        runTest {
            api.get("/Items/{itemId}/Similar", mapOf("itemId" to moonstruckId))
            api.get("/Items/{itemId}/Similar", mapOf("itemId" to moonstruckId))
            assertEquals(1, server.requests.count { it.first == "/Items/{itemId}" })
            // Another user starts over
            val other = testUser("zoe").copy(policyState = PolicyState.KID, maxParentalRating = 10)
            current.value = CurrentUser(testServer, other)
            server.token = other.accessToken
            api.get("/Items/{itemId}/Similar", mapOf("itemId" to moonstruckId))
            assertEquals(2, server.requests.count { it.first == "/Items/{itemId}" })
        }

    @Test
    fun `an unreadable item response is an error, never the unfiltered body`() =
        runTest {
            server.listResponse = """{"Items":[{"Id":"x","Type":"Movie" """
            try {
                api.get("/Items")
                fail("Expected an error")
            } catch (ex: ApiClientException) {
                assertTrue(ex !is InvalidStatusException)
            }
        }

    @Test
    fun `non-JSON bodies pass through`() =
        runTest {
            server.contentType = "text/vtt"
            server.listResponse = "WEBVTT\n\n00:00.000 --> 00:01.000\nHello"
            val response = api.get("/Videos/{itemId}/Subtitles", mapOf("itemId" to moonstruckId))
            assertEquals(server.listResponse, response.body.decodeToString())
        }

    @Test
    fun `visible ids keep only what the rules show`() =
        runTest {
            val visible = guard.visibleIds(api, listOf(poltergeistId, moonstruckId, episodeId, UUID.randomUUID()))
            assertEquals(setOf(moonstruckId), visible)
        }

    @Test
    fun `the horror genre leaves the filter lists`() =
        runTest {
            server.listResponse = """{"Genres":["Comedy","Horror","Comedy Horror"],"Years":[1982],"OfficialRatings":["PG"]}"""
            val response = api.get("/Items/Filters", query = mapOf("userId" to null))
            val genres = Json.parseToJsonElement(response.body.decodeToString()).jsonObject["Genres"]!!.jsonArray
            assertEquals(listOf("Comedy"), genres.map { it.jsonPrimitive.content })
        }

    @Test
    fun `request helpers`() {
        assertSame(emptyMap<String, Any?>(), KidContentGuard.withRuleFields(emptyMap()))
        assertEquals(
            mapOf("fields" to listOf(ItemFields.GENRES, ItemFields.SETTINGS)),
            KidContentGuard.withRuleFields(mapOf("fields" to null)),
        )
        val already = mapOf("fields" to listOf(ItemFields.SETTINGS, ItemFields.GENRES))
        assertSame(already, KidContentGuard.withRuleFields(already))
        assertNull(KidContentGuard.pathItemId(HttpMethod.GET, "/Items/{itemId}", mapOf("itemId" to moonstruckId)))
        assertEquals(key(moonstruckId), KidContentGuard.pathItemId(HttpMethod.POST, "/Items/{itemId}", mapOf("itemId" to moonstruckId)))
        assertEquals(key(ginnyId), KidContentGuard.pathItemId(HttpMethod.GET, "/Shows/{seriesId}/Seasons", mapOf("seriesId" to ginnyId)))
        assertNull(KidContentGuard.pathItemId(HttpMethod.GET, "/Playlists/{playlistId}/Items", mapOf("playlistId" to ginnyId)))
        assertTrue(KidContentGuard.isPlaybackPath("/Items/{itemId}/PlaybackInfo"))
        assertTrue(KidContentGuard.isPlaybackPath("/Videos/{itemId}/stream"))
        assertFalse(KidContentGuard.isPlaybackPath("/Items/{itemId}/Similar"))
        assertNull(KidContentGuard.removedBy(mapOf("content-type" to listOf("application/json"))))
        assertEquals(0, KidContentGuard.removedBy(mapOf(KidContentGuard.HEADER_FILTERED to listOf("1"))))
    }
}
