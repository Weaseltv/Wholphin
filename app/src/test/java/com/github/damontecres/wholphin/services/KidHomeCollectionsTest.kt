package com.github.damontecres.wholphin.services

import android.content.Context
import android.content.SharedPreferences
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class KidHomeCollectionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val api = mockk<ApiClient>()

    @Before
    fun setUp() {
        mockkObject(GetItemsRequestHandler)
    }

    @After
    fun tearDown() {
        unmockkObject(GetItemsRequestHandler)
    }

    private fun collection(
        name: String,
        childCount: Int? = 5,
    ) = BaseItemDto(id = UUID.nameUUIDFromBytes(name.toByteArray()), type = BaseItemKind.BOX_SET, name = name, childCount = childCount)

    private fun isKidsCollections(request: GetItemsRequest) = request.tags == listOf(KidHomeCollections.TAG)

    private fun serverCollections(vararg items: BaseItemDto) {
        coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } returns mockQueryResult(items.toList())
    }

    @Test
    fun `The collections query asks for the kids-tagged BoxSets as the kid, in the server's sort-name order`() =
        runTest {
            val kid = UUID.randomUUID()
            serverCollections()
            KidHomeCollections.fetch(api, kid)
            coVerify {
                GetItemsRequestHandler.execute(
                    api,
                    match {
                        it.userId == kid &&
                            it.includeItemTypes == listOf(BaseItemKind.BOX_SET) &&
                            it.recursive == true &&
                            it.tags == listOf("WeaselPlex Kids") &&
                            it.sortBy == listOf(ItemSortBy.SORT_NAME) &&
                            it.sortOrder == listOf(SortOrder.ASCENDING)
                    },
                )
            }
        }

    @Test
    fun `Watch again is the kid's played movies and shows, most recently played first, 40 at most`() {
        val row = KidHomeCollections.watchAgainRow("Watch again")
        assertEquals("Watch again", row.name)
        with(row.getItems) {
            assertEquals(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), includeItemTypes)
            assertEquals(true, recursive)
            assertEquals(true, isPlayed)
            assertEquals(listOf(ItemSortBy.DATE_PLAYED), sortBy)
            assertEquals(listOf(SortOrder.DESCENDING), sortOrder)
            assertEquals(40, limit)
            assertNull(tags)
            assertNull(parentId)
        }
        assertFalse(StreamingCollections.isStreamingRow(row))
    }

    @Test
    fun `Rows are continue watching, watch again, then each collection in the server's order with its name in upper case`() {
        val cartoons = collection("Cartoon Adventures")
        val laugh = collection("Laugh Out Loud")
        val magic = collection("Magic & Make-Believe")
        val rows = KidHomeCollections.settings("Watch again", listOf(cartoons, laugh, magic)).rows

        assertTrue(rows[0].config is HomeRowConfig.ContinueWatchingCombined)
        assertEquals(KidHomeCollections.watchAgainRow("Watch again").getItems, (rows[1].config as HomeRowConfig.GetItems).getItems)
        val collectionRows = rows.drop(2)
        assertEquals(listOf(cartoons.id, laugh.id, magic.id), collectionRows.map { (it.config as HomeRowConfig.ByParent).parentId })
        assertEquals(
            listOf("CARTOON ADVENTURES", "LAUGH OUT LOUD", "MAGIC & MAKE-BELIEVE"),
            collectionRows.map { (it.title as StringStringProvider).str },
        )
        // A collection's own children, in the order the server gives them
        collectionRows.forEach {
            val config = it.config as HomeRowConfig.ByParent
            assertFalse(config.recursive)
            assertNull(config.sort)
        }
        assertEquals(rows.indices.toList(), rows.map { it.id })
    }

    @Test
    fun `Before the server has kids collections, Home is the first two rows only`() {
        val rows = KidHomeCollections.settings("Watch again", emptyList()).rows
        assertEquals(2, rows.size)
        assertTrue(rows[0].config is HomeRowConfig.ContinueWatchingCombined)
        assertTrue(rows[1].config is HomeRowConfig.GetItems)
    }

    @Test
    fun `Collections keep the server's order and those with nothing the kid can see are hidden`() =
        runTest {
            val kid = UUID.randomUUID()
            val disney = collection("Disney")
            val empty = collection("Pixar", childCount = 0)
            val probedVisible = collection("DreamWorks", childCount = null)
            val probedEmpty = collection("Sing-Along", childCount = null)
            serverCollections(disney, empty, probedVisible, probedEmpty)
            coEvery { GetItemsRequestHandler.execute(api, match { it.parentId == probedVisible.id && it.userId == kid }) } returns
                mockQueryResult(listOf(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)))
            coEvery { GetItemsRequestHandler.execute(api, match { it.parentId == probedEmpty.id && it.userId == kid }) } returns
                mockQueryResult(emptyList())

            assertEquals(listOf(disney, probedVisible), KidHomeCollections.fetch(api, kid))
        }

    private fun user(policy: PolicyState) =
        JellyfinUser(
            rowId = policy.ordinal,
            id = UUID.nameUUIDFromBytes(policy.name.toByteArray()),
            name = policy.name,
            serverId = UUID.randomUUID(),
            accessToken = "token",
            policyState = policy,
        )

    private fun service(current: JellyfinUser?): HomeSettingsService {
        val prefs = mockk<SharedPreferences> { every { getBoolean(any(), any()) } returns true }
        val context =
            mockk<Context> {
                every { filesDir } returns folder.root
                every { getString(R.string.kid_home_watch_again) } returns "Watch again"
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        return HomeSettingsService(
            context = context,
            api = api,
            serverRepository = mockk<ServerRepository> { every { currentUser } returns current },
            userPreferencesService = mockk(),
            navDrawerService = mockk(),
            latestNextUpService = mockk(),
            imageUrlService = mockk(),
            suggestionService = mockk(),
            displayPreferencesService = mockk(),
            streamingOrderService = mockk(),
            justWatchRowService = mockk(),
        )
    }

    /** An adult layout saved on this TV for [userId], which a kids Home must never show */
    private suspend fun HomeSettingsService.saveAdultLayout(userId: UUID) =
        saveToLocal(userId, HomePageSettings(listOf(HomeRowConfig.NextUp()), SUPPORTED_HOME_PAGE_SETTINGS_VERSION))

    @Test
    fun `Kid and unknown profiles get the kids Home, never a saved adult layout`() =
        runTest {
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            for (user in listOf(user(PolicyState.KID), user(PolicyState.UNKNOWN), null)) {
                val userId = user?.id ?: UUID.randomUUID()
                val service = service(user)
                service.saveAdultLayout(userId)
                service.loadCurrentSettings(userId)
                val rows = service.currentSettings.value.rows
                assertEquals(KidHomeCollections.settings("Watch again", listOf(cartoons)), service.currentSettings.value)
                assertFalse(rows.any { it.config is HomeRowConfig.NextUp })
            }
        }

    @Test
    fun `If the kids collections can't be loaded, a kid still gets the first two rows and never the adult ones`() =
        runTest {
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } throws RuntimeException("offline")
            val kid = user(PolicyState.KID)
            val service = service(kid)
            service.saveAdultLayout(kid.id)
            service.loadCurrentSettings(kid.id)
            assertEquals(KidHomeCollections.settings("Watch again", emptyList()), service.currentSettings.value)
        }

    @Test
    fun `Adults keep their own Home and never ask for the kids collections`() =
        runTest {
            val adult = user(PolicyState.ADULT)
            val service = service(adult)
            service.saveAdultLayout(adult.id)
            service.loadCurrentSettings(adult.id)
            val configs =
                service.currentSettings.value.rows
                    .map { it.config }
            assertTrue(configs.first() is HomeRowConfig.NextUp)
            assertFalse(configs.any { it is HomeRowConfig.ByParent || it is HomeRowConfig.ContinueWatchingCombined })
            coVerify(exactly = 0) { GetItemsRequestHandler.execute(api, any()) }
        }
}
