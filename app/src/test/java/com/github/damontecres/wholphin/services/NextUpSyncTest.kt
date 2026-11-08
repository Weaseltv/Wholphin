package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class NextUpSyncTest {
    private val user = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherUser = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val series = UUID.fromString("281868fd-6a4b-7a21-bb13-26166a2a08e7")
    private val otherSeries = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val removedAt = LocalDateTime.parse("2026-11-01T12:00:00")
    private var server = "https://first.example"
    private val api = mockk<ApiClient>(relaxed = true)
    private val itemsApi = mockk<ItemsApi>()
    private val prefs = mockk<DisplayPreferencesService>()
    private val documents = mutableMapOf<Triple<String, UUID, String>, DisplayPreferencesDto>()
    private val writes = mutableListOf<String>()

    init {
        every { api.baseUrl } answers { server }
        every { api.itemsApi } returns itemsApi
        coEvery { prefs.getDisplayPreferences(any(), any(), any()) } answers {
            document(firstArg(), thirdArg())
        }
        coEvery { prefs.updateDisplayPreferences(any(), any(), any(), any()) } answers {
            val id = firstArg<UUID>()
            val client = thirdArg<String>()
            val current = document(id, client)
            val data = current.customPrefs.toMutableMap()
            arg<MutableMap<String, String?>.() -> Unit>(3).invoke(data)
            documents[Triple(server, id, client)] = current.copy(customPrefs = data)
            writes += client
        }
    }

    private fun service() = LatestNextUpService(api, mockk(relaxed = true), prefs)

    private fun document(
        id: UUID,
        client: String,
    ) = documents[Triple(server, id, client)] ?: testDisplayPreferencesDto

    private fun seed(
        client: String,
        vararg values: Pair<UUID, LocalDateTime>,
        id: UUID = user,
    ) {
        documents[Triple(server, id, client)] =
            testDisplayPreferencesDto.copy(
                customPrefs =
                    mapOf(
                        LatestNextUpService.REMOVED_KEY to Json.encodeToString(RemovedSeriesIds(values.toMap())),
                        "homeSettings" to "keep this layout",
                    ),
            )
    }

    private fun episode(lastPlayed: LocalDateTime? = null) =
        BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(),
                type = BaseItemKind.EPISODE,
                seriesId = series,
                userData =
                    UserItemDataDto(
                        playbackPositionTicks = 123000000,
                        playCount = 0,
                        isFavorite = false,
                        played = false,
                        key = "episode",
                        lastPlayedDate = lastPlayed,
                        itemId = UUID.randomUUID(),
                    ),
            ),
        )

    @Test fun `Alpha reads customer TV removals and imports its old removals once`() =
        runTest {
            seed("Wholphin", series to removedAt)
            seed("Wholphin (Debug)", otherSeries to removedAt)
            val manager = service()
            assertEquals(setOf(series, otherSeries), manager.getRemovedFromNextUp(user).keys)
            assertEquals("keep this layout", document(user, "Wholphin").customPrefs["homeSettings"])
            assertEquals("keep this layout", document(user, "Wholphin (Debug)").customPrefs["homeSettings"])
            manager.allowSeriesRemovedFromNextUp(user, otherSeries)
            // Even another process must not reimport a restored legacy removal.
            assertEquals(setOf(series), service().getRemovedFromNextUp(user).keys)
            assertEquals(1, writes.count { it == "Wholphin (Debug)" })
        }

    @Test fun `new dismissal uses shared storage and UTC rather than an Alpha namespace`() =
        runTest {
            val before = LocalDateTime.now(ZoneOffset.UTC)
            val manager = service()
            manager.removeFromNextUp(user, episode())
            val actual = manager.getRemovedFromNextUp(user).getValue(series)
            assertFalse(actual.isBefore(before))
            assertFalse(actual.isAfter(LocalDateTime.now(ZoneOffset.UTC)))
            assertTrue(document(user, "Wholphin").customPrefs.getValue(LatestNextUpService.REMOVED_KEY)!!.contains(series.toString()))
            assertNull(document(user, "Wholphin (Debug)").customPrefs[LatestNextUpService.REMOVED_KEY])
        }

    @Test fun `phone wire format hides resume while a later play restores visibility`() =
        runTest {
            seed("Wholphin", series to removedAt)
            val item = episode(removedAt.minusDays(1))
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } returns
                Response(
                    content = BaseItemDtoQueryResult(totalRecordCount = 1, startIndex = 0, items = listOf(item.data)),
                    status = 200,
                    headers = emptyMap(),
                )
            val manager = service()
            assertTrue(manager.getResume(user, 20, true).isEmpty())
            // Dismissal did not erase this episode's watch progress.
            assertEquals(123000000L, item.data.userData!!.playbackPositionTicks)
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } returns
                Response(
                    content =
                        BaseItemDtoQueryResult(
                            totalRecordCount = 1,
                            startIndex = 0,
                            items = listOf(item.data.copy(userData = item.data.userData!!.copy(lastPlayedDate = removedAt.plusDays(1)))),
                        ),
                    status = 200,
                    headers = emptyMap(),
                )
            assertEquals(1, manager.getResume(user, 20, true).size)
        }

    @Test fun `playing or marking played restores the shared series and respects account changes`() =
        runTest {
            seed("Wholphin", series to removedAt, otherSeries to removedAt)
            val manager = service()
            manager.reengageSeries(user, series) { false }
            assertTrue(series in manager.getRemovedFromNextUp(user))
            manager.reengageSeries(user, series)
            assertEquals(setOf(otherSeries), manager.getRemovedFromNextUp(user).keys)
        }

    @Test fun `migration and removals are isolated by server and user`() =
        runTest {
            seed("Wholphin (Debug)", series to removedAt)
            val manager = service()
            assertEquals(setOf(series), manager.getRemovedFromNextUp(user).keys)
            assertTrue(manager.getRemovedFromNextUp(otherUser).isEmpty())
            server = "https://second.example"
            seed("Wholphin (Debug)", otherSeries to removedAt)
            assertEquals(setOf(otherSeries), manager.getRemovedFromNextUp(user).keys)
            assertTrue(manager.getRemovedFromNextUp(otherUser).isEmpty())
        }

    @Test fun `background cleanup preserves a newer dismissal saved during its scan`() =
        runTest {
            seed("Wholphin", series to removedAt)
            val newer = removedAt.plusDays(4)
            coEvery {
                itemsApi.getItems(
                    userId = user,
                    parentId = series,
                    recursive = true,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    sortBy = listOf(org.jellyfin.sdk.model.api.ItemSortBy.DATE_PLAYED),
                    sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
                    limit = 1,
                )
            } answers {
                seed("Wholphin", series to newer, otherSeries to newer)
                Response(
                    content =
                        BaseItemDtoQueryResult(
                            totalRecordCount = 1,
                            startIndex = 0,
                            items = listOf(episode(removedAt.plusDays(1)).data),
                        ),
                    status = 200,
                    headers = emptyMap(),
                )
            }
            val manager = service()
            manager.updateRemovedFromNextUp(user)
            assertEquals(mapOf(series to newer, otherSeries to newer), manager.getRemovedFromNextUp(user))
        }
}
