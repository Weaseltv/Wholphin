package com.github.damontecres.wholphin.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.kids.onlineNetwork
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeSettingsOwner
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.KidHomeCache
import com.github.damontecres.wholphin.services.KidHomeCollections
import com.github.damontecres.wholphin.services.KidHomeSlow
import com.github.damontecres.wholphin.services.UserSwitchListener
import com.github.damontecres.wholphin.services.mockQueryResult
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * PR #97 review round 2: the kids Home in [HomeViewModel], with the session followed by the real [UserSwitchListener]
 * (its saved profile from a Room-like flow under the session, as [ServerRepository.currentUserFlow] does)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class KidHomeViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var savedIo: CoroutineDispatcher
    private lateinit var savedDefault: CoroutineDispatcher

    private val api = mockk<ApiClient>()
    private val server = JellyfinServer(UUID.randomUUID(), "WeaselPlex", "https://media.example", "10.11.0")

    /** The session ([ServerRepository.current]) and its epoch */
    private val session = MutableStateFlow<CurrentUser?>(null)
    private val epoch = MutableStateFlow(0L)

    /** Each profile's saved (Room) row */
    private val rooms = mutableMapOf<UUID, MutableStateFlow<JellyfinUser?>>()

    /** Row fetches that wait, and those that were cancelled */
    private val gates = mutableMapOf<Any, CompletableDeferred<Unit>>()
    private val cancelled = mutableListOf<HomeRowConfig>()
    private val fetched = mutableListOf<HomeRowConfig>()
    private val limits = mutableListOf<Int>()
    private var librariesGate: CompletableDeferred<Unit>? = null

    /** Rows (by [gateKey]) whose fetch returns a title to show */
    private val withContent = mutableSetOf<Any>()

    /** Rows (by [gateKey]) whose fetch takes this long (virtual ms), or fails */
    private val delays = mutableMapOf<Any, Long>()
    private val failing = mutableSetOf<Any>()

    /** The device's preferences right now, for Home and the kids Home loader alike */
    private var preferencesNow: () -> UserPreferences = { homePreferences() }
    private val cache = mockk<KidHomeCache>(relaxed = true) { coEvery { rules() } returns null }
    private var now = 0L

    private lateinit var repository: ServerRepository
    private lateinit var homeSettings: HomeSettingsService

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        savedIo = WholphinDispatchers.IO
        savedDefault = WholphinDispatchers.Default
        WholphinDispatchers.IO = dispatcher
        WholphinDispatchers.Default = dispatcher
        mockkObject(GetItemsRequestHandler)
        serverCollections()
        repository =
            mockk(relaxed = true) {
                every { current } returns session
                every { sessionEpoch } returns epoch
                every { currentUser } answers { session.value?.user }
                every { currentUserDto } answers { session.value?.user?.let { userDto(it) } }
                every { currentServer } returns null
                every { currentUserFlow } returns
                    session.flatMapLatest { current -> current?.let { room(it.user) } ?: flowOf(null) }
            }
        homeSettings = spyk(homeSettingsService())
        homeSettings.clock = { now }
        coEvery { homeSettings.fetchDataForRow(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val row = firstArg<HomeRowConfig>()
            fetched.add(row)
            limits.add(arg(5))
            try {
                gates[gateKey(row)]?.await()
                delays[gateKey(row)]?.let { delay(it) }
                if (gateKey(row) in failing) throw IllegalStateException("row failed")
            } catch (ex: kotlinx.coroutines.CancellationException) {
                cancelled.add(row)
                throw ex
            }
            val items =
                if (gateKey(row) in
                    withContent
                ) {
                    listOf(BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)))
                } else {
                    emptyList()
                }
            HomeRowLoadingState.Success(StringStringProvider(row.toString()), items, rowType = row)
        }
    }

    @After
    fun tearDown() {
        unmockkObject(GetItemsRequestHandler)
        WholphinDispatchers.IO = savedIo
        WholphinDispatchers.Default = savedDefault
        Dispatchers.resetMain()
    }

    private fun gateKey(row: HomeRowConfig): Any = (row as? HomeRowConfig.ByParent)?.parentId ?: row::class

    private fun userDto(user: JellyfinUser) = mockk<UserDto>(relaxed = true) { every { id } returns user.id }

    private fun room(user: JellyfinUser) = rooms.getOrPut(user.id) { MutableStateFlow(user) }

    private fun user(
        name: String,
        policy: PolicyState,
    ) = JellyfinUser(
        rowId = name.hashCode(),
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        serverId = server.id,
        accessToken = "token-$name",
        policyState = policy,
    )

    private fun collection(name: String) =
        BaseItemDto(id = UUID.nameUUIDFromBytes(name.toByteArray()), type = BaseItemKind.BOX_SET, name = name)

    private fun isKidsCollections(request: GetItemsRequest) = request.tags == listOf(KidHomeCollections.TAG)

    private fun serverCollections(vararg items: BaseItemDto) {
        coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } returns mockQueryResult(items.toList())
    }

    private fun homeSettingsService(): HomeSettingsService {
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
            serverRepository = repository,
            userPreferencesService = mockk { coEvery { getCurrent() } answers { preferencesNow() } },
            navDrawerService = mockk { coEvery { getAllUserLibraries(any(), any()) } returns emptyList() },
            latestNextUpService = mockk(),
            imageUrlService = mockk(),
            suggestionService = mockk(),
            displayPreferencesService = mockk(),
            streamingOrderService = mockk(),
            justWatchRowService = mockk(),
            kidHomeCache = cache,
        )
    }

    /** The real listener, on an activity, following [session] */
    private fun listener() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        UserSwitchListener(
            context = activity,
            serverRepository = repository,
            seerrServerRepository = mockk(relaxed = true),
            homeSettingsService = homeSettings,
            navigationManager = mockk(relaxed = true),
            setupNavigationManager = mockk(relaxed = true),
            accountAvatars = mockk(relaxed = true),
        )
    }

    private fun homePreferences(maxItemsPerRow: Int = 0) =
        UserPreferences(
            AppPreferences
                .newBuilder()
                .setHomePagePreferences(HomePagePreferences.newBuilder().setMaxItemsPerRow(maxItemsPerRow))
                .build(),
            null,
        )

    private fun viewModel(preferences: () -> UserPreferences = { homePreferences() }): HomeViewModel {
        preferencesNow = preferences
        return HomeViewModel(
            context = mockk(relaxed = true),
            navigationManager = mockk(relaxed = true),
            serverRepository = repository,
            serverReportService = mockk(relaxed = true),
            navDrawerService =
                mockk(relaxed = true) {
                    coEvery { getAllUserLibraries(any(), any()) } coAnswers {
                        librariesGate?.await()
                        emptyList()
                    }
                },
            homeSettingsService = homeSettings,
            favoriteWatchManager = mockk(relaxed = true),
            datePlayedService = mockk(relaxed = true),
            backdropService = mockk(relaxed = true),
            userPreferencesService = mockk { coEvery { getCurrent() } answers { preferencesNow() } },
            mediaManagementService = mockk(relaxed = true),
            latestNextUpService = mockk(relaxed = true),
            streamingOrderService = mockk(relaxed = true),
            quickListService = mockk(relaxed = true),
            networkAvailability = onlineNetwork(),
        )
    }

    private suspend fun saveAdultLayout(user: JellyfinUser) =
        homeSettings.saveToLocal(user.id, HomePageSettings(listOf(HomeRowConfig.NextUp()), SUPPORTED_HOME_PAGE_SETTINGS_VERSION))

    private fun signIn(user: JellyfinUser) {
        epoch.value += 1
        session.value = CurrentUser(server, user)
    }

    /** What [ServerRepository] does on a policy refresh: Room first, then (after [pause]) the session, with a new epoch */
    private fun TestScope.policyRefresh(
        to: JellyfinUser,
        pause: () -> Unit = {},
    ) {
        room(to).value = to
        runCurrent()
        pause()
        epoch.value += 1
        session.value = CurrentUser(server, to)
        runCurrent()
    }

    private fun TestScope.record(viewModel: HomeViewModel): MutableList<HomeState> {
        val states = mutableListOf<HomeState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { states.add(it) } }
        return states
    }

    private fun HomeState.configs() = settings.rows.map { it.config }

    private fun HomeState.hasAdultRows() = configs().any { it is HomeRowConfig.NextUp }

    private fun HomeState.collectionIds() = configs().filterIsInstance<HomeRowConfig.ByParent>().map { it.parentId }

    private fun HomeViewModel.close() = viewModelScope.cancel()

    @Test
    fun `A load running when a policy refresh makes the profile a kid never shows its adult rows`() =
        runTest(dispatcher) {
            val adult = user("Avery", PolicyState.ADULT)
            saveAdultLayout(adult)
            listener()
            signIn(adult)
            advanceUntilIdle()
            assertTrue(
                homeSettings.currentSettings.value.rows
                    .any { it.config is HomeRowConfig.NextUp },
            )

            val viewModel = viewModel()
            // The adult rows' requests are still out when the policy changes
            gates[HomeRowConfig.NextUp::class] = CompletableDeferred()
            viewModel.init()
            runCurrent()
            assertTrue(viewModel.state.value.hasAdultRows())
            val states = record(viewModel)

            var duringPause: HomeState? = null
            policyRefresh(adult.copy(policyState = PolicyState.KID)) { duringPause = viewModel.state.value }
            // While Room said kid and the session still said adult, the listener's load published nothing new
            assertFalse(duringPause!!.configs().any { it is HomeRowConfig.ByParent })
            val afterPublish = states.size - 1

            gates.getValue(HomeRowConfig.NextUp::class).complete(Unit)
            advanceUntilIdle()

            assertTrue(cancelled.any { it is HomeRowConfig.NextUp })
            assertTrue(states.drop(afterPublish).none { it.hasAdultRows() })
            val last = viewModel.state.value
            assertEquals(LoadingState.Success, last.loadingState)
            assertEquals(true, last.settings.owner?.kid)
            assertTrue(last.configs().first() is HomeRowConfig.ContinueWatchingCombined)
            viewModel.close()
        }

    @Test
    fun `Collections found while Home is starting are shown`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            withContent.add(cartoons.id)
            val found = CompletableDeferred<Unit>()
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                found.await()
                mockQueryResult(listOf(cartoons))
            }
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            found.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(LoadingState.Success, state.loadingState)
            assertEquals(listOf(cartoons.id), state.collectionIds())
            assertTrue(state.homeRows.all { it is HomeRowLoadingState.Success })
            viewModel.close()
        }

    @Test
    fun `The loading picture stays until a row has something to show, never a page with just the menu`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            withContent.add(cartoons.id)
            val found = CompletableDeferred<Unit>()
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                found.await()
                mockQueryResult(listOf(cartoons))
            }
            listener()
            signIn(kid)
            val viewModel = viewModel()
            val states = record(viewModel)
            viewModel.init()
            runCurrent()
            // Rows 1 and 2 are back, and empty; the collections aren't found yet: still the loading picture
            assertTrue(
                viewModel.state.value.homeRows
                    .take(2)
                    .all { it is HomeRowLoadingState.Success },
            )
            assertEquals(LoadingState.Loading, viewModel.state.value.loadingState)

            found.complete(Unit)
            advanceUntilIdle()
            assertEquals(LoadingState.Success, viewModel.state.value.loadingState)
            // Home never showed Success with nothing to show
            assertTrue(
                states
                    .filter { it.loadingState == LoadingState.Success }
                    .all { st -> st.homeRows.any { it is HomeRowLoadingState.Success && it.items.isNotEmpty() } },
            )
            viewModel.close()
        }

    @Test
    fun `A kids Home with nothing at all to show says so once everything has loaded`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(LoadingState.Success, state.loadingState)
            assertEquals(LoadingState.Success, state.refreshState)
            assertTrue(state.homeRows.none { it is HomeRowLoadingState.Success && it.items.isNotEmpty() })
            viewModel.close()
        }

    @Test
    fun `Everything starts at once, and each collection's first page as soon as the list is back`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            val pixar = collection("Pixar")
            val found = CompletableDeferred<Unit>()
            var listAsked = false
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                listAsked = true
                found.await()
                mockQueryResult(listOf(cartoons, pixar))
            }
            gates[HomeRowConfig.ContinueWatchingCombined::class] = CompletableDeferred()
            gates[HomeRowConfig.GetItems::class] = CompletableDeferred()
            listener()
            signIn(kid)
            runCurrent()
            // The list, Next Up & Continue Watching and Watch Again are all out, none waiting on another
            assertTrue(listAsked)
            assertTrue(fetched.any { it is HomeRowConfig.ContinueWatchingCombined })
            assertTrue(fetched.any { it is HomeRowConfig.GetItems })

            // The list returns while the other two are still out: both first pages start at once
            found.complete(Unit)
            runCurrent()
            assertEquals(
                setOf(cartoons.id, pixar.id),
                fetched.filterIsInstance<HomeRowConfig.ByParent>().map { it.parentId }.toSet(),
            )
            gates.values.forEach { it.complete(Unit) }
            advanceUntilIdle()
        }

    @Test
    fun `Last time's rows paint before the network answers, then fresh rows replace them in place`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            val savedMovie = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, name = "Saved")
            coEvery { cache.rules() } returns "kid-rules-v1:5"
            every { cache.read(kid, "kid-rules-v1:5") } returns
                KidHomeCache.Saved(
                    serverId = server.id.toString(),
                    userId = kid.id.toString(),
                    profileRowId = kid.rowId,
                    rules = "kid-rules-v1:5",
                    savedAt = 0,
                    collections = listOf(KidHomeCache.SavedCollection(cartoons.id.toString(), "Cartoon Adventures")),
                    rows = mapOf("collection:${cartoons.id}" to KidHomeCache.SavedRow(listOf(KidHomeCache.SavedItem(savedMovie)))),
                )
            coEvery { cache.visibleNow(any()) } answers { firstArg() }
            // The network is slow
            val found = CompletableDeferred<Unit>()
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                found.await()
                mockQueryResult(listOf(cartoons))
            }
            gates[cartoons.id] = CompletableDeferred()
            withContent.add(cartoons.id)
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            val warm = viewModel.state.value
            assertEquals(LoadingState.Success, warm.loadingState)
            assertEquals(listOf(cartoons.id), warm.collectionIds())
            assertEquals(savedMovie.id, (warm.homeRows[2] as HomeRowLoadingState.Success).items.single()?.id)

            found.complete(Unit)
            gates.getValue(cartoons.id).complete(Unit)
            advanceUntilIdle()
            val fresh = viewModel.state.value
            assertEquals(kidHomeRowKeys(warm.settings), kidHomeRowKeys(fresh.settings))
            assertFalse((fresh.homeRows[2] as HomeRowLoadingState.Success).items.single()?.id == savedMovie.id)
            viewModel.close()
        }

    @Test
    fun `A saved Home is only read for this profile on this server under its own rules`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            coEvery { cache.rules() } returns "kid-rules-v1:5"
            listener()
            signIn(kid)
            advanceUntilIdle()
            io.mockk.verify { cache.read(kid, "kid-rules-v1:5") }
            io.mockk.verify(exactly = 0) { cache.read(neq(kid), any()) }
        }

    @Test
    fun `A collection added or removed around another keeps that row in place and its key`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val a = collection("Cartoon Adventures")
            val b = collection("Laugh Out Loud")
            val c = collection("Pixar")
            serverCollections(a, c)
            listener()
            signIn(kid)
            advanceUntilIdle()
            val viewModel = viewModel()
            viewModel.init()
            advanceUntilIdle()
            val before = viewModel.state.value
            val cRow = before.homeRows[3]
            val keysBefore = kidHomeRowKeys(before.settings)!!

            // B arrives above C. While C's fresh page is still out, C keeps the row it had, and its key moves with it
            serverCollections(a, b, c)
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            gates[c.id] = CompletableDeferred()
            homeSettings.refreshKidHome()
            runCurrent()
            val during = viewModel.state.value
            assertEquals(listOf(a.id, b.id, c.id), during.collectionIds())
            assertSame(cRow, during.homeRows[4])
            val keysAfter = kidHomeRowKeys(during.settings)!!
            assertEquals(keysBefore[3], keysAfter[4])
            // Focus on C (row 3 before) follows it to row 4
            assertEquals(4, remapFocusedRow(keysBefore[3], 3, keysAfter) { true })
            gates.getValue(c.id).complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.homeRows[4] is HomeRowLoadingState.Success)

            // A is removed: C moves up to row 3
            serverCollections(b, c)
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            homeSettings.refreshKidHome()
            advanceUntilIdle()
            val removed = viewModel.state.value
            assertEquals(listOf(b.id, c.id), removed.collectionIds())
            assertTrue(removed.homeRows[3] is HomeRowLoadingState.Success)
            val keysRemoved = kidHomeRowKeys(removed.settings)!!
            assertEquals(3, remapFocusedRow(keysAfter[4], 4, keysRemoved) { true })
            // Focus on A (row 2), now gone, goes to the row in its place (B)
            assertEquals(2, remapFocusedRow(keysAfter[2], 2, keysRemoved) { true })
            viewModel.close()
        }

    @Test
    fun `Focus falls back to the nearest row with content when its row is gone`() {
        val keys = listOf("row:0", "row:1", "collection:a", "collection:c")
        // The row now in its place is empty (hidden), so the one above
        assertEquals(2, remapFocusedRow("collection:b", 3, keys) { it != 3 })
        assertEquals(3, remapFocusedRow("collection:b", 9, keys) { true })
        assertNull(remapFocusedRow("collection:b", 2, keys) { false })
        assertEquals(2, remapFocusedRow("collection:a", 0, keys) { false })
    }

    @Test
    fun `Adult Home keeps positional rows`() {
        val adultSettings =
            HomePageResolvedSettings(emptyList(), owner = HomeSettingsOwner(UUID.randomUUID(), 1, false))
        assertNull(kidHomeRowKeys(adultSettings))
        assertNull(kidHomeRowKeys(HomePageResolvedSettings.EMPTY))
    }

    @Test
    fun `A restored process with no settings yet shows the kids Home once the listener loads it`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            val viewModel = viewModel()
            viewModel.init()
            advanceUntilIdle()
            assertEquals(HomePageResolvedSettings.EMPTY, viewModel.state.value.settings)
            assertTrue(
                viewModel.state.value.homeRows
                    .isEmpty(),
            )

            listener()
            signIn(kid)
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(LoadingState.Success, state.loadingState)
            assertEquals(true, state.settings.owner?.kid)
            assertEquals(listOf(cartoons.id), state.collectionIds())
            viewModel.close()
        }

    @Test
    fun `A quick kid to adult to kid switch ends on the second kid's Home, never the adult's`() =
        runTest(dispatcher) {
            val first = user("Kai", PolicyState.KID)
            val adult = user("Avery", PolicyState.ADULT)
            val second = user("Rae", PolicyState.KID)
            saveAdultLayout(adult)
            listener()
            signIn(first)
            advanceUntilIdle()
            val viewModel = viewModel()
            viewModel.init()
            advanceUntilIdle()
            assertEquals(
                first.id,
                viewModel.state.value.settings.owner
                    ?.userId,
            )

            gates[HomeRowConfig.NextUp::class] = CompletableDeferred()
            signIn(adult)
            runCurrent()
            val states = record(viewModel)
            signIn(second)
            val afterSecond = states.size
            runCurrent()
            gates.getValue(HomeRowConfig.NextUp::class).complete(Unit)
            advanceUntilIdle()

            assertTrue(states.drop(afterSecond).none { it.hasAdultRows() })
            val state = viewModel.state.value
            assertEquals(LoadingState.Success, state.loadingState)
            assertEquals(second.id, state.settings.owner?.userId)
            assertEquals(true, state.settings.owner?.kid)
            viewModel.close()
        }

    @Test
    fun `Focus taken from a removed collection lands on its replacement once that row has loaded`() {
        val before = listOf("row:0", "row:1", "collection:a")
        val after = listOf("row:0", "row:1", "collection:b")
        val focus = KidRowFocus()
        focus.onFocusedRow(2, before)
        assertEquals("collection:a", focus.focusedKey)

        // A is gone; the rows left are empty and B is still pending: nothing can take focus yet
        assertEquals(KidRowFocus.Action.None, focus.onRows(after, 2) { false })
        assertTrue(focus.unresolved)
        // B's content arrives (same keys): focus goes to it
        assertEquals(KidRowFocus.Action.Recover(2), focus.onRows(after, 2) { it == 2 })
        assertFalse(focus.unresolved)
        assertEquals("collection:b", focus.focusedKey)
        assertEquals(KidRowFocus.Action.None, focus.onRows(after, 2) { true })

        // A row added above follows; the focused row moves with it
        val added = listOf("row:0", "row:1", "collection:c", "collection:b")
        assertEquals(KidRowFocus.Action.Follow(3), focus.onRows(added, 2) { true })
    }

    @Test
    fun `Home reads its preferences once the session's settings are there, not before`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val viewModel =
                viewModel {
                    if (session.value ==
                        null
                    ) {
                        homePreferences(maxItemsPerRow = 7)
                    } else {
                        homePreferences(maxItemsPerRow = 11)
                    }
                }
            viewModel.init()
            runCurrent()
            listener()
            signIn(kid)
            advanceUntilIdle()
            assertEquals(LoadingState.Success, viewModel.state.value.loadingState)
            assertTrue(limits.isNotEmpty())
            assertTrue(limits.all { it == 11 })
            viewModel.close()
        }

    @Test
    fun `A load that never gets settings gives up after a while and tries again when they arrive`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            advanceTimeBy(29_000)
            assertFalse(viewModel.state.value.loadingState is LoadingState.Error)
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(viewModel.state.value.loadingState is LoadingState.Error)

            listener()
            signIn(kid)
            advanceUntilIdle()
            assertEquals(LoadingState.Success, viewModel.state.value.loadingState)
            assertEquals(
                kid.id,
                viewModel.state.value.settings.owner
                    ?.userId,
            )
            viewModel.close()
        }

    @Test
    fun `Kids Home rows from a session that ends right before they're shown are never shown`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            withContent.add(HomeRowConfig.GetItems::class)
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            advanceUntilIdle()
            val shown = viewModel.state.value
            assertEquals(LoadingState.Success, shown.loadingState)

            val states = record(viewModel)
            // New rows arrive; the ceiling changes between Home's check and its write
            var flip = true
            every { homeSettings.isCurrent(any<HomePageResolvedSettings>()) } answers {
                val current = callOriginal()
                if (flip) {
                    flip = false
                    epoch.value += 1
                }
                current
            }
            val content = homeSettings.kidHome.value!!
            val extra = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE), false)
            val watchAgain = shown.settings.rows[1].config
            homeSettings.kidHome.value =
                content.copy(rows = content.rows + (watchAgain to HomeRowLoadingState.Success(StringStringProvider("x"), listOf(extra))))
            advanceUntilIdle()
            // Not even for a moment
            assertTrue(
                states.none { state ->
                    state.homeRows.any { row -> (row as? HomeRowLoadingState.Success)?.items?.any { it?.id == extra.id } == true }
                },
            )
            viewModel.close()
        }

    @Test
    fun `With nothing to show after 30 seconds the loading picture goes, and slow rows still arrive`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            withContent.add(cartoons.id)
            delays[cartoons.id] = 35_000
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            assertEquals(LoadingState.Loading, viewModel.state.value.loadingState)
            advanceTimeBy(31_000)
            runCurrent()
            // The picture goes: Home says it's slow, rather than waiting on
            val slow = viewModel.state.value.loadingState
            assertTrue(slow is LoadingState.Error && slow.exception is KidHomeSlow)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(LoadingState.Success, viewModel.state.value.loadingState)
            assertTrue(cancelled.isEmpty())
            assertTrue((viewModel.state.value.homeRows[2] as HomeRowLoadingState.Success).items.isNotEmpty())
            viewModel.close()
        }

    @Test
    fun `A row still loading after 30 seconds isn't cancelled when another has shown`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            serverCollections(cartoons)
            withContent.add(HomeRowConfig.GetItems::class)
            withContent.add(cartoons.id)
            delays[cartoons.id] = 35_000
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            advanceTimeBy(36_000)
            runCurrent()
            assertTrue(cancelled.isEmpty())
            assertTrue((viewModel.state.value.homeRows[2] as HomeRowLoadingState.Success).items.isNotEmpty())
            viewModel.close()
        }

    @Test
    fun `A fast failure of a fresh row doesn't throw away its re-checked saved row`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val savedEpisode = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "Saved")
            coEvery { cache.rules() } returns "kid-rules-v1:5"
            every { cache.read(kid, "kid-rules-v1:5") } returns
                KidHomeCache.Saved(
                    serverId = server.id.toString(),
                    userId = kid.id.toString(),
                    profileRowId = kid.rowId,
                    rules = "kid-rules-v1:5",
                    savedAt = 0,
                    collections = emptyList(),
                    rows = mapOf("continue" to KidHomeCache.SavedRow(listOf(KidHomeCache.SavedItem(savedEpisode)))),
                )
            val checked = CompletableDeferred<Unit>()
            coEvery { cache.visibleNow(any()) } coAnswers {
                checked.await()
                firstArg()
            }
            // Next Up & Continue Watching fails at once, before the saved row is re-checked
            failing.add(HomeRowConfig.ContinueWatchingCombined::class)
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            val continueRow =
                homeSettings.currentSettings.value.rows[0]
                    .config
            assertTrue(homeSettings.kidHome.value!!.rows[continueRow] is HomeRowLoadingState.Error)

            checked.complete(Unit)
            runCurrent()
            val row = viewModel.state.value.homeRows[0] as HomeRowLoadingState.Success
            assertEquals(listOf(savedEpisode.id), row.items.map { it?.id })
            viewModel.close()
        }

    private fun HomeState.firstContentRow() = homeRows.indexOfFirst { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }

    @Test
    fun `A kids Home opens at its top row cold, whatever order the rows arrive in`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val top = collection("Cartoon Adventures")
            val last = collection("DreamWorks")
            serverCollections(top, last)
            withContent.addAll(listOf(HomeRowConfig.ContinueWatchingCombined::class, top.id, last.id))
            // In reverse: the last collection first, the top row last
            gates[HomeRowConfig.ContinueWatchingCombined::class] = CompletableDeferred()
            gates[top.id] = CompletableDeferred()
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            // Only a lower row has content: still the loading picture, so Home doesn't open (and focus) down there
            assertEquals(LoadingState.Loading, viewModel.state.value.loadingState)
            gates.getValue(top.id).complete(Unit)
            runCurrent()
            assertEquals(LoadingState.Loading, viewModel.state.value.loadingState)
            gates.getValue(HomeRowConfig.ContinueWatchingCombined::class).complete(Unit)
            runCurrent()
            val opened = viewModel.state.value
            assertEquals(LoadingState.Success, opened.loadingState)
            assertEquals(0, opened.firstContentRow())
            viewModel.close()
        }

    @Test
    fun `A kids Home opens at its top row warm, from the saved Home, before the refresh lands`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val top = collection("Cartoon Adventures")
            val last = collection("DreamWorks")
            val saved = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "Saved")
            coEvery { cache.rules() } returns "kid-rules-v1:5"
            every { cache.read(kid, "kid-rules-v1:5") } returns
                KidHomeCache.Saved(
                    serverId = server.id.toString(),
                    userId = kid.id.toString(),
                    profileRowId = kid.rowId,
                    rules = "kid-rules-v1:5",
                    savedAt = 0,
                    collections =
                        listOf(
                            KidHomeCache.SavedCollection(top.id.toString(), "Cartoon Adventures"),
                            KidHomeCache.SavedCollection(last.id.toString(), "DreamWorks"),
                        ),
                    rows =
                        mapOf(
                            "continue" to KidHomeCache.SavedRow(listOf(KidHomeCache.SavedItem(saved))),
                            // Watch Again was empty last time
                            "watchAgain" to KidHomeCache.SavedRow(emptyList()),
                        ),
                )
            coEvery { cache.visibleNow(any()) } answers { firstArg() }
            serverCollections(top, last)
            withContent.addAll(listOf(HomeRowConfig.ContinueWatchingCombined::class, top.id, last.id))
            // The refresh is slow, and arrives bottom first
            gates[HomeRowConfig.ContinueWatchingCombined::class] = CompletableDeferred()
            gates[top.id] = CompletableDeferred()
            listener()
            signIn(kid)
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            val warm = viewModel.state.value
            assertEquals(LoadingState.Success, warm.loadingState)
            assertEquals(0, warm.firstContentRow())
            assertEquals(saved.id, (warm.homeRows[0] as HomeRowLoadingState.Success).items.single()?.id)

            gates.values.forEach { it.complete(Unit) }
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.firstContentRow())
            viewModel.close()
        }
}
