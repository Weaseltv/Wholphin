package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val expected =
                listOf(
                    "Netflix",
                    "Disney+",
                    "Max",
                    "Prime Video",
                    "Paramount+",
                    "Peacock",
                    "Apple TV+",
                    "AMC+",
                    "MGM+",
                    "Starz",
                    "BritBox",
                    "Crunchyroll",
                    "Hallmark",
                    "Angel",
                    "Alpha",
                    "Zeta",
                )
            val names = expected.reversed() + "Hulu"
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
                val body = """{"Items":[$items],"TotalRecordCount":17,"StartIndex":0}""".toByteArray()
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
