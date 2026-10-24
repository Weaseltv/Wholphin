package com.github.damontecres.wholphin.ui.main

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeSettingsOwner
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.KidHomeCollections
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private var librariesGate: CompletableDeferred<Unit>? = null
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
            try {
                gates[gateKey(row)]?.await()
            } catch (ex: kotlinx.coroutines.CancellationException) {
                cancelled.add(row)
                throw ex
            }
            HomeRowLoadingState.Success(StringStringProvider(row.toString()), emptyList(), rowType = row)
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

    private fun viewModel(): HomeViewModel {
        val preferences = UserPreferences(AppPreferences.getDefaultInstance(), null)
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
            userPreferencesService = mockk { coEvery { getCurrent() } returns preferences },
            mediaManagementService = mockk(relaxed = true),
            latestNextUpService = mockk(relaxed = true),
            streamingOrderService = mockk(relaxed = true),
            quickListService = mockk(relaxed = true),
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
    fun `Collections found while the first load is starting are not lost`() =
        runTest(dispatcher) {
            val kid = user("Kai", PolicyState.KID)
            val cartoons = collection("Cartoon Adventures")
            val found = CompletableDeferred<Unit>()
            coEvery { GetItemsRequestHandler.execute(api, match { isKidsCollections(it) }) } coAnswers {
                found.await()
                mockQueryResult(listOf(cartoons))
            }
            listener()
            signIn(kid)
            runCurrent()
            // Rows 1-2 are out; the load takes them and waits for the libraries, before it shows anything
            assertEquals(2, homeSettings.currentSettings.value.rows.size)
            librariesGate = CompletableDeferred()
            val viewModel = viewModel()
            viewModel.init()
            runCurrent()
            assertEquals(HomePageResolvedSettings.EMPTY, viewModel.state.value.settings)

            // Discovery publishes the collection rows now, then the load goes on with its two rows
            found.complete(Unit)
            runCurrent()
            librariesGate!!.complete(Unit)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(LoadingState.Success, state.loadingState)
            assertEquals(listOf(cartoons.id), state.collectionIds())
            assertTrue(state.homeRows.all { it is HomeRowLoadingState.Success })
            viewModel.close()
        }

    @Test
    fun `A collection added or removed around another keeps that row's loaded state and its key`() =
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

            // B arrives above C: C's row is the same object, B is fetched alone, and C's key moves with it
            serverCollections(a, b, c)
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            fetched.clear()
            homeSettings.refreshKidCollections()
            advanceUntilIdle()
            val after = viewModel.state.value
            assertEquals(listOf(a.id, b.id, c.id), after.collectionIds())
            assertSame(cRow, after.homeRows[4])
            assertEquals(listOf<Any>(b.id), fetched.map { (it as HomeRowConfig.ByParent).parentId })
            val keysAfter = kidHomeRowKeys(after.settings)!!
            assertEquals(keysBefore[3], keysAfter[4])
            // Focus on C (row 3 before) follows it to row 4
            assertEquals(4, remapFocusedRow(keysBefore[3], 3, keysAfter) { true })

            // A is removed: C moves up to row 3 and keeps its state
            serverCollections(b, c)
            now += KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
            homeSettings.refreshKidCollections()
            advanceUntilIdle()
            val removed = viewModel.state.value
            assertEquals(listOf(b.id, c.id), removed.collectionIds())
            assertSame(cRow, removed.homeRows[3])
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
}
