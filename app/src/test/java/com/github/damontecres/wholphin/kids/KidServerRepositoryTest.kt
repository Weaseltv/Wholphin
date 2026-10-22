package com.github.damontecres.wholphin.kids

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.tvprovider.LauncherRows
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ServerRepository's kid-profiles duties (plan 4.1, 4.2): policy columns and kid protection persisted before a
 * session is published, the session ceiling written on every completed switch, nothing published when the gate
 * aborts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidServerRepositoryTest {
    private val context = mockk<Context>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val scopedApi = mockk<ApiClient>()
    private val userApi = mockk<UserApi>()
    private val systemApi = mockk<SystemApi>()

    /** In memory (review round 4): no real IO, so the test dispatcher is the only timing model */
    private val keyValue = FakePreferencesDataStore()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")

    private fun policy(maxParentalRating: Int?) = mockk<UserPolicy> { every { this@mockk.maxParentalRating } returns maxParentalRating }

    private fun userDto(
        id: UUID,
        maxParentalRating: Int?,
    ) = UserDto(
        id = id,
        name = "user",
        serverName = "test server",
        hasPassword = true,
        hasConfiguredPassword = true,
        hasConfiguredEasyPassword = false,
        policy = policy(maxParentalRating),
    )

    private fun row(
        rowId: Int,
        pin: String? = null,
    ) = JellyfinUser(
        rowId = rowId,
        id = UUID.randomUUID(),
        name = "user$rowId",
        serverId = serverId,
        accessToken = "token$rowId",
        pin = pin,
    )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(UnconfinedTestDispatcher())
        store = KidProtectionStore(keyValue)
        val appPreferences = mockk<DataStore<AppPreferences>>()
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        every { jellyfin.createApi(any(), any(), any(), any(), any()) } returns scopedApi
        every { scopedApi.userApi } returns userApi
        every { scopedApi.systemApi } returns systemApi
        coEvery { systemApi.getPublicSystemInfo() } returns
            Response(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"), 200, emptyMap())
        every { serverDao.addOrUpdateServer(any()) } just Runs
        every { serverDao.addOrUpdateUser(any()) } answers { firstArg() }
        every { serverDao.updateUserPolicy(any(), any(), any()) } just Runs
        every { serverDao.getUser(any(), any()) } returns null
        repository =
            ServerRepository(
                context,
                jellyfin,
                serverDao,
                sharedApi,
                appPreferences,
                UnconfinedTestDispatcher(),
                store,
            )
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `kid switch persists policy and protection before publishing, the ceiling once completed`() =
        runTest {
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            val events = mutableListOf<String>()
            every { serverDao.addOrUpdateUser(any()) } answers {
                events += "room"
                firstArg()
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.currentUserDtoFlow.drop(1).collect { events += "dto" }
            }
            // The write that completes the switch waits until the published session was seen (a barrier, not timing)
            val seen = CompletableDeferred<Unit>()
            keyValue.beforeUpdate = { preview ->
                if (preview[stringPreferencesKey("kids.sessionCeiling")] == "10") seen.await()
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.current.drop(1).collect {
                    events += "current protected=${store.isProtected()} ceiling=${store.sessionCeiling()}"
                    seen.complete(Unit)
                }
            }

            val saved = slot<JellyfinUser>()
            every { serverDao.addOrUpdateUser(capture(saved)) } answers {
                events += "room"
                firstArg()
            }
            repository.changeUser(server, kid, authorize = {})

            assertEquals(PolicyState.KID, saved.captured.policyState)
            assertEquals(10, saved.captured.maxParentalRating)
            // Published while the switch is journalled (reads unknown), completed right after
            assertEquals(
                listOf("room", "dto", "current protected=true ceiling=${ProfileCeiling.Unknown}"),
                events,
            )
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `adult switch stores an unlimited ceiling and does not protect`() =
        runTest {
            val adult = row(1, pin = "1234")
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adult.id, null))

            repository.changeUser(server, adult, authorize = {})

            assertFalse(store.isProtected())
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
            assertEquals(PolicyState.ADULT, repository.currentUser?.policyState)
        }

    @Test
    fun `adult handing off to a younger kid lowers the ceiling`() =
        runTest {
            val adult = row(1, pin = "1234")
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adult.id, null))
            repository.changeUser(server, adult, authorize = {})
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            repository.changeUser(server, kid, authorize = {})

            assertTrue(store.isProtected())
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `aborted switch publishes nothing and leaves the shared client alone`() =
        runTest {
            val kid = row(4)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 14))
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))

            try {
                repository.changeUser(
                    server,
                    kid,
                    beforePublish = { throw ProfileGateException(GateDecision.NeedsParentUnlock) },
                    authorize = {},
                )
                error("should have thrown")
            } catch (_: ProfileGateException) {
            }

            assertNull(repository.current.value)
            assertNull(repository.currentUserDto)
            verify(exactly = 0) { sharedApi.update(any(), any(), any(), any()) }
            verify(exactly = 0) { serverDao.addOrUpdateUser(any()) }
            // The fresh policy is still recorded for the saved row
            verify(exactly = 1) { serverDao.updateUserPolicy(4, 14, PolicyState.KID) }
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `removing the current kid keeps protection and the ceiling`() =
        runTest {
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            every { serverDao.deleteUser(any(), any()) } just Runs
            repository.changeUser(server, kid, authorize = {})

            repository.removeUser(repository.currentUser!!)

            assertNull(repository.current.value)
            assertTrue(store.isProtected())
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `first kid via Quick Connect protects the device before publishing`() =
        runTest {
            val kidId = UUID.randomUUID()
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kidId, 14))
            var protectedAtPublish: Boolean? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.current.drop(1).collect { protectedAtPublish = store.isProtected() }
            }
            val auth =
                AuthenticationResult(
                    user = userDto(kidId, 14),
                    serverId = serverId.toServerString(),
                    accessToken = "kid-token",
                )

            repository.changeUser(server.url, auth, null, authorize = {})

            assertEquals(true, protectedAtPublish)
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
        }

    @Test
    fun `re-adding a saved adult keeps their PIN`() =
        runTest {
            val adult = row(1, pin = "1234")
            every { serverDao.getUser(serverId, adult.id) } returns adult
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adult.id, null))
            val saved = slot<JellyfinUser>()
            every { serverDao.addOrUpdateUser(capture(saved)) } answers { firstArg() }
            val auth =
                AuthenticationResult(
                    user = userDto(adult.id, null),
                    serverId = serverId.toServerString(),
                    accessToken = "new-token",
                )

            repository.changeUser(server.url, auth, null, authorize = {})

            assertEquals("1234", saved.captured.pin)
            assertEquals(1, saved.captured.rowId)
            assertEquals("new-token", saved.captured.accessToken)
        }

    @Test
    fun `Quick Connect as another user never takes over the selected profile's row or PIN`() =
        runTest {
            // The kid was picked on the chooser, but a new adult approved the Quick Connect
            val kid = row(3, pin = "5555").copy(policyState = PolicyState.KID, maxParentalRating = 10)
            val adultId = UUID.randomUUID()
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adultId, null))
            val saved = slot<JellyfinUser>()
            every { serverDao.addOrUpdateUser(capture(saved)) } answers { firstArg() }
            val auth =
                AuthenticationResult(
                    user = userDto(adultId, null),
                    serverId = serverId.toServerString(),
                    accessToken = "adult-token",
                )

            repository.changeUser(server.url, auth, kid, authorize = {})

            assertEquals(adultId, saved.captured.id)
            assertEquals(0, saved.captured.rowId)
            assertNull(saved.captured.pin)
            assertEquals("adult-token", saved.captured.accessToken)
        }

    @Test
    fun `a switch interrupted before it completes never leaves a higher ceiling`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val adult = row(1, pin = "1234")
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adult.id, null))
            // What a restart would read if the process died at each step of the switch
            val seenOnRestart = mutableListOf<ProfileCeiling>()

            fun snapshot() = runBlocking { seenOnRestart += KidProtectionStore(keyValue).sessionCeiling() }
            every { serverDao.addOrUpdateUser(any()) } answers {
                snapshot()
                firstArg()
            }
            every { sharedApi.update(any(), any(), any(), any()) } answers {
                snapshot()
                throw IllegalStateException("died before publishing")
            }

            try {
                repository.changeUser(server, adult, authorize = {})
                error("should have thrown")
            } catch (_: IllegalStateException) {
            }

            assertEquals(2, seenOnRestart.size)
            seenOnRestart.forEach { assertFalse("saw $it", it == ProfileCeiling.Unlimited) }
            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `an unfinished switch reads as unknown after a restart`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val switchId = store.beginSwitch(protect = false).id
            assertEquals(ProfileCeiling.Unknown, KidProtectionStore(keyValue).sessionCeiling())
            store.completeSwitch(switchId, ProfileCeiling.Rating(14))
            assertEquals(ProfileCeiling.Rating(14), KidProtectionStore(keyValue).sessionCeiling())
        }

    @Test
    fun `a stale switch continuation can't complete or abort the journalled switch`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val stale = store.beginSwitch(protect = false).id
            val current = store.beginSwitch(protect = false).id

            assertFalse(store.completeSwitch(stale, ProfileCeiling.Rating(17)))
            assertFalse(store.abortSwitch(stale))
            // Still journalled for the current switch, and the stale ceiling was never written
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertTrue(store.completeSwitch(current, ProfileCeiling.Rating(14)))
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
        }

    @Test
    fun `a held switch reads as unknown until it is entered, also after a restart`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val switchId = store.beginSwitch(protect = false).id
            assertTrue(store.holdSwitch(switchId, ProfileCeiling.Unlimited))
            assertEquals(ProfileCeiling.Unknown, KidProtectionStore(keyValue).sessionCeiling())

            // A stale switch enters nothing; the held one applies the destination it recorded
            assertFalse(KidProtectionStore(keyValue).enterSwitch(UUID.randomUUID().toString()))
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertTrue(KidProtectionStore(keyValue).enterSwitch(switchId))
            assertEquals(ProfileCeiling.Unlimited, KidProtectionStore(keyValue).sessionCeiling())
            assertFalse(store.enterSwitch(switchId))
        }

    @Test
    fun `withdrawing a held switch closes its journal at the ceiling from before it`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(14))
            store.setSessionCeiling(ProfileCeiling.Rating(14))
            val switchId = store.beginSwitch(protect = false).id
            store.holdSwitch(switchId, ProfileCeiling.Unlimited)
            // A refresh of the held session changes what entering it would apply, never the ceiling from before it
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())

            store.withdrawSwitch(ProfileCeiling.Rating(14), switchId)
            assertEquals(ProfileCeiling.Rating(14), KidProtectionStore(keyValue).sessionCeiling())
            assertFalse(store.enterSwitch(switchId))
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
        }

    @Test
    fun `a held switch refreshed before it is entered applies the refreshed ceiling`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val switchId = store.beginSwitch(protect = false).id
            store.holdSwitch(switchId, ProfileCeiling.Unlimited)
            store.setSessionCeiling(ProfileCeiling.Rating(14))

            assertTrue(store.enterSwitch(switchId))
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
        }

    /**
     * Review round 8: switches older app versions journalled and never finished, each over an entered adult's stored
     * ceiling: round 2 (a pending flag), rounds 3-6 (with a switch id) and round 7 (held for a chooser flow)
     */
    private val unfinishedSwitchRecords: List<suspend () -> Unit> =
        listOf(
            {
                keyValue.edit { it[booleanPreferencesKey("kids.switchPending")] = true }
            },
            {
                keyValue.edit {
                    it[booleanPreferencesKey("kids.switchPending")] = true
                    it[stringPreferencesKey("kids.switchId")] = UUID.randomUUID().toString()
                }
            },
            {
                keyValue.edit {
                    it[booleanPreferencesKey("kids.switchPending")] = true
                    it[stringPreferencesKey("kids.switchId")] = UUID.randomUUID().toString()
                    it[booleanPreferencesKey("kids.switchHeld")] = true
                    it[stringPreferencesKey("kids.switchCeilingBefore")] = "unlimited"
                    it[stringPreferencesKey("kids.switchDestination")] = "10"
                }
            },
        )

    private suspend fun unfinishedSwitchOverUnlimited(record: suspend () -> Unit) {
        keyValue.edit { it.clear() }
        store.markProtected(ProfileCeiling.Unlimited)
        store.setSessionCeiling(ProfileCeiling.Unlimited)
        record()
    }

    @Test
    fun `a switch aborted after replacing an unfinished one never reveals the ceiling it hid`() =
        runTest {
            unfinishedSwitchRecords.forEachIndexed { format, record ->
                unfinishedSwitchOverUnlimited(record)
                val restarted = KidProtectionStore(keyValue)
                assertEquals("format $format", ProfileCeiling.Unknown, restarted.sessionCeiling())

                val recovery = restarted.beginSwitch(protect = false)
                assertEquals("format $format", ProfileCeiling.Unknown, recovery.ceilingBefore)
                assertTrue(restarted.abortSwitch(recovery.id))
                assertEquals("format $format", ProfileCeiling.Unknown, restarted.sessionCeiling())
                assertEquals("format $format", ProfileCeiling.Unknown, KidProtectionStore(keyValue).sessionCeiling())
            }
        }

    @Test
    fun `a held switch withdrawn after replacing an unfinished one never reveals the ceiling it hid`() =
        runTest {
            unfinishedSwitchRecords.forEachIndexed { format, record ->
                unfinishedSwitchOverUnlimited(record)
                val recovery = store.beginSwitch(protect = false)
                assertTrue(store.holdSwitch(recovery.id, ProfileCeiling.Rating(14)))

                store.withdrawSwitch(ProfileCeiling.Unlimited, recovery.id)
                assertEquals("format $format", ProfileCeiling.Unknown, store.sessionCeiling())
                assertFalse(store.enterSwitch(recovery.id))
            }
        }

    @Test
    fun `a switch completed after replacing an unfinished one applies its destination`() =
        runTest {
            unfinishedSwitchRecords.forEachIndexed { format, record ->
                unfinishedSwitchOverUnlimited(record)
                val completed = store.beginSwitch(protect = false)
                assertTrue(store.completeSwitch(completed.id, ProfileCeiling.Rating(14)))
                assertEquals("format $format", ProfileCeiling.Rating(14), KidProtectionStore(keyValue).sessionCeiling())

                unfinishedSwitchOverUnlimited(record)
                val entered = store.beginSwitch(protect = false)
                assertTrue(store.holdSwitch(entered.id, ProfileCeiling.Rating(10)))
                assertTrue(store.enterSwitch(entered.id))
                assertEquals("format $format", ProfileCeiling.Rating(10), KidProtectionStore(keyValue).sessionCeiling())
            }
        }

    @Test
    fun `an aborted switch restores the ceiling from before it, never a higher one written meanwhile`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            store.setSessionCeiling(ProfileCeiling.Rating(10))
            val switch = store.beginSwitch(protect = false)
            assertEquals(ProfileCeiling.Rating(10), switch.ceilingBefore)
            store.setSessionCeiling(ProfileCeiling.Unlimited)

            assertTrue(store.abortSwitch(switch.id))
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `protection set under a journalled switch never initialises its ceiling`() =
        runTest {
            val switch = store.beginSwitch(protect = false)
            assertEquals(ProfileCeiling.Unknown, switch.ceilingBefore)
            store.markProtected(ProfileCeiling.Unlimited)

            assertTrue(store.abortSwitch(switch.id))
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
        }

    /** A repository whose every step (IO, Main, key-value and app DataStores) runs on the test scheduler */
    private fun TestScope.scheduledRepository(keyValueStore: DataStore<Preferences>): ServerRepository {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val appPreferences = mockk<DataStore<AppPreferences>>()
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        return ServerRepository(
            context,
            jellyfin,
            serverDao,
            sharedApi,
            appPreferences,
            StandardTestDispatcher(testScheduler),
            KidProtectionStore(keyValueStore),
        )
    }

    @Test
    fun `a cancellation delivered after publication still completes the switch with the destination's ceiling`() =
        runTest {
            val keyValueStore = FakePreferencesDataStore()
            val store = KidProtectionStore(keyValueStore)
            store.markProtected(ProfileCeiling.Unlimited)
            store.setSessionCeiling(ProfileCeiling.Unlimited)
            val repository = scheduledRepository(keyValueStore)
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            lateinit var switch: Job
            // The switch is cancelled the moment the kid is published, before it resumes off the main thread
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.current.collect { if (it?.user?.id == kid.id) switch.cancel() }
            }

            switch = launch { repository.changeUser(server, kid, authorize = {}) }
            switch.join()

            assertTrue(switch.isCancelled)
            assertEquals(kid.id, repository.currentUser?.id)
            // Not the previous unlimited ceiling: a 14+ sibling must still need a parent
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `overlapping switches never commit one profile's session with another's ceiling`() =
        runTest {
            val keyValueStore = FakePreferencesDataStore()
            val store = KidProtectionStore(keyValueStore)
            store.markProtected(ProfileCeiling.Rating(14))
            store.setSessionCeiling(ProfileCeiling.Rating(14))
            val repository = scheduledRepository(keyValueStore)
            val teens = row(7)
            val kids = row(3)
            coEvery { userApi.getCurrentUser() } returnsMany
                listOf(successResponse(userDto(teens.id, 17)), successResponse(userDto(kids.id, 10)))
            // The Teens switch's continuation after publishing is held back while the Kids switch runs
            val releaseTeens = CompletableDeferred<Unit>()
            keyValueStore.beforeUpdate = { preview ->
                if (preview[stringPreferencesKey("kids.sessionCeiling")] == "17") releaseTeens.await()
            }
            val kidsGo = CompletableDeferred<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.current.first { it?.user?.id == teens.id }
                kidsGo.complete(Unit)
            }

            val teensSwitch = launch { repository.changeUser(server, teens, authorize = {}) }
            val kidsSwitch =
                launch {
                    kidsGo.await()
                    repository.changeUser(server, kids, authorize = {})
                }
            advanceUntilIdle()
            // The Teens session is out and its completion held: the switches overlap
            assertTrue(kidsGo.isCompleted)
            releaseTeens.complete(Unit)
            teensSwitch.join()
            kidsSwitch.join()

            assertEquals(kids.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `first kid admission protects the device before the kid row is written`() =
        runTest {
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            var protectedWhenRowWritten: Boolean? = null
            every { serverDao.addOrUpdateUser(any()) } answers {
                protectedWhenRowWritten = runBlocking { KidProtectionStore(keyValue).isProtected() }
                firstArg()
            }

            repository.changeUser(server, kid, authorize = {})

            assertEquals(true, protectedWhenRowWritten)
        }

    @Test
    fun `PIN removal is blocked on a protected device`() =
        runTest {
            val adult = row(1, pin = "1234")
            store.markProtected(ProfileCeiling.Unlimited)
            try {
                repository.updateUserAuth(adult, null, false)
                error("should have thrown")
            } catch (_: IllegalStateException) {
            }
            verify(exactly = 0) { serverDao.addOrUpdateUser(any()) }
            verify(exactly = 0) { serverDao.updateUserAuthUnlessPinRemoved(any(), any(), any(), any()) }
        }

    @Test
    fun `current user flow carries the policy columns`() =
        runTest {
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            val current: CurrentUser = repository.changeUser(server, kid, authorize = {})
            assertEquals(PolicyState.KID, current.user.policyState)
            assertEquals(ProfileKind.KID, current.user.profileKind)
            assertEquals(ProfileCeiling.Rating(10), current.user.profileCeiling)
        }

    @Test
    fun `cancelling a kid switch while the launcher clear is awaited settles its journal`() =
        runTest {
            // Review round 5: a session at 14 before, protection on
            store.protect()
            store.setSessionCeiling(ProfileCeiling.Rating(14))
            val kid = row(5)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            // The launcher is busy, so the clear the kid session waits for doesn't finish
            val release = CompletableDeferred<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { LauncherRows.locked { release.await() } }

            val switch = launch(UnconfinedTestDispatcher(testScheduler)) { repository.changeUser(server, kid, authorize = {}) }
            // The switch is journalled (the ceiling reads unknown) and waits for the clear
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertTrue(switch.isActive)
            // Back, or another profile picked, while the clear is awaited
            switch.cancel()
            switch.join()

            assertNull(repository.current.value)
            // The journal was settled: the ceiling from before, never left unknown
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
            assertTrue(store.isProtected())
            release.complete(Unit)
        }
}

/**
 * In-memory key-value DataStore on the caller's dispatcher, so a test controls every step. [beforeUpdate] sees the
 * result an edit would write and may suspend it before it is applied.
 */
class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    var beforeUpdate: suspend (Preferences) -> Unit = {}

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        beforeUpdate(transform(state.value))
        return mutex.withLock {
            transform(state.value).also { state.value = it.toPreferences() }
        }
    }
}
