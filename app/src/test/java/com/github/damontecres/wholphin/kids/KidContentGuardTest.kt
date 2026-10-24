package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.services.KidHomeCache
import com.github.damontecres.wholphin.services.KidHomeCollections
import com.github.damontecres.wholphin.util.CoroutineContextApiClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The kid content rules at the SDK choke point (contract section 10) */
class KidContentGuardTest {
    /** A Jellyfin server in memory: [items] by id, answering `/Items/{itemId}` and `/Items?ids=` */
    private class FakeServer(
        val items: Map<String, String>,
        var token: String?,
    ) : ApiClient() {
        val requests = mutableListOf<Pair<String, Map<String, Any?>>>()

        /** Box set children by parent id, served in pages */
        var children: Map<String, List<String>> = emptyMap()

        /** Called with each request's path before it is answered */
        var onRequest: ((String) -> Unit)? = null

        /** How long each box set children request takes */
        var childrenDelayMillis = 0L
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
            onRequest?.invoke(pathTemplate)
            val parentId = queryParameters["parentId"]?.toString()
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
                        } else if (parentId != null) {
                            if (childrenDelayMillis > 0) Thread.sleep(childrenDelayMillis)
                            val all = children[parentId].orEmpty()
                            val start = queryParameters["startIndex"] as? Int ?: 0
                            val limit = queryParameters["limit"] as? Int ?: all.size
                            val page = all.drop(start).take(limit)
                            """{"Items":[${page.joinToString(",")}],"TotalRecordCount":${all.size}}"""
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
    private val epoch = MutableStateFlow(0L)
    private val repository =
        mockk<ServerRepository> {
            every { current } returns this@KidContentGuardTest.current
            every { sessionEpoch } returns epoch
        }
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

    // Review round 1

    @Test
    fun `a request and its lookups stay on the credentials they started with`() =
        runTest {
            val pinnedServers = mutableMapOf<String?, FakeServer>()
            lateinit var pinnedApi: CoroutineContextApiClient
            pinnedApi =
                CoroutineContextApiClient(server, Dispatchers.Unconfined, guard) { credentials ->
                    pinnedServers.getOrPut(credentials.accessToken) {
                        FakeServer(items, credentials.accessToken).also { pinned ->
                            if (credentials.accessToken == kid.accessToken) {
                                // The TV switches to the adult while the kid's lookup is under way
                                pinned.onRequest = { path ->
                                    if (path == "/Items/{itemId}" && current.value?.user == kid) {
                                        current.value = CurrentUser(testServer, adult)
                                        pinnedApi.update(server.baseUrl, adult.accessToken, server.clientInfo, server.deviceInfo)
                                    }
                                }
                            }
                        }
                    }
                }
            val response = pinnedApi.get("/Items/{itemId}/Similar", mapOf("itemId" to moonstruckId))
            // The lookup and the request went out with the kid's credentials, and say so
            assertEquals(
                listOf("/Items/{itemId}", "/Items/{itemId}/Similar"),
                pinnedServers.getValue(kid.accessToken).requests.map { it.first },
            )
            assertNull(pinnedServers[adult.accessToken])
            val made = guard.sessionOf(response.headers)!!
            assertEquals(KidContentGuard.credentialStamp(kid.accessToken), made.credentials)
            assertEquals("${KidContentGuard.RULES_VERSION}:2", made.rules)
            // The next request is the adult's: unfiltered, and stamped as the adult's
            val adultResponse = pinnedApi.get("/Items")
            assertEquals(1, pinnedServers.getValue(adult.accessToken).requests.size)
            val adultSession = guard.sessionOf(pinnedApi)!!
            assertEquals(adult.id, adultSession.userId)
            assertNull(adultSession.rules)
            assertTrue(adultSession.made(guard.sessionOf(adultResponse.headers)))
            assertFalse(adultSession.made(made))
        }

    private fun boxSet(id: String) = """{"Id":"$id","Name":"$id","ServerId":"s","Type":"BoxSet","IsFolder":true}"""

    private fun ids(response: RawResponse) =
        Json
            .parseToJsonElement(response.body.decodeToString())
            .jsonObject["Items"]!!
            .jsonArray
            .map { it.jsonObject["Id"]!!.jsonPrimitive.content }

    @Test
    fun `box sets page through their children and follow nested box sets`() =
        runTest {
            val hidden = (0 until 150).map { movie("horror$it", "PG", 1982, listOf("Horror")) }
            val moonstruck = items.getValue(key(moonstruckId))
            server.children =
                mapOf(
                    // The visible child is on the second page
                    "paged" to hidden + moonstruck,
                    "allhidden" to hidden,
                    // A box set inside a box set
                    "outer" to listOf(boxSet("inner")),
                    "inner" to listOf(moonstruck),
                    // A cycle with nothing else in it
                    "a" to listOf(boxSet("b")),
                    "b" to listOf(boxSet("a")),
                    // Deeper than the limit
                    "d1" to listOf(boxSet("d2")),
                    "d2" to listOf(boxSet("d3")),
                    "d3" to listOf(boxSet("d4")),
                    "d4" to listOf(boxSet("d5")),
                    "d5" to listOf(moonstruck),
                )
            server.listResponse =
                """{"Items":[${listOf("paged", "allhidden", "outer", "a", "d1").joinToString(",") { boxSet(it) }}],"TotalRecordCount":5}"""
            assertEquals(listOf("paged", "outer"), ids(api.get("/Items", query = mapOf("fields" to null))))
        }

    /** The kids Home's own collection lookup, as [KidHomeCollections.fetch] sends it */
    private val kidsHomeQuery =
        mapOf(
            "fields" to null,
            "userId" to kid.id,
            "includeItemTypes" to listOf(BaseItemKind.BOX_SET),
            "recursive" to true,
            "tags" to listOf(KidHomeCollections.TAG),
            "sortBy" to listOf(ItemSortBy.SORT_NAME),
            "sortOrder" to listOf(SortOrder.ASCENDING),
            "ids" to null,
            "parentId" to null,
        )

    private fun probes() = server.requests.count { it.second["parentId"] != null }

    @Test
    fun `the kids Home collection lookup skips the box set probe, every other box set list keeps it`() =
        runTest {
            server.children = mapOf("empty" to emptyList(), "full" to listOf(items.getValue(key(moonstruckId))))
            server.listResponse = """{"Items":[${boxSet("empty")},${boxSet("full")}],"TotalRecordCount":2}"""
            val found = withContext(KidContentGuard.KidsHomeCollections()) { ids(api.get("/Items", query = kidsHomeQuery)) }
            assertEquals(listOf("empty", "full"), found)
            assertEquals(0, probes())

            // Streaming Services, the Collections page, ...: probed as before
            val streaming = kidsHomeQuery + ("tags" to listOf("WeaselPlex Streaming"))
            assertEquals(listOf("full"), withContext(KidContentGuard.KidsHomeCollections()) { ids(api.get("/Items", query = streaming)) })
            assertTrue(probes() > 0)
        }

    @Test
    fun `the same query from anywhere else is probed`() =
        runTest {
            server.children = mapOf("empty" to emptyList())
            server.listResponse = """{"Items":[${boxSet("empty")}],"TotalRecordCount":1}"""
            assertEquals(emptyList<String>(), ids(api.get("/Items", query = kidsHomeQuery)))
            assertTrue(probes() > 0)
        }

    @Test
    fun `extra filters on the kids Home lookup mean it is probed`() =
        runTest {
            server.children = mapOf("empty" to emptyList())
            server.listResponse = """{"Items":[${boxSet("empty")}],"TotalRecordCount":1}"""
            // (ids and parentId are refused by shape too: see the next test; this server answers those differently)
            for (extra in listOf("searchTerm" to "a", "isFavorite" to true, "studioIds" to listOf("s"), "excludeItemIds" to listOf("x"))) {
                val query = kidsHomeQuery + extra
                assertEquals(
                    extra.first,
                    emptyList<String>(),
                    withContext(KidContentGuard.KidsHomeCollections()) { ids(api.get("/Items", query = query)) },
                )
            }
            assertTrue(probes() > 0)
        }

    @Test
    fun `only exactly the kids Home collection query shape is a row source list`() {
        assertTrue(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery))
        assertTrue(KidContentGuard.isKidsHomeCollections("/Users/{userId}/Items", kidsHomeQuery))
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery + ("tags" to listOf(KidHomeCollections.TAG, "Other"))))
        assertFalse(
            KidContentGuard.isKidsHomeCollections(
                "/Items",
                kidsHomeQuery + ("includeItemTypes" to listOf(BaseItemKind.BOX_SET, BaseItemKind.MOVIE)),
            ),
        )
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery + ("recursive" to null)))
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery + ("sortBy" to listOf(ItemSortBy.RANDOM))))
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery + ("parentId" to "folder")))
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items", kidsHomeQuery + ("ids" to listOf("empty"))))
        assertFalse(KidContentGuard.isKidsHomeCollections("/Items/{itemId}", kidsHomeQuery))
    }

    @Test
    fun `a fresh check of a row of episodes looks up their series once, together`() =
        runTest {
            val ratings = items.toMutableMap()
            val seriesId = UUID.randomUUID()
            ratings[key(seriesId)] = """{"Id":"${key(seriesId)}","Name":"Bluey","ServerId":"s","Type":"Series","OfficialRating":"TV-Y",""" +
                """"Genres":["Animation"],"LockedFields":[]}"""
            val episodes = (0 until 40).map { UUID.randomUUID() }
            episodes.forEach { id ->
                ratings[key(id)] = """{"Id":"${key(id)}","Name":"Episode","ServerId":"s","Type":"Episode","OfficialRating":"TV-Y",""" +
                    """"SeriesId":"${key(seriesId)}","Genres":[],"LockedFields":[]}"""
            }
            val rows = FakeServer(ratings, kid.accessToken)
            val client = CoroutineContextApiClient(rows, Dispatchers.Unconfined, guard)
            assertEquals(episodes.toSet(), guard.visibleIds(client, episodes, fresh = true))
            // One batch for the 40 episodes, one for their one series: not one series request per episode
            assertEquals(2, rows.requests.count { it.first == "/Items" && it.second["ids"] != null })
            assertEquals(0, rows.requests.count { it.first == "/Items/{itemId}" })
        }

    @Test
    fun `the saved kids Home is checked with fresh facts, so a re-rated title never paints from it`() =
        runTest {
            val ratings = items.toMutableMap()
            val seriesId = UUID.randomUUID()
            val episode = UUID.randomUUID()
            ratings[key(seriesId)] = """{"Id":"${key(seriesId)}","Name":"Bluey","ServerId":"s","Type":"Series","OfficialRating":"TV-Y",""" +
                """"Genres":["Animation"],"LockedFields":[]}"""
            ratings[key(episode)] =
                """{"Id":"${key(episode)}","Name":"Keepy Uppy","ServerId":"s","Type":"Episode","OfficialRating":"TV-Y",""" +
                """"SeriesId":"${key(seriesId)}","Genres":[],"LockedFields":[]}"""
            val rerated = FakeServer(ratings, kid.accessToken)
            val client = CoroutineContextApiClient(rerated, Dispatchers.Unconfined, guard)
            val cache = KidHomeCache(mockk(relaxed = true), client, guard)
            val saved =
                listOf(moonstruckId, episode).map { id ->
                    BaseItem(BaseItemDto(id = id, type = if (id == episode) BaseItemKind.EPISODE else BaseItemKind.MOVIE), false)
                }
            // Seen a moment ago: the rules have their facts cached
            assertEquals(setOf(moonstruckId, episode), guard.visibleIds(client, listOf(moonstruckId, episode)))

            // Re-rated on the server since: the movie R, the episode's series TV-MA
            ratings[key(moonstruckId)] = movie(key(moonstruckId), "R", 1987, listOf("Comedy"))
            ratings[key(seriesId)] = ratings.getValue(key(seriesId)).replace("TV-Y", "TV-MA")
            // The cached facts would still show them; the saved Home's check doesn't use those
            assertEquals(setOf(moonstruckId, episode), guard.visibleIds(client, listOf(moonstruckId, episode)))
            assertEquals(emptyList<BaseItem>(), cache.visibleNow(saved))
        }

    @Test
    fun `list lookups expire after two minutes`() {
        assertEquals(120_000L, KidContentGuard.CACHE_TTL.inWholeMilliseconds)
    }

    // Review round 2

    @Test
    fun `a box set stops at its first visible child`() =
        runTest {
            val moonstruck = items.getValue(key(moonstruckId))
            server.children = mapOf("first" to listOf(moonstruck) + (0 until 50).map { boxSet("nested$it") })
            server.listResponse = """{"Items":[${boxSet("first")}],"TotalRecordCount":1}"""
            assertEquals(listOf("first"), ids(api.get("/Items", query = mapOf("fields" to null))))
            assertEquals(1, server.requests.count { it.second["parentId"] != null })
            // The verdict is cached
            api.get("/Items", query = mapOf("fields" to null))
            assertEquals(1, server.requests.count { it.second["parentId"] != null })
        }

    @Test
    fun `a box set traversal has a request budget and fails closed past it`() =
        runTest {
            val moonstruck = items.getValue(key(moonstruckId))
            // 30 empty nested box sets before the one with a visible child
            val nested = (0 until 30).map { "empty$it" }
            server.children = nested.associateWith { emptyList<String>() } + ("big" to nested.map(::boxSet) + boxSet("last")) +
                ("last" to listOf(moonstruck))
            server.listResponse = """{"Items":[${boxSet("big")}],"TotalRecordCount":1}"""
            assertEquals(emptyList<String>(), ids(api.get("/Items", query = mapOf("fields" to null))))
            assertTrue(server.requests.count { it.second["parentId"] != null } <= KidContentGuard.BOX_SET_REQUESTS)
        }

    @Test
    fun `a response that outlives its session is never returned`() =
        runTest {
            // The shared client: its credentials are set by every switch
            api.update(server.baseUrl, kid.accessToken, server.clientInfo, server.deviceInfo)
            server.listResponse = """{"Items":[${items.getValue(key(moonstruckId))}],"TotalRecordCount":1}"""
            server.onRequest = { epoch.value += 1 }
            try {
                api.get("/Items")
                fail("Expected a stale session")
            } catch (_: KidContentGuard.StaleSessionException) {
            }
            server.onRequest = null
            val response = api.get("/Items")
            assertEquals(epoch.value, guard.sessionOf(response.headers)!!.epoch)
        }

    @Test
    fun `permits and visible ids are bound to the session epoch`() =
        runTest {
            val permit = guard.playbackPermit(api, moonstruckId)
            assertTrue(guard.isCurrent(permit))
            epoch.value += 1
            assertFalse(guard.isCurrent(permit))
            // The session changes while the check is under way: no permit, nothing visible
            server.onRequest = { epoch.value += 1 }
            assertNull(guard.playbackPermit(api, moonstruckId))
            assertEquals(emptySet<UUID>(), guard.visibleIds(api, listOf(moonstruckId), fresh = true))
            server.onRequest = null
            assertEquals(setOf(moonstruckId), guard.visibleIds(api, listOf(moonstruckId)))
            // Adults get permits too, bound the same way
            current.value = CurrentUser(testServer, adult)
            server.token = adult.accessToken
            val adultPermit = guard.playbackPermit(api, poltergeistId)
            assertTrue(guard.isCurrent(adultPermit))
            epoch.value += 1
            assertFalse(guard.isCurrent(adultPermit))
        }

    // Review round 3

    @Test
    fun `a box set traversal is bounded in wall time and hides the set past it`() =
        runTest {
            guard.boxSetTime = 300.milliseconds
            val moonstruck = items.getValue(key(moonstruckId))
            // Each level takes 200 ms: the visible child, four levels down, is past the time budget
            server.children =
                mapOf(
                    "t1" to listOf(boxSet("t2")),
                    "t2" to listOf(boxSet("t3")),
                    "t3" to listOf(boxSet("t4")),
                    "t4" to listOf(moonstruck),
                )
            server.childrenDelayMillis = 200
            server.listResponse = """{"Items":[${boxSet("t1")}],"TotalRecordCount":1}"""
            assertEquals(emptyList<String>(), ids(api.get("/Items", query = mapOf("fields" to null))))
            assertTrue(server.requests.count { it.second["parentId"] != null } < 4)
            // Not cached: with time enough it is shown
            guard.boxSetTime = 10.seconds
            assertEquals(listOf("t1"), ids(api.get("/Items", query = mapOf("fields" to null))))
        }
}
