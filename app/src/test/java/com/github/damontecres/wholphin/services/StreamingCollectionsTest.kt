package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.services.StreamingCollections.JustWatchLists
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID

class StreamingCollectionsTest {
    private fun settings(vararg configs: HomeRowConfig) =
        HomePageResolvedSettings(
            configs.mapIndexed { index, config ->
                HomeRowConfigDisplay(index, StringStringProvider("Row $index"), config)
            },
        )

    @Test
    fun `Streaming follows separate continue watching and next up without changing other rows`() {
        val recent = HomeRowConfig.RecentlyAdded(UUID.randomUUID())
        val original = settings(HomeRowConfig.ContinueWatching(), HomeRowConfig.NextUp(), recent)
        val result = StreamingCollections.withStreamingRow(original)
        assertEquals(
            listOf(original.rows[0], original.rows[1], result.rows[2], original.rows[2]),
            result.rows,
        )
        assertEquals(StreamingCollections.row, result.rows[2].config)
        assertEquals(
            result.rows.size,
            result.rows
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(result, StreamingCollections.withStreamingRow(result))
    }

    @Test
    fun `Home row is titled Streaming Services, including layouts saved with the old name`() {
        assertEquals("Streaming Services", StreamingCollections.NAME)
        assertEquals(StreamingCollections.NAME, StreamingCollections.row.name)
        val saved = StreamingCollections.row.copy(name = "Streaming")
        assertEquals(StreamingCollections.NAME, StreamingCollections.title(saved))
        val other = StreamingCollections.row.copy(name = "Kids", getItems = StreamingCollections.row.getItems.copy(tags = listOf("Kids")))
        assertEquals("Kids", StreamingCollections.title(other))
    }

    @Test
    fun `Streaming follows the combined row or starts a layout with no resume rows`() {
        val combined = HomeRowConfig.ContinueWatchingCombined()
        val recent = HomeRowConfig.RecentlyAdded(UUID.randomUUID())
        assertEquals(
            listOf(combined, StreamingCollections.row, recent),
            StreamingCollections.withStreamingRow(settings(combined, recent)).rows.map { it.config },
        )
        assertEquals(
            listOf(StreamingCollections.row, recent),
            StreamingCollections.withStreamingRow(settings(recent)).rows.map { it.config },
        )
    }

    @Test
    fun `JustWatch codes follow the new names, and the old ones still work`() {
        assertEquals(JustWatchLists("mxx", listOf("mxx")), StreamingCollections.justWatch("HBO Max"))
        assertEquals(JustWatchLists("mxx", listOf("mxx")), StreamingCollections.justWatch("Max"))
        assertEquals(JustWatchLists("atp", listOf("atp")), StreamingCollections.justWatch("Apple TV"))
        assertEquals(JustWatchLists("atp", listOf("atp")), StreamingCollections.justWatch("Apple TV+"))
        assertEquals(JustWatchLists("nfx", listOf("nfx", "nfa")), StreamingCollections.justWatch(" netflix "))
        assertEquals(null, StreamingCollections.justWatch("Kids"))
    }

    @Test
    fun `The newly listed services have lists under their own codes`() {
        val expected =
            mapOf(
                "Tubi" to JustWatchLists("tbv", listOf("tbv")),
                "Pluto TV" to JustWatchLists("ptv", listOf("ptv")),
                "Discovery+" to JustWatchLists("dpu", listOf("dpu")),
                "Shudder" to JustWatchLists("shd", listOf("shd", "sua")),
                "Acorn TV" to JustWatchLists("act", listOf("act", "acr")),
                "Criterion Channel" to JustWatchLists("crc", listOf("crc")),
                "YouTube" to JustWatchLists("yfr", listOf("yfr", "ytr")),
                // Paramount+ Premium's rankings, kept apart from Paramount+'s own lists
                "Showtime" to JustWatchLists("sho", listOf("ppp", "ppe")),
            )
        expected.forEach { (name, lists) -> assertEquals(name, lists, StreamingCollections.justWatch(name)) }
        assertEquals(StreamingCollections.justWatch("Paramount+")?.packages, StreamingCollections.justWatch("Showtime")?.packages)
        assertEquals("ppp", StreamingCollections.justWatch("Paramount+")?.code)
        mapOf(
            "Discovery Plus" to "dpu",
            "The Criterion Channel" to "crc",
            "Pluto" to "ptv",
            "Tubi TV" to "tbv",
            "Acorn" to "act",
        ).forEach { (name, code) -> assertEquals(name, code, StreamingCollections.justWatch(name)?.code) }
        // A TV Networks collection, not the streaming service
        assertNull(StreamingCollections.justWatch("Showtime Network"))
    }

    @Test
    fun `Every service on the server's row has JustWatch lists`() {
        listOf(
            "Netflix",
            "Prime Video",
            "Disney+",
            "HBO Max",
            "Hulu",
            "Paramount+",
            "Showtime",
            "Peacock",
            "Apple TV",
            "Tubi",
            "Pluto TV",
            "Starz",
            "Crunchyroll",
            "Discovery+",
            "AMC+",
            "MGM+",
            "Hallmark",
            "BritBox",
            "Shudder",
            "Acorn TV",
            "Angel",
            "Criterion Channel",
            "YouTube",
        ).forEach { assertNotNull(it, StreamingCollections.justWatch(it)) }
    }

    private fun hex(id: UUID) = id.toString().replace("-", "")

    @Test
    fun `service_codes holds collection ID and code pairs`() {
        val netflix = UUID.randomUUID()
        val showtime = UUID.randomUUID()
        val raw = "${hex(netflix).uppercase()}=nfx, ${hex(showtime)}=sho ,broken,=x,${hex(UUID.randomUUID())}="
        assertEquals(mapOf(hex(netflix) to "nfx", hex(showtime) to "sho"), StreamingCollections.parseServiceCodes(raw))
        assertNull(StreamingCollections.parseServiceCodes(null))
        assertNull(StreamingCollections.parseServiceCodes(""))
        assertNull(StreamingCollections.parseServiceCodes(" "))
    }

    @Test
    fun `The server's mapping wins over the built-in names`() {
        val id = UUID.randomUUID()
        assertEquals(
            JustWatchLists("xyz", listOf("nfx", "nfa")),
            StreamingCollections.justWatch(id, "Netflix", StreamingCollections.parseServiceCodes("${hex(id)}=xyz")),
        )
        // A service only the server knows has lists, but no JustWatch packages to ask JustWatch with
        assertEquals(
            JustWatchLists("fbo", emptyList()),
            StreamingCollections.justWatch(id, "Fubo", StreamingCollections.parseServiceCodes("${hex(id)}=fbo")),
        )
    }

    @Test
    fun `Without service_codes the built-in names decide`() {
        val id = UUID.randomUUID()
        assertEquals(StreamingCollections.justWatch("Showtime"), StreamingCollections.justWatch(id, "Showtime", null))
        assertEquals(
            StreamingCollections.justWatch("Netflix"),
            StreamingCollections.justWatch(id, "Netflix", StreamingCollections.parseServiceCodes("")),
        )
        assertNull(StreamingCollections.justWatch(id, "Fubo", null))
    }

    @Test
    fun `With service_codes a collection without an entry has no lists`() {
        val codes = StreamingCollections.parseServiceCodes("${hex(UUID.randomUUID())}=nfx")
        assertNull(StreamingCollections.justWatch(UUID.randomUUID(), "Netflix", codes))
    }

    @Test
    fun `Collection IDs match with or without dashes, in any case`() {
        val id = UUID.randomUUID()
        listOf(id.toString(), id.toString().uppercase(), hex(id), hex(id).uppercase()).forEach { written ->
            val codes = StreamingCollections.parseServiceCodes("$written=sho")
            assertEquals(written, "sho", StreamingCollections.justWatch(id, "Showtime", codes)?.code)
        }
    }

    @Test
    fun `service_codes comes from Home's cached JustWatch prefs`() =
        runBlocking {
            val userId = UUID.randomUUID()
            val showtime = UUID.randomUUID()
            val prefs = mockk<DisplayPreferencesService>()
            coEvery { prefs.getDisplayPreferences(userId, JustWatchRowService.PREFS_ID, JustWatchRowService.PREFS_CLIENT) } returns
                mockk<DisplayPreferencesDto> {
                    every { customPrefs } returns mapOf(JustWatchRowService.SERVICE_CODES to "${hex(showtime)}=sho")
                }
            val service = JustWatchRowService(mockk(), prefs)
            assertEquals(JustWatchLists("sho", listOf("ppp", "ppe")), service.serviceLists(userId, showtime, "Showtime"))
            assertNull(service.serviceLists(userId, UUID.randomUUID(), "Netflix"))
            coVerify(exactly = 1) { prefs.getDisplayPreferences(any(), any(), any()) }
        }

    @Test
    fun `Streaming pages read the server's lists by the service's code, not its first package`() =
        runBlocking {
            val userId = UUID.randomUUID()
            val rows = mockk<JustWatchRowService>()
            coEvery { rows.serviceList(any(), any(), any()) } returns listOf(UUID.randomUUID())
            val service =
                StreamingQuickListService(
                    mockk(relaxed = true),
                    mockk(),
                    mockk(),
                    rows,
                    mockk {
                        coEvery { currentPolicy() } returns
                            null
                    },
                )
            service.prefetch(userId, StreamingCollections.justWatch("Showtime"))
            coVerify(timeout = 5_000) { rows.serviceList(userId, "sho", StreamingQuickList.POPULAR) }
            coVerify(exactly = 0) { rows.serviceList(any(), "ppp", any()) }
        }

    @Test
    fun `Only tagged BoxSets open in Streaming mode`() {
        assertTrue(
            StreamingCollections.isStreamingCollection(
                BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.BOX_SET, tags = listOf(StreamingCollections.TAG)),
            ),
        )
        assertFalse(
            StreamingCollections.isStreamingCollection(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.BOX_SET, name = "UFC PPV")),
        )
        assertFalse(
            StreamingCollections.isStreamingCollection(
                BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, tags = listOf(StreamingCollections.TAG)),
            ),
        )
    }

    @Test
    fun `SDK discovery uses member Authorization and tag and refetches rebuilt ids`() =
        runBlocking {
            val member = UUID.randomUUID()
            val memberId = member.toString().replace("-", "")
            var netflixId = UUID.randomUUID().toString().replace("-", "")
            val initialId = netflixId
            val requests = mutableListOf<Map<String, String>>()
            val headers = mutableListOf<String?>()
            // The server's sort-name order is the owner's row order; the app keeps it as is
            val expected =
                listOf(
                    "Netflix",
                    "YouTube",
                    "Prime Video",
                    "Disney+",
                    "HBO Max",
                    "Paramount+",
                    "Showtime",
                    "Peacock",
                    "Apple TV",
                    "Tubi",
                    "Pluto TV",
                    "Starz",
                    "Crunchyroll",
                    "Discovery+",
                    "AMC+",
                    "MGM+",
                    "Hallmark",
                    "BritBox",
                    "Shudder",
                    "Acorn TV",
                    "Angel",
                    "Criterion Channel",
                )
            // Hulu (sixth on the server) has no titles for this member, so it's left out
            val names = expected.take(5) + "Hulu" + expected.drop(5)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/Items") { exchange ->
                val query =
                    exchange.requestURI.rawQuery
                        .split("&")
                        .map {
                            val parts = it.split("=", limit = 2)
                            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                        }.groupBy({ it.first }, { it.second })
                        .mapValues { it.value.joinToString(",") }
                requests.add(query)
                headers.add(exchange.requestHeaders.getFirst("Authorization"))
                val items =
                    if (query["userId"]?.replace("-", "") != memberId || query["tags"] != StreamingCollections.TAG) {
                        ""
                    } else {
                        names.joinToString(",") { name ->
                            val id = if (name == "Netflix") netflixId else UUID.randomUUID().toString().replace("-", "")
                            val count = if (name == "Hulu") 0 else 1
                            """{"Id":"$id","Name":"$name","Type":"BoxSet","ChildCount":$count}"""
                        }
                    }
                val body = """{"Items":[$items],"TotalRecordCount":23,"StartIndex":0}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            val http = OkHttpClient()
            val factory = OkHttpFactory(http)
            val api =
                createJellyfin {
                    context = mockk<Context>(relaxed = true)
                    clientInfo = ClientInfo("WeaselPlex test", "1")
                    deviceInfo = DeviceInfo("test-device", "test-device")
                    apiClientFactory = factory
                    socketConnectionFactory = factory
                }.createApi("http://127.0.0.1:${server.address.port}", "test-member-token")
            try {
                val first = StreamingCollections.fetch(api, member)
                assertEquals(expected, first.map { it.name })
                assertEquals(
                    initialId,
                    first
                        .first()
                        .id
                        .toString()
                        .replace("-", ""),
                )
                netflixId = UUID.randomUUID().toString().replace("-", "")
                val second = StreamingCollections.fetch(api, member)
                assertEquals(
                    netflixId,
                    second
                        .first()
                        .id
                        .toString()
                        .replace("-", ""),
                )
                assertEquals(2, requests.size)
                requests.forEach {
                    assertEquals(memberId, it["userId"]?.replace("-", ""))
                    assertEquals("BoxSet", it["includeItemTypes"])
                    assertEquals("true", it["recursive"])
                    assertEquals(StreamingCollections.TAG, it["tags"])
                    assertTrue(it["fields"].orEmpty().contains("ChildCount"))
                    assertTrue(it["fields"].orEmpty().contains("Tags"))
                    assertEquals("SortName", it["sortBy"])
                    assertEquals("Ascending", it["sortOrder"])
                    assertFalse(it.containsKey("limit"))
                    assertFalse(it.containsKey("parentId"))
                }
                headers.forEach {
                    assertTrue(it.orEmpty().startsWith("MediaBrowser "))
                    assertTrue(it.orEmpty().contains("Token=\"test-member-token\""))
                }
            } finally {
                server.stop(0)
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
}
