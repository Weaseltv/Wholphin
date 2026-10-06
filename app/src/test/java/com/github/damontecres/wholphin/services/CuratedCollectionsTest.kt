package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
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

class CuratedCollectionsTest {
    private fun settings(vararg configs: HomeRowConfig) =
        HomePageResolvedSettings(
            configs.mapIndexed { index, config -> HomeRowConfigDisplay(index, StringStringProvider("Row $index"), config) },
        )

    @Test
    fun `Picks follows Streaming in default and saved layouts without duplicate rows or ids`() {
        val recent = HomeRowConfig.RecentlyAdded(UUID.randomUUID())
        listOf(
            settings(HomeRowConfig.ContinueWatching(), HomeRowConfig.NextUp(), recent),
            settings(HomeRowConfig.ContinueWatchingCombined(), recent),
            settings(recent),
            settings(CuratedCollections.row, recent, StreamingCollections.row),
        ).forEach { original ->
            val result = CuratedCollections.withCuratedRow(original)
            val streaming = result.rows.indexOfFirst { StreamingCollections.isStreamingRow(it.config) }
            assertEquals(CuratedCollections.row, result.rows[streaming + 1].config)
            assertEquals(1, result.rows.count { CuratedCollections.isCuratedRow(it.config) })
            assertEquals(
                result.rows.size,
                result.rows
                    .map { it.id }
                    .distinct()
                    .size,
            )
            assertEquals(result, CuratedCollections.withCuratedRow(result))
            assertEquals(
                original.rows.filterNot {
                    CuratedCollections.isCuratedRow(it.config) ||
                        StreamingCollections.isStreamingRow(it.config)
                },
                result.rows.filterNot {
                    CuratedCollections.isCuratedRow(it.config) ||
                        StreamingCollections.isStreamingRow(it.config)
                },
            )
        }
        assertEquals("WeaselPlex Picks", CuratedCollections.row.name)
        assertEquals(AspectRatio.TALL, CuratedCollections.row.viewOptions.aspectRatio)
        assertEquals(ViewOptionImageType.PRIMARY, CuratedCollections.row.viewOptions.imageType)
        assertFalse(CuratedCollections.row.viewOptions.showTitles)
    }

    @Test
    fun `Only curated tagged BoxSets use Picks mode, independent of name and id`() {
        assertTrue(
            CuratedCollections.isCuratedCollection(
                BaseItemDto(id = UUID.randomUUID(), name = "Any name", type = BaseItemKind.BOX_SET, tags = listOf(CuratedCollections.TAG)),
            ),
        )
        assertFalse(
            CuratedCollections.isCuratedCollection(BaseItemDto(id = UUID.randomUUID(), name = "Sports", type = BaseItemKind.BOX_SET)),
        )
        assertFalse(
            CuratedCollections.isCuratedCollection(
                BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.BOX_SET, tags = listOf(StreamingCollections.TAG)),
            ),
        )
        assertFalse(CuratedCollections.isCuratedRow(StreamingCollections.row))
    }

    @Test
    fun `Discovery uses Authorization and exact member query, preserves server order and refetches replaced ids`() =
        runBlocking {
            val member = UUID.randomUUID()
            val requests = mutableListOf<Map<String, String>>()
            val headers = mutableListOf<String?>()
            var seasonalId = UUID.randomUUID()
            var seasonalPresent = true
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/Items") { exchange ->
                requests.add(
                    exchange.requestURI.rawQuery
                        .split("&")
                        .map {
                            val parts = it.split("=", limit = 2)
                            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                        }.groupBy({ it.first }, { it.second })
                        .mapValues { it.value.joinToString(",") },
                )
                headers.add(exchange.requestHeaders.getFirst("Authorization"))
                val items =
                    buildList {
                        // Deliberately not alphabetical by Name; only the server knows SortName.
                        if (seasonalPresent) add("""{"Id":"$seasonalId","Name":"Spooky Season","Type":"BoxSet","ChildCount":1}""")
                        add("""{"Id":"${UUID.randomUUID()}","Name":"Zeta","Type":"BoxSet","ChildCount":3}""")
                        add("""{"Id":"${UUID.randomUUID()}","Name":"Empty","Type":"BoxSet","ChildCount":0}""")
                        add("""{"Id":"${UUID.randomUUID()}","Name":"Alpha","Type":"BoxSet","ChildCount":2}""")
                    }
                val body = """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size},"StartIndex":0}""".toByteArray()
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
                val first = CuratedCollections.fetch(api, member)
                assertEquals(listOf("Spooky Season", "Zeta", "Alpha"), first.map { it.name })
                assertEquals(seasonalId, first.first().id)
                seasonalPresent = false
                assertEquals(listOf("Zeta", "Alpha"), CuratedCollections.fetch(api, member).map { it.name })
                seasonalId = UUID.randomUUID()
                seasonalPresent = true
                assertEquals(seasonalId, CuratedCollections.fetch(api, member).first().id)
                assertEquals(3, requests.size)
                requests.forEach {
                    assertEquals(member.toString().replace("-", ""), it["userId"]?.replace("-", ""))
                    assertEquals("BoxSet", it["includeItemTypes"])
                    assertEquals("true", it["recursive"])
                    assertEquals(CuratedCollections.TAG, it["tags"])
                    assertEquals("SortName", it["sortBy"])
                    assertEquals("Ascending", it["sortOrder"])
                    assertEquals(setOf("ChildCount", "Overview"), it["fields"].orEmpty().split(',').toSet())
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
