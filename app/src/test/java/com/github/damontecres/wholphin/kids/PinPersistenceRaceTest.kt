package com.github.damontecres.wholphin.kids

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.AppDatabase
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Review round 10: a profile's PIN and login requirement are never lost to an overlapping write. A PIN removal checks
 * the saved PIN and writes in one Room statement, under the session lock; a switch saves its profile with the auth
 * columns saved by then (except a PIN its own sign-in set). Real [ServerRepository] on an in-memory Room database, one
 * [StandardTestDispatcher] scheduler, and barriers that hold each step where the race happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PinPersistenceRaceTest {
    private val dispatcher = StandardTestDispatcher()

    /** The repository's IO steps: on the test scheduler, and holdable for one coroutine */
    private val io = HoldingDispatcher(StandardTestDispatcher(dispatcher.scheduler))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: JellyfinServerDao
    private val jellyfin = mockk<Jellyfin>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val scopedApi = mockk<ApiClient>()
    private val userApi = mockk<UserApi>()
    private val systemApi = mockk<SystemApi>()
    private val keyValue = FakePreferencesDataStore()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")

    /** A pinless adult, saved */
    private lateinit var adult: JellyfinUser

    private fun dto(user: JellyfinUser) =
        UserDto(
            id = user.id,
            name = user.name,
            serverName = "test server",
            hasPassword = true,
            hasConfiguredPassword = true,
            hasConfiguredEasyPassword = false,
            policy = mockk<UserPolicy> { every { maxParentalRating } returns null },
        )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.serverDao()
        dao.addOrUpdateServer(server)
        adult =
            dao.addOrUpdateUser(
                JellyfinUser(
                    id = UUID.randomUUID(),
                    name = "parent",
                    serverId = serverId,
                    accessToken = "parent-token",
                    policyState = PolicyState.ADULT,
                ),
            )
        store = KidProtectionStore(keyValue)
        val appPreferences = mockk<DataStore<AppPreferences>>()
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        every { jellyfin.createApi(any(), any(), any(), any(), any()) } returns scopedApi
        every { scopedApi.userApi } returns userApi
        every { scopedApi.systemApi } returns systemApi
        coEvery { userApi.getCurrentUser() } returns successResponse(dto(adult))
        coEvery { systemApi.getPublicSystemInfo() } returns
            successResponse(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"))
        repository = ServerRepository(context, jellyfin, dao, sharedApi, appPreferences, io, store)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        WholphinDispatchers.reset()
    }

    private fun saved(): JellyfinUser = dao.getUser(serverId, adult.id)!!

    /** The adult's session is current; returns that session's profile */
    private suspend fun enterAdult(): JellyfinUser = repository.changeUser(server, adult, authorize = {}).user

    /** The adult's profile fetch waits for the returned barrier */
    private fun fetchHeld(): CompletableDeferred<Unit> {
        val fetched = CompletableDeferred<Unit>()
        coEvery { userApi.getCurrentUser() } coAnswers {
            fetched.await()
            successResponse(dto(adult))
        }
        return fetched
    }

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
            error("should have been refused")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `a stale PIN removal whose check ran before a first PIN was saved never removes it`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            val stale = enterAdult()
            assertFalse(stale.hasPin)

            // "None" from a Settings screen that still shows no PIN, held after its first IO step (before this fix:
            // right after it read that no PIN was saved)
            io.holdAfterFirst = "stale removal"
            launch(CoroutineName("stale removal")) { repository.updateUserAuth(stale, null, false) }
            advanceUntilIdle()
            // Meanwhile the adult (unlocked by another parent) sets a first PIN
            launch { repository.updateUserAuth(stale, "1234", false) }
            advanceUntilIdle()
            io.release()
            advanceUntilIdle()

            assertEquals("the PIN was removed on a protected device", "1234", saved().pin)
            assertEquals("1234", repository.currentUser!!.pin)
        }

    @Test
    fun `a stale Require login whose check ran before a first PIN was saved never removes it`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            val stale = enterAdult()

            io.holdAfterFirst = "stale require login"
            launch(CoroutineName("stale require login")) { repository.updateUserAuth(stale, null, true) }
            advanceUntilIdle()
            launch { repository.updateUserAuth(stale, "1234", false) }
            advanceUntilIdle()
            io.release()
            advanceUntilIdle()

            assertEquals("1234", saved().pin)
            assertEquals("1234", repository.currentUser!!.pin)
        }

    @Test
    fun `a PIN removal from a copy made before the PIN was saved is refused on a protected device`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            val stale = enterAdult()
            repository.updateUserAuth(stale, "1234", false)

            assertRefused { repository.updateUserAuth(stale, null, false) }
            assertRefused { repository.updateUserAuth(stale, null, true) }

            assertEquals("1234", saved().pin)
            assertFalse(saved().requireLogin)
            assertEquals("1234", repository.currentUser!!.pin)
        }

    @Test
    fun `a switch carrying a copy from before a PIN was set keeps that PIN in Room and in the session`() =
        runTest(dispatcher) {
            val fetched = fetchHeld()
            // The switch took its copy of the row (no PIN) and is fetching the profile
            val switch = async { repository.changeUser(server, adult, authorize = {}) }
            runCurrent()
            repository.updateUserAuth(adult, "1234", false)
            assertEquals("1234", saved().pin)

            fetched.complete(Unit)
            val session = switch.await()

            assertEquals("the switch reverted the PIN in Room", "1234", saved().pin)
            assertEquals("1234", session.user.pin)
            assertEquals("1234", repository.currentUser!!.pin)
        }

    @Test
    fun `a switch carrying a copy from before Require login was set keeps it`() =
        runTest(dispatcher) {
            val fetched = fetchHeld()
            val switch = async { repository.changeUser(server, adult, authorize = {}) }
            runCurrent()
            repository.updateUserAuth(adult, null, true)

            fetched.complete(Unit)
            val session = switch.await()

            assertTrue(saved().requireLogin)
            assertTrue(session.user.requireLogin)
            assertTrue(repository.currentUser!!.requireLogin)
        }

    @Test
    fun `a sign-in carrying a copy from before a PIN was set keeps that PIN`() =
        runTest(dispatcher) {
            val fetched = fetchHeld()
            val auth = AuthenticationResult(user = dto(adult), serverId = serverId.toServerString(), accessToken = "fresh")
            val signIn = async { repository.changeUser(server.url, auth, adult, authorize = {}) }
            runCurrent()
            repository.updateUserAuth(adult, "1234", false)

            fetched.complete(Unit)
            val session = signIn.await()

            assertEquals("1234", saved().pin)
            assertEquals("fresh", saved().accessToken)
            assertEquals("1234", session.user.pin)
        }

    @Test
    fun `a PIN set in the sign-in flow is saved even over one saved meanwhile`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            val fetched = fetchHeld()
            val auth = AuthenticationResult(user = dto(adult), serverId = serverId.toServerString(), accessToken = "fresh")
            // Quick Connect sign-in of the pinless adult with the in-flow PIN setup (deferred publication)
            val signIn = async { repository.changeUser(server.url, auth, adult, newPin = "5678", authorize = {}) }
            runCurrent()
            repository.updateUserAuth(adult, "1234", false)

            fetched.complete(Unit)
            val session = signIn.await()

            assertEquals("5678", saved().pin)
            assertEquals("5678", session.user.pin)
            assertEquals("5678", repository.currentUser!!.pin)
        }

    @Test
    fun `a PIN set in the sign-in flow is saved`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            val auth = AuthenticationResult(user = dto(adult), serverId = serverId.toServerString(), accessToken = "fresh")

            val session = repository.changeUser(server.url, auth, adult, newPin = "5678", authorize = {})

            assertEquals("5678", saved().pin)
            assertEquals("5678", session.user.pin)
        }

    @Test
    fun `Settings changes one after another on an unprotected TV`() =
        runTest(dispatcher) {
            var user = enterAdult()

            repository.updateUserAuth(user, "1234", false)
            assertEquals("1234", saved().pin)
            user = repository.currentUser!!
            assertEquals("1234", user.pin)

            repository.updateUserAuth(user, "5678", false)
            assertEquals("5678", saved().pin)
            user = repository.currentUser!!
            assertEquals("5678", user.pin)

            repository.updateUserAuth(user, null, true)
            assertNull(saved().pin)
            assertTrue(saved().requireLogin)
            user = repository.currentUser!!
            assertNull(user.pin)
            assertTrue(user.requireLogin)

            repository.updateUserAuth(user, "1234", false)
            repository.updateUserAuth(repository.currentUser!!, null, false)
            assertNull(saved().pin)
            assertFalse(saved().requireLogin)
            assertNull(repository.currentUser!!.pin)
            assertFalse(repository.currentUser!!.requireLogin)
        }

    @Test
    fun `Settings changes one after another on a protected TV`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Unlimited)
            var user = enterAdult()

            // A pinless adult may still choose Require login: no PIN to remove
            repository.updateUserAuth(user, null, true)
            assertTrue(saved().requireLogin)
            user = repository.currentUser!!

            repository.updateUserAuth(user, "1234", false)
            assertEquals("1234", saved().pin)
            assertFalse(saved().requireLogin)
            user = repository.currentUser!!

            repository.updateUserAuth(user, "5678", false)
            assertEquals("5678", saved().pin)
            user = repository.currentUser!!

            assertRefused { repository.updateUserAuth(user, null, false) }
            assertRefused { repository.updateUserAuth(user, null, true) }
            assertEquals("5678", saved().pin)
            assertFalse(saved().requireLogin)
            assertEquals("5678", repository.currentUser!!.pin)
        }

    @Test
    fun `an app preferences change from an older copy keeps the auth columns saved meanwhile`() =
        runTest(dispatcher) {
            val older = enterAdult()
            repository.updateUserAuth(older, "1234", false)

            dao.updateUserAppPreferences(older.rowId, older.appPreferences.copy(preferredAudioLanguage = "fra"))

            assertEquals("fra", saved().appPreferences.preferredAudioLanguage)
            assertEquals("1234", saved().pin)
        }
}

/**
 * Dispatches to [delegate], except that it holds the dispatches of the coroutine named [holdAfterFirst] after its first
 * one, until [release]: a barrier right after that coroutine's first IO step.
 */
private class HoldingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var holdAfterFirst: String? = null
    private var seen = 0
    private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val name = holdAfterFirst
        if (name != null && context[CoroutineName]?.name == name && seen++ > 0) {
            held += context to block
        } else {
            delegate.dispatch(context, block)
        }
    }

    fun release() {
        holdAfterFirst = null
        held.forEach { (context, block) -> delegate.dispatch(context, block) }
        held.clear()
    }
}
