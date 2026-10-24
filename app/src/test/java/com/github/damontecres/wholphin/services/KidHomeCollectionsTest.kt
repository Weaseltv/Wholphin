package com.github.damontecres.wholphin.services

import android.content.Context
import android.content.SharedPreferences
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.ui.main.reconcileRows
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
class KidHomeCollectionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val api = mockk<ApiClient>()
    private val server = JellyfinServer(UUID.randomUUID(), "WeaselPlex", "https://media.example", "10.11.0")
    private val session = MutableStateFlow<CurrentUser?>(null)
    private val epoch = MutableStateFlow(1L)
    private var now = 0L

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
        tags: List<String> = listOf(KidHomeCollections.TAG),
    ) = BaseItemDto(id = UUID.nameUUIDFromBytes(name.toByteArray()), type = BaseItemKind.BOX_SET, name = name, tags = tags)

    private fun isKidsCollections(request: GetItemsRequest) = request.tags == listOf(KidHomeCollections.TAG)

    private fun serverCollections(vararg items: BaseItemDto) {
        coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } returns mockQueryResult(items.toList())
    }

    private fun serverFails() {
        coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } throws RuntimeException("offline")
    }

    private fun user(
        policy: PolicyState,
        name: String = "Avery",
    ) = JellyfinUser(
        rowId = 1,
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        serverId = server.id,
        accessToken = "token",
        policyState = policy,
    )

    private fun signIn(
        user: JellyfinUser?,
        epoch: Long = this.epoch.value + 1,
    ) {
        this.epoch.value = epoch
        session.value = user?.let { CurrentUser(server, it) }
    }

    private fun service(): HomeSettingsService {
        val prefs = mockk<SharedPreferences> { every { getBoolean(any(), any()) } returns true }
        val context =
            mockk<Context> {
                every { filesDir } returns folder.root
                every { getString(R.string.kid_home_watch_again) } returns "Watch again"
                every { getSharedPreferences(any(), any()) } returns prefs
            }
        val repository =
            mockk<ServerRepository> {
                every { current } returns session
                every { sessionEpoch } returns epoch
            }
        return HomeSettingsService(
            context = context,
            api = api,
            serverRepository = repository,
            userPreferencesService = mockk(),
            navDrawerService = mockk(),
            latestNextUpService = mockk(),
            imageUrlService = mockk(),
            suggestionService = mockk(),
            displayPreferencesService = mockk(),
            streamingOrderService = mockk(),
            justWatchRowService = mockk(),
        ).also { it.clock = { now } }
    }

    /** An adult layout saved on this TV for [userId], which a kids Home must never show */
    private suspend fun HomeSettingsService.saveAdultLayout(userId: UUID) =
        saveToLocal(userId, HomePageSettings(listOf(HomeRowConfig.NextUp()), SUPPORTED_HOME_PAGE_SETTINGS_VERSION))

    private fun HomeSettingsService.collectionIds() =
        currentSettings.value.rows
            .drop(2)
            .map { (it.config as HomeRowConfig.ByParent).parentId }

    private fun owner(
        user: JellyfinUser,
        kid: Boolean = true,
    ) = HomeSettingsOwner(user.id, epoch.value, kid)

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
    fun `Collections come in the server's order, extra tags and all, with no per-collection checks`() =
        runTest {
            val cartoons = collection("Cartoon Adventures")
            val pixar = collection("Pixar", listOf("WeaselPlex Kometa", "WeaselPlex Group: Studios", KidHomeCollections.TAG))
            val dreamWorks = collection("DreamWorks")
            serverCollections(cartoons, pixar, dreamWorks)
            assertEquals(listOf(cartoons, pixar, dreamWorks), KidHomeCollections.fetch(api, UUID.randomUUID()))
            coVerify(exactly = 1) { GetItemsRequestHandler.execute(api, any()) }
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
        val owner = HomeSettingsOwner(UUID.randomUUID(), 3, kid = true)
        val settings = KidHomeCollections.settings(owner, "Watch again", listOf(cartoons, laugh, magic))
        val rows = settings.rows

        assertEquals(owner, settings.owner)
        assertTrue(rows[0].config is HomeRowConfig.ContinueWatchingCombined)
        assertEquals(KidHomeCollections.watchAgainRow("Watch again").getItems, (rows[1].config as HomeRowConfig.GetItems).getItems)
        val collectionRows = rows.drop(2)
        assertEquals(listOf(cartoons.id, laugh.id, magic.id), collectionRows.map { (it.config as HomeRowConfig.ByParent).parentId })
        assertEquals(
            listOf("CARTOON ADVENTURES", "LAUGH OUT LOUD", "MAGIC & MAKE-BELIEVE"),
            collectionRows.map { (it.title as StringStringProvider).str },
        )
        // A collection's own children, in the order the server gives them (reshuffled daily): no sort, here or in
        // the row's grid, which fetches the same row config
        collectionRows.forEach {
            val config = it.config as HomeRowConfig.ByParent
            assertFalse(config.recursive)
            assertNull(config.sort)
        }
        assertEquals(rows.indices.toList(), rows.map { it.id })
    }

    @Test
    fun `Before the server has kids collections, Home is the first two rows only`() {
        val rows = KidHomeCollections.settings(HomeSettingsOwner(UUID.randomUUID(), 1, true), "Watch again", emptyList()).rows
        assertEquals(2, rows.size)
        assertTrue(rows[0].config is HomeRowConfig.ContinueWatchingCombined)
        assertTrue(rows[1].config is HomeRowConfig.GetItems)
    }

    @Test
    fun `A collection row whose first page the kid rules emptied gets one more page before it's hidden`() =
        runTest {
            val asked = mutableListOf<Int>()
            val items = KidHomeCollections.firstPage(25) { start -> asked.add(start).let { if (start == 0) emptyList() else listOf("a") } }
            assertEquals(listOf("a"), items)
            assertEquals(listOf(0, 25), asked)

            asked.clear()
            assertEquals(listOf("b"), KidHomeCollections.firstPage(25) { start -> asked.add(start).let { listOf("b") } })
            assertEquals(listOf(0), asked)

            assertEquals(emptyList<String>(), KidHomeCollections.firstPage(25) { emptyList<String>() })
        }

    @Test
    fun `Kid and unknown profiles get the kids Home, never a saved adult layout`() =
        runTest {
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            for (user in listOf(user(PolicyState.KID), user(PolicyState.UNKNOWN))) {
                signIn(user)
                val service = service()
                service.saveAdultLayout(user.id)
                service.loadCurrentSettings(user.id, user)
                assertEquals(KidHomeCollections.settings(owner(user), "Watch again", listOf(cartoons)), service.currentSettings.value)
                assertTrue(service.isCurrent(service.currentSettings.value))
                assertFalse(
                    service.currentSettings.value.rows
                        .any { it.config is HomeRowConfig.NextUp },
                )
            }
        }

    @Test
    fun `Nothing is published for a user who isn't the session`() =
        runTest {
            val kid = user(PolicyState.KID)
            signIn(null)
            val service = service()
            service.loadCurrentSettings(kid.id, kid)
            assertEquals(HomePageResolvedSettings.EMPTY, service.currentSettings.value)
        }

    @Test
    fun `A policy refresh paused between the Room write and the session publish never shows the adult layout`() =
        runTest {
            serverCollections(collection("Cartoon Adventures"))
            val adult = user(PolicyState.ADULT)
            val kid = adult.copy(policyState = PolicyState.KID)
            signIn(adult)
            val service = service()
            service.saveAdultLayout(adult.id)
            service.loadCurrentSettings(adult.id, adult)
            val adultHome = service.currentSettings.value
            assertTrue(adultHome.rows.first().config is HomeRowConfig.NextUp)
            assertTrue(service.isCurrent(adultHome))

            // Room now says kid; the session still says adult (the refresh hasn't published yet). The listener's load
            // for the kid row publishes nothing: no adult rows, and no kids Home under the adult session
            service.currentSettings.value = HomePageResolvedSettings.EMPTY
            service.loadCurrentSettings(kid.id, kid)
            assertEquals(HomePageResolvedSettings.EMPTY, service.currentSettings.value)

            // Had Home still held the adult rows, the session publish (kid, new epoch) makes them stale at once
            signIn(kid)
            assertFalse(service.isCurrent(adultHome))
            assertFalse(
                service.isCurrent(
                    KidHomeCollections.settings(HomeSettingsOwner(kid.id, epoch.value - 1, true), "Watch again", emptyList()),
                ),
            )

            // The load for the published session is the kids Home, which is what Home waits for
            val waiting = backgroundScope.launch { service.awaitCurrentSettings() }
            runCurrent()
            assertTrue(waiting.isActive)
            service.loadCurrentSettings(kid.id, kid)
            runCurrent()
            assertFalse(waiting.isActive)
            assertEquals(owner(kid), service.currentSettings.value.owner)
            assertFalse(
                service.currentSettings.value.rows
                    .any { it.config is HomeRowConfig.NextUp },
            )
        }

    @Test
    fun `An adult session never takes a kids Home, and a kid session takes nothing else`() {
        val adult = user(PolicyState.ADULT)
        val kid = user(PolicyState.KID, "Kai")
        val service = service()
        signIn(adult)
        val kidsHome = KidHomeCollections.settings(owner(adult), "Watch again", emptyList())
        assertFalse(service.isCurrent(kidsHome))
        assertTrue(service.isCurrent(kidsHome.copy(owner = owner(adult, kid = false))))
        assertTrue(service.isCurrent(kidsHome.copy(owner = null)))

        signIn(kid)
        assertTrue(service.isCurrent(KidHomeCollections.settings(owner(kid), "Watch again", emptyList())))
        assertFalse(service.isCurrent(kidsHome.copy(owner = null)))
        assertFalse(service.isCurrent(kidsHome.copy(owner = owner(kid, kid = false))))
        assertFalse(service.isCurrent(KidHomeCollections.settings(owner(adult), "Watch again", emptyList())))
        assertFalse(service.isCurrent(HomePageResolvedSettings.EMPTY))
    }

    @Test
    fun `Rows 1 and 2 show while the collections are still being found, which then follow`() =
        runTest {
            val kid = user(PolicyState.KID)
            signIn(kid)
            val cartoons = collection("Cartoon Adventures")
            val found = CompletableDeferred<Unit>()
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                found.await()
                mockQueryResult(listOf(cartoons))
            }
            val service = service()
            val load = backgroundScope.launch { service.loadCurrentSettings(kid.id, kid) }
            runCurrent()
            assertTrue(load.isActive)
            assertEquals(KidHomeCollections.settings(owner(kid), "Watch again", emptyList()), service.currentSettings.value)
            assertTrue(service.isCurrent(service.currentSettings.value))

            found.complete(Unit)
            runCurrent()
            assertEquals(listOf(cartoons.id), service.collectionIds())
        }

    @Test
    fun `Returning Home picks up added and removed collections, throttled, and keeps its rows through an error`() =
        runTest {
            val kid = user(PolicyState.KID)
            signIn(kid)
            val a = collection("Cartoon Adventures")
            val b = collection("Family Fun Night")
            serverCollections(a)
            val service = service()
            service.loadCurrentSettings(kid.id, kid)
            assertEquals(listOf(a.id), service.collectionIds())

            // Within the interval: not asked again
            serverCollections(a, b)
            now += 60_000
            service.refreshKidCollections()
            assertEquals(listOf(a.id), service.collectionIds())

            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            service.refreshKidCollections()
            assertEquals(listOf(a.id, b.id), service.collectionIds())

            serverCollections(b)
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            service.refreshKidCollections()
            assertEquals(listOf(b.id), service.collectionIds())

            // A failed look keeps the rows shown, and the next visit tries again
            serverFails()
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            service.refreshKidCollections()
            assertEquals(listOf(b.id), service.collectionIds())
            serverCollections(a, b)
            service.refreshKidCollections()
            assertEquals(listOf(a.id, b.id), service.collectionIds())
        }

    @Test
    fun `A kids Home that couldn't find its collections recovers on the next visit, without signing out`() =
        runTest {
            val kid = user(PolicyState.KID)
            signIn(kid)
            serverFails()
            val service = service()
            service.saveAdultLayout(kid.id)
            service.loadCurrentSettings(kid.id, kid)
            assertEquals(KidHomeCollections.settings(owner(kid), "Watch again", emptyList()), service.currentSettings.value)

            val a = collection("Cartoon Adventures")
            serverCollections(a)
            service.refreshKidCollections()
            assertEquals(listOf(a.id), service.collectionIds())
        }

    @Test
    fun `A reload in the same session starts from the collections already found`() =
        runTest {
            val kid = user(PolicyState.KID)
            signIn(kid)
            val a = collection("Cartoon Adventures")
            serverCollections(a)
            val service = service()
            service.loadCurrentSettings(kid.id, kid)
            serverFails()
            service.currentSettings.value = HomePageResolvedSettings.EMPTY
            service.loadCurrentSettings(kid.id, kid)
            assertEquals(listOf(a.id), service.collectionIds())
        }

    @Test
    fun `Adults keep their own Home and never ask for the kids collections`() =
        runTest {
            val adult = user(PolicyState.ADULT)
            signIn(adult)
            val service = service()
            service.saveAdultLayout(adult.id)
            service.loadCurrentSettings(adult.id, adult)
            val configs =
                service.currentSettings.value.rows
                    .map { it.config }
            assertTrue(configs.first() is HomeRowConfig.NextUp)
            assertFalse(configs.any { it is HomeRowConfig.ByParent || it is HomeRowConfig.ContinueWatchingCombined })
            assertTrue(service.isCurrent(service.currentSettings.value))
            service.refreshKidCollections()
            coVerify(exactly = 0) { GetItemsRequestHandler.execute(api, any()) }
        }

    @Test
    fun `New kids Home settings keep the rows already loaded and only fetch the new ones`() {
        val owner = HomeSettingsOwner(UUID.randomUUID(), 1, true)
        val a = collection("Cartoon Adventures")
        val b = collection("Family Fun Night")
        val old = KidHomeCollections.settings(owner, "Watch again", listOf(a))
        val loaded = old.rows.map { HomeRowLoadingState.Success(it.title, emptyList()) }
        val new = KidHomeCollections.settings(owner, "Watch again", listOf(b, a))
        val rows = reconcileRows(old, loaded, new)
        assertEquals(loaded[0], rows[0])
        assertEquals(loaded[1], rows[1])
        assertTrue(rows[2] is HomeRowLoadingState.Pending)
        assertEquals(loaded[2], rows[3])

        val removed = reconcileRows(new, rows, old)
        assertEquals(loaded, removed)
    }
}
