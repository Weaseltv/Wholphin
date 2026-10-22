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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Review round 11: a switch whose fetched server user has no policy is gated, saved and published with the restriction
 * saved by the time it holds the session lock (plan 4.1, 4.2), never the older one its copy was taken with, and never
 * saves its copy's local preferences over newer ones. Real [ServerRepository] and [KidSessionGateImpl] on an in-memory
 * Room database, one [StandardTestDispatcher] scheduler, and a barrier holding the switch's profile fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MissingPolicyFallbackTest {
    private val dispatcher = StandardTestDispatcher()
    private val io = StandardTestDispatcher(dispatcher.scheduler)
    private val gateScope = CoroutineScope(StandardTestDispatcher(dispatcher.scheduler))
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: JellyfinServerDao
    private val jellyfin = mockk<Jellyfin>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val scopedApi = mockk<ApiClient>()
    private val userApi = mockk<UserApi>()
    private val systemApi = mockk<SystemApi>()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository
    private lateinit var gate: KidSessionGateImpl

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")

    private lateinit var parent: JellyfinUser
    private lateinit var kid: JellyfinUser
    private lateinit var sibling: JellyfinUser

    /** What the next profile fetch returns, once [fetched] is completed */
    private var nextDto: UserDto? = null
    private var fetched = CompletableDeferred(Unit)

    private fun dto(
        user: JellyfinUser,
        policy: UserPolicy?,
    ) = UserDto(
        id = user.id,
        name = user.name,
        serverName = "test server",
        hasPassword = true,
        hasConfiguredPassword = true,
        hasConfiguredEasyPassword = false,
        policy = policy,
    )

    private fun rating(max: Int?) = mockk<UserPolicy> { every { maxParentalRating } returns max }

    private fun save(user: JellyfinUser): JellyfinUser = dao.addOrUpdateUser(user)

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
        parent =
            save(
                JellyfinUser(
                    id = UUID.randomUUID(),
                    name = "parent",
                    serverId = serverId,
                    accessToken = "parent-token",
                    pin = "1111",
                    policyState = PolicyState.ADULT,
                ),
            )
        kid = save(kidRow("kid"))
        sibling = save(kidRow("sibling"))
        store = KidProtectionStore(FakePreferencesDataStore())
        val appPreferences = mockk<DataStore<AppPreferences>>()
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        every { jellyfin.createApi(any(), any(), any(), any(), any()) } returns scopedApi
        every { scopedApi.userApi } returns userApi
        every { scopedApi.systemApi } returns systemApi
        coEvery { userApi.getCurrentUser() } coAnswers {
            fetched.await()
            successResponse(nextDto!!)
        }
        coEvery { systemApi.getPublicSystemInfo() } returns
            successResponse(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"))
        repository = ServerRepository(context, jellyfin, dao, sharedApi, appPreferences, io, store)
        gate = KidSessionGateImpl(repository, dao, store, gateScope, io)
    }

    @After
    fun tearDown() {
        gateScope.cancel()
        if (::db.isInitialized) db.close()
        WholphinDispatchers.reset()
    }

    private fun kidRow(name: String) =
        JellyfinUser(
            id = UUID.randomUUID(),
            name = name,
            serverId = serverId,
            accessToken = "$name-token",
            policyState = PolicyState.KID,
            maxParentalRating = 14,
        )

    private fun saved(user: JellyfinUser): JellyfinUser = dao.getUser(serverId, user.id)!!

    /** The next fetch waits for the returned barrier */
    private fun holdFetch(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { fetched = it }

    private suspend fun parentUnlocksSwitchTo(user: JellyfinUser) {
        assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1111", UnlockPurpose.Switch(user.rowId)))
    }

    private suspend fun gateRefusal(block: suspend () -> Unit): GateDecision? =
        try {
            block()
            null
        } catch (ex: ProfileGateException) {
            ex.decision
        }

    @Test
    fun `a switch without a fetched policy is saved and published with the newer saved rating, and gates a sibling`() =
        runTest(dispatcher) {
            // A kid at 12 is the current session
            store.markProtected(ProfileCeiling.Rating(12))
            // The chooser's copy of the kid still says 14
            val chooserCopy = saved(kid)
            assertEquals(14, chooserCopy.maxParentalRating)

            // A parent authorizes the switch to the kid; its profile fetch comes back without a policy
            parentUnlocksSwitchTo(chooserCopy)
            nextDto = dto(kid, policy = null)
            val barrier = holdFetch()
            val switch = async { gate.switchTo(server, chooserCopy) }
            runCurrent()
            // Meanwhile Room records the kid's lowered rating (eg a policy refresh of that profile)
            dao.updateUserPolicy(kid.rowId, 10, PolicyState.KID)
            barrier.complete(Unit)
            val session = switch.await()

            assertEquals("the switch published its copy's older rating", ProfileCeiling.Rating(10), session.user.profileCeiling)
            assertEquals(
                ProfileCeiling.Rating(10),
                repository.current.value!!
                    .user.profileCeiling,
            )
            assertEquals("the switch saved its copy's older rating", 10, saved(kid).maxParentalRating)
            assertEquals(PolicyState.KID, saved(kid).policyState)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())

            // A sibling at 14 is above the kid's 10: it needs a parent's PIN
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(saved(sibling)))
            nextDto = dto(sibling, rating(14))
            fetched = CompletableDeferred(Unit)
            assertEquals(GateDecision.NeedsParentUnlock, gateRefusal { gate.switchTo(server, saved(sibling)) })
            assertEquals(kid.id, repository.currentUser!!.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a switch without a fetched policy uses the saved rating even if the chooser copy was older from the start`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Rating(12))
            val chooserCopy = saved(kid)
            dao.updateUserPolicy(kid.rowId, 10, PolicyState.KID)
            nextDto = dto(kid, policy = null)

            // 10 is within the session's 12, so the gate's final check (with the saved rating) allows it unaided
            parentUnlocksSwitchTo(chooserCopy)
            val session = gate.switchTo(server, chooserCopy)

            assertEquals(ProfileCeiling.Rating(10), session.user.profileCeiling)
            assertEquals(10, saved(kid).maxParentalRating)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a switch without a fetched policy whose saved row is unknown fails closed`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Rating(12))
            val chooserCopy = saved(kid)
            parentUnlocksSwitchTo(chooserCopy)
            nextDto = dto(kid, policy = null)
            val barrier = holdFetch()
            val switch = async { gate.switchTo(server, chooserCopy) }
            runCurrent()
            dao.updateUserPolicy(kid.rowId, null, PolicyState.UNKNOWN)
            barrier.complete(Unit)
            val session = switch.await()

            assertEquals(ProfileCeiling.Unknown, session.user.profileCeiling)
            assertEquals(ProfileKind.UNKNOWN, session.user.profileKind)
            assertEquals(PolicyState.UNKNOWN, saved(kid).policyState)
            assertNull(saved(kid).maxParentalRating)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(true, store.isProtected())
            // Nothing passes against an unknown ceiling without a parent
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(saved(sibling)))
        }

    @Test
    fun `a switch without a fetched policy whose row was removed meanwhile fails closed`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Rating(12))
            val chooserCopy = saved(kid)
            parentUnlocksSwitchTo(chooserCopy)
            nextDto = dto(kid, policy = null)
            val barrier = holdFetch()
            val switch = async { gate.switchTo(server, chooserCopy) }
            runCurrent()
            dao.deleteUser(serverId, kid.id)
            barrier.complete(Unit)
            val session = switch.await()

            assertEquals(ProfileCeiling.Unknown, session.user.profileCeiling)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
        }

    @Test
    fun `a switch with a fetched policy still takes the fetched rating`() =
        runTest(dispatcher) {
            store.markProtected(ProfileCeiling.Rating(12))
            val chooserCopy = saved(kid)
            parentUnlocksSwitchTo(chooserCopy)
            nextDto = dto(kid, rating(8))
            val barrier = holdFetch()
            val switch = async { gate.switchTo(server, chooserCopy) }
            runCurrent()
            dao.updateUserPolicy(kid.rowId, 10, PolicyState.KID)
            barrier.complete(Unit)
            val session = switch.await()

            assertEquals(ProfileCeiling.Rating(8), session.user.profileCeiling)
            assertEquals(8, saved(kid).maxParentalRating)
            assertEquals(ProfileCeiling.Rating(8), store.sessionCeiling())
        }

    @Test
    fun `subtitle and UI language saved while a switch is in flight survive it`() =
        runTest(dispatcher) {
            // Unprotected TV: the kid's session is current and entered
            nextDto = dto(kid, rating(14))
            val entered = repository.changeUser(server, saved(kid), authorize = {})
            // A switch to the same profile takes its copy, then fetches the profile
            val copy = saved(kid)
            val barrier = holdFetch()
            val switch = async { repository.changeUser(server, copy, authorize = {}) }
            runCurrent()
            // Meanwhile Settings saves a subtitle language and the UI language
            repository.updateUserAppPreferences(kid.rowId, copy.appPreferences.copy(preferredSubtitleLanguage = "spa"))
            requireNotNull(repository.updateCurrentUserLanguage(entered, "fr"))
            barrier.complete(Unit)
            val session = switch.await()

            assertEquals("the switch reverted the subtitle language", "spa", saved(kid).appPreferences.preferredSubtitleLanguage)
            assertEquals("the switch reverted the UI language", "fr", saved(kid).uiLanguage)
            assertEquals("spa", session.user.appPreferences.preferredSubtitleLanguage)
            assertEquals("fr", session.user.uiLanguage)
            assertEquals("fr", repository.currentUser!!.uiLanguage)
            // The switch's own sign-in fields are still its own
            assertEquals("kid-token", saved(kid).accessToken)
        }
}
