package com.github.damontecres.wholphin.kids

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppPreferencesSerializer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * ServerRepository's kid-profiles duties (plan 4.1, 4.2): policy columns and kid protection persisted before a
 * session is published, the session ceiling written on every completed switch, nothing published when the gate
 * aborts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidServerRepositoryTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context = mockk<Context>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val scopedApi = mockk<ApiClient>()
    private val userApi = mockk<UserApi>()
    private val systemApi = mockk<SystemApi>()
    private lateinit var keyValue: DataStore<Preferences>
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
        keyValue =
            PreferenceDataStoreFactory.create(
                scope = ioScope,
                produceFile = { File(temporaryFolder.root, "key_value.preferences_pb") },
            )
        store = KidProtectionStore(keyValue)
        val appPreferences =
            DataStoreFactory.create(
                serializer = AppPreferencesSerializer(),
                produceFile = { File(temporaryFolder.root, "app.pb") },
                scope = ioScope,
                corruptionHandler = ReplaceFileCorruptionHandler { AppPreferences.getDefaultInstance() },
            )
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
        ioScope.cancel()
        WholphinDispatchers.reset()
    }

    @Test
    fun `kid switch persists policy, protection and ceiling before publishing`() =
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
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.current.drop(1).collect {
                    events += "current protected=${store.isProtected()} ceiling=${store.sessionCeiling()}"
                }
            }

            val saved = slot<JellyfinUser>()
            every { serverDao.addOrUpdateUser(capture(saved)) } answers {
                events += "room"
                firstArg()
            }
            repository.changeUser(server, kid)

            assertEquals(PolicyState.KID, saved.captured.policyState)
            assertEquals(10, saved.captured.maxParentalRating)
            assertEquals(
                listOf("room", "dto", "current protected=true ceiling=${ProfileCeiling.Rating(10)}"),
                events,
            )
        }

    @Test
    fun `adult switch stores an unlimited ceiling and does not protect`() =
        runTest {
            val adult = row(1, pin = "1234")
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(adult.id, null))

            repository.changeUser(server, adult)

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
            repository.changeUser(server, adult)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            repository.changeUser(server, kid)

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
                repository.changeUser(server, kid) { throw ProfileGateException(GateDecision.NeedsParentUnlock) }
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
            repository.changeUser(server, kid)

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

            repository.changeUser(server.url, auth, null)

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

            repository.changeUser(server.url, auth, null)

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

            repository.changeUser(server.url, auth, kid)

            assertEquals(adultId, saved.captured.id)
            assertEquals(0, saved.captured.rowId)
            assertNull(saved.captured.pin)
            assertEquals("adult-token", saved.captured.accessToken)
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
        }

    @Test
    fun `current user flow carries the policy columns`() =
        runTest {
            val kid = row(3)
            coEvery { userApi.getCurrentUser() } returns successResponse(userDto(kid.id, 10))
            val current: CurrentUser = repository.changeUser(server, kid)
            assertEquals(PolicyState.KID, current.user.policyState)
            assertEquals(ProfileKind.KID, current.user.profileKind)
            assertEquals(ProfileCeiling.Rating(10), current.user.profileCeiling)
        }
}
