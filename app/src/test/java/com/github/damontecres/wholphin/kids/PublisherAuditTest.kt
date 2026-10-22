package com.github.damontecres.wholphin.kids

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.preferences.LocaleChoiceViewModel
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
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.Locale
import java.util.UUID

/**
 * Review round 9: only a gated switch publishes a profile. A locale change is an update of the session it was made
 * in, so it can't republish that profile once another session replaced it; a switch without its publication-time
 * authorization, or the upstream restore, publishes nothing on a kid-profiles build. Real [ServerRepository] and
 * [LocaleChoiceViewModel] on one [StandardTestDispatcher] with an in-memory store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublisherAuditTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>()
    private val serverDao = mockk<JellyfinServerDao>(relaxUnitFun = true)
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val systemApi = mockk<SystemApi>()
    private val keyValue = FakePreferencesDataStore()
    private val appPreferences = mockk<DataStore<AppPreferences>>()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")

    private fun profile(
        rowId: Int,
        state: PolicyState,
        rating: Int? = null,
    ) = JellyfinUser(
        rowId = rowId,
        id = UUID.randomUUID(),
        name = "user$rowId",
        serverId = serverId,
        accessToken = "token$rowId",
        policyState = state,
        maxParentalRating = rating,
    )

    private val adult = profile(1, PolicyState.ADULT)
    private val kid = profile(3, PolicyState.KID, 10)
    private val teen = profile(5, PolicyState.KID, 17)
    private val rows = listOf(adult, kid, teen)

    /** A parent whose saved row has a PIN */
    private val pinned = profile(6, PolicyState.ADULT).copy(pin = "1234")

    /** Tokens given to the shared client, in order */
    private val tokens = mutableListOf<String?>()

    /** Profiles the scoped clients fetched: a publisher's network work */
    private val fetched = mutableListOf<UUID>()

    private fun dto(user: JellyfinUser) =
        UserDto(
            id = user.id,
            name = user.name,
            serverName = "test server",
            hasPassword = true,
            hasConfiguredPassword = true,
            hasConfiguredEasyPassword = false,
            policy = mockk<UserPolicy> { every { maxParentalRating } returns user.maxParentalRating },
        )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        store = KidProtectionStore(keyValue)
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        rows.forEach { row ->
            val scopedApi = mockk<ApiClient>()
            val userApi = mockk<UserApi>()
            every { jellyfin.createApi(any(), row.accessToken, any(), any(), any()) } returns scopedApi
            every { scopedApi.userApi } returns userApi
            every { scopedApi.systemApi } returns systemApi
            coEvery { userApi.getCurrentUser() } answers {
                fetched += row.id
                successResponse(dto(row))
            }
        }
        coEvery { systemApi.getPublicSystemInfo() } returns
            successResponse(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"))
        every { sharedApi.update(any(), any(), any(), any()) } answers { tokens += secondArg<String?>() }
        every { serverDao.addOrUpdateServer(any()) } just Runs
        every { serverDao.addOrUpdateUser(any()) } answers { firstArg() }
        every { serverDao.updateUserPolicy(any(), any(), any()) } just Runs
        every { serverDao.getUser(any(), any()) } returns null
        (rows + pinned).forEach { every { serverDao.getUser(serverId, it.id) } returns it }
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server, rows)
        every { serverDao.getUserFlow(any(), any()) } returns flowOf(null)
        repository = ServerRepository(context, jellyfin, serverDao, sharedApi, appPreferences, dispatcher, store)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private suspend fun protect(sessionCeiling: ProfileCeiling) {
        store.markProtected(sessionCeiling)
        store.setSessionCeiling(sessionCeiling)
    }

    /** A switch that passed the gate, as [KidSessionGateImpl.switchTo] publishes it */
    private suspend fun switchTo(user: JellyfinUser) = repository.changeUser(server, user, beforePublish = {}, authorize = {})

    private fun localeDialog() = LocaleChoiceViewModel(context, repository)

    private suspend fun assertRefused(block: suspend () -> Unit) {
        val refused =
            try {
                block()
                false
            } catch (ex: IllegalStateException) {
                true
            }
        assertTrue("should have been refused", refused)
    }

    @Test
    fun `a locale change made in the adult's session never republishes the adult over a newer kid session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Unlimited)
            switchTo(adult)
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
            // The adult's Settings is open
            val dialog = localeDialog()
            runCurrent()

            // A permitted switch to the kid is under way: past its network work, it holds the session lock
            val gateCheck = CompletableDeferred<Unit>()
            val kidSwitch =
                launch { repository.changeUser(server, kid, beforePublish = { gateCheck.await() }, authorize = {}) }
            runCurrent()

            // The adult picks a language; the kid's switch then completes
            dialog.changeLocale(Locale.FRENCH)
            runCurrent()
            gateCheck.complete(Unit)
            runCurrent()
            kidSwitch.join()
            runCurrent()

            assertEquals("the adult was republished over the kid", kid.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertEquals("requests must authenticate as the kid", kid.accessToken, tokens.last())
            assertNull("the adult's language never lands on the kid", repository.currentUser?.uiLanguage)
        }

    @Test
    fun `a locale change on the current session applies to it, without a switch`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            switchTo(kid)
            val published = repository.current.value!!
            val tokensBefore = tokens.toList()
            fetched.clear()
            val dialog = localeDialog()
            runCurrent()

            dialog.changeLocale(Locale.FRENCH)
            runCurrent()

            assertEquals(kid.id, repository.currentUser?.id)
            assertEquals("fr", repository.currentUser?.uiLanguage)
            assertEquals(published.user.accessToken, repository.currentUser?.accessToken)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertEquals("the session's credentials are left alone", tokensBefore, tokens)
            assertTrue("an update fetches no profile", fetched.isEmpty())
            verify(exactly = 1) { serverDao.updateUserLanguage(kid.rowId, "fr") }
        }

    @Test
    fun `a language update refuses a session that was replaced or changed since it was read`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Unlimited)
            val adultSession = switchTo(adult)
            switchTo(kid)

            assertNull(repository.updateCurrentUserLanguage(adultSession, "fr"))
            assertEquals(kid.id, repository.currentUser?.id)
            assertNull(repository.currentUser?.uiLanguage)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            verify(exactly = 0) { serverDao.updateUserLanguage(any(), any()) }

            // A session changed since it was read (here by another language update) is refused too
            val kidSession = repository.current.value!!
            val updated = repository.updateCurrentUserLanguage(kidSession, "fr")
            assertEquals("fr", updated?.user?.uiLanguage)
            assertNull(repository.updateCurrentUserLanguage(kidSession, "de"))
            assertEquals(updated, repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a language update never lands on a session nobody entered`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val unentered = repository.changeUser(server, teen, authorize = {}, entryOwner = Any())

            assertNull(repository.updateCurrentUserLanguage(unentered, "fr"))
            assertNull(repository.currentUser?.uiLanguage)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
        }

    @Test
    fun `a switch without its publication-time authorization publishes nothing`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))

            assertRefused { repository.changeUser(server, adult) }
            assertRefused { repository.changeUser(server, adult, beforePublish = {}) }

            assertNull(repository.current.value)
            assertTrue(tokens.none { it == adult.accessToken })
            assertTrue("refused before any request", fetched.isEmpty())
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a sign-in without its publication-time authorization publishes nothing`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val auth = AuthenticationResult(user = dto(adult), serverId = serverId.toServerString(), accessToken = adult.accessToken)

            assertRefused { repository.changeUser(server.url, auth, null) }

            assertNull(repository.current.value)
            assertTrue(tokens.none { it == adult.accessToken })
            assertTrue("refused before any request", fetched.isEmpty())
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `the upstream restore never switches to another profile on a kid-profiles build`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))

            assertNull(repository.restoreSession(serverId, teen.id))

            assertNull(repository.current.value)
            assertTrue(fetched.isEmpty())
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `the upstream restore still re-applies the current session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val session = switchTo(kid)
            tokens.clear()

            assertEquals(session, repository.restoreSession(serverId, kid.id))
            assertEquals(listOf(kid.accessToken), tokens)
            assertFalse(store.sessionCeiling() == ProfileCeiling.Unknown)
        }

    @Test
    fun `a PIN set from an older copy of the current profile keeps the session's policy`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            switchTo(kid)
            // The Settings screen still holds the kid's row from before the server lowered it from 14 to 10
            val older = kid.copy(maxParentalRating = 14)

            repository.updateUserAuth(older, "4321", false)

            val session = repository.currentUser!!
            assertEquals(kid.id, session.id)
            assertEquals("4321", session.pin)
            assertEquals("the older policy replaced the session's", 10, session.maxParentalRating)
            verify(exactly = 0) { serverDao.addOrUpdateUser(match { it.maxParentalRating == 14 }) }
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a copy from before a PIN was set can't remove that PIN on a protected device`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))

            assertRefused { repository.updateUserAuth(pinned.copy(pin = null), null, false) }

            verify(exactly = 0) { serverDao.addOrUpdateUser(any()) }
            verify(exactly = 0) { serverDao.updateUserAuth(any(), any(), any()) }
        }
}
