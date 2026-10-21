package com.github.damontecres.wholphin.kids

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppPreferencesSerializer
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * The session coordinator (review round 3): every change of the shared client's credentials and the session goes
 * through the session lock and one main-thread step, which also holds the last authorization check. Real
 * [ServerRepository] and [KidSessionGateImpl] with a real main thread, so the interleavings are the real ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KidSessionCoordinatorTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-main") }
    private val mainThread = mainExecutor.asCoroutineDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val scopedApi = mockk<ApiClient>()
    private val userApi = mockk<UserApi>()
    private val sharedUserApi = mockk<UserApi>()
    private val systemApi = mockk<SystemApi>()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")
    private var now = 1_000_000L

    /** Every token the shared client was given, in order */
    private val tokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** Runs inside the shared client's update, before the token is recorded */
    @Volatile
    private var onUpdate: (String?) -> Unit = {}

    private fun profile(
        rowId: Int,
        state: PolicyState,
        rating: Int? = null,
        pin: String? = null,
    ) = JellyfinUser(
        rowId = rowId,
        id = UUID.randomUUID(),
        name = "user$rowId",
        serverId = serverId,
        accessToken = "token$rowId",
        pin = pin,
        policyState = state,
        maxParentalRating = rating,
    )

    private val parent = profile(1, PolicyState.ADULT, pin = "1234")
    private val kid = profile(3, PolicyState.KID, 10)
    private val olderKid = profile(4, PolicyState.KID, 14)
    private val teen = profile(5, PolicyState.KID, 17)

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
        Dispatchers.setMain(mainThread)
        store =
            KidProtectionStore(
                PreferenceDataStoreFactory.create(
                    scope = ioScope,
                    produceFile = { File(temporaryFolder.root, "key_value.preferences_pb") },
                ),
            )
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
        // The restore shortcut's policy refresh is offline: the cached policy is kept
        every { sharedApi.userApi } returns sharedUserApi
        coEvery { sharedUserApi.getCurrentUser() } throws IOException("offline")
        coEvery { systemApi.getPublicSystemInfo() } returns
            successResponse(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"))
        every { sharedApi.update(any(), any(), any(), any()) } answers {
            val token = secondArg<String?>()
            onUpdate(token)
            tokens += token
        }
        every { serverDao.addOrUpdateServer(any()) } just Runs
        every { serverDao.addOrUpdateUser(any()) } answers { firstArg() }
        every { serverDao.updateUserPolicy(any(), any(), any()) } just Runs
        every { serverDao.getUser(any(), any()) } returns null
        listOf(parent, kid, olderKid, teen).forEach { every { serverDao.getUser(serverId, it.id) } returns it }
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server, listOf(parent, kid, olderKid, teen))
        repository = ServerRepository(context, jellyfin, serverDao, sharedApi, appPreferences, Dispatchers.IO, store)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    /** On a real-time [scope], so the unlock's expiry job only runs when [now] says so (never in these tests) */
    private fun gate(scope: CoroutineScope) =
        KidSessionGateImpl(repository, serverDao, store, scope, Dispatchers.IO).also { it.clock = { now } }

    private suspend fun protect(sessionCeiling: ProfileCeiling) {
        store.markProtected(sessionCeiling)
        store.setSessionCeiling(sessionCeiling)
    }

    private suspend fun switchTo(user: JellyfinUser) {
        coEvery { userApi.getCurrentUser() } returns successResponse(dto(user))
        repository.changeUser(server, user)
    }

    /**
     * The next time the shared client is given [token], a switch to [other] runs first, as if it had been
     * published by another thread right then. It gets [WAIT_MS] to finish; one blocked by the session lock
     * finishes afterwards. Returns the switch, to wait for.
     */
    private fun switchWhenGiven(
        token: String?,
        other: JellyfinUser,
    ): Deferred<Unit> {
        val started = CompletableDeferred<Deferred<Unit>>()
        coEvery { userApi.getCurrentUser() } returns successResponse(dto(other))
        onUpdate = { given ->
            if (given == token && !started.isCompleted) {
                val switch = ioScope.async { repository.changeUser(server, other).let { } }
                started.complete(switch)
                runBlocking { withTimeoutOrNull(WAIT_MS) { switch.join() } }
            }
        }
        return ioScope.async { started.await().await() }
    }

    @Test
    fun `restore shortcut never gives the shared client a superseded profile's token`() =
        runBlocking {
            switchTo(parent)
            // While the parent's session is restored (an intent, app resume), the chooser publishes the kid
            val kidSwitch = switchWhenGiven(parent.accessToken, kid)

            repository.restoreSession(serverId, parent.id)
            kidSwitch.await()

            assertEquals(kid.id, repository.currentUser?.id)
            assertEquals("requests must authenticate as the session", kid.accessToken, tokens.last())
        }

    @Test
    fun `chooser teardown never clears the token of a session published meanwhile`() =
        runBlocking {
            switchTo(parent)
            val kidSwitch = switchWhenGiven(null, kid)

            repository.endSessionForChooser()
            kidSwitch.await()

            assertEquals(kid.id, repository.currentUser?.id)
            assertEquals(kid.accessToken, tokens.last())
        }

    @Test
    fun `gated restore of the current profile never switches back ungated after another session was published`() =
        runBlocking {
            protect(ProfileCeiling.Unlimited)
            switchTo(parent)
            val gate = gate(ioScope)
            // The kid is published while the parent's restore reads the saved rows (or re-applies its token)
            coEvery { userApi.getCurrentUser() } returnsMany listOf(successResponse(dto(kid)), successResponse(dto(parent)))
            var armed = true
            every { serverDao.getServer(serverId) } answers {
                if (armed) {
                    armed = false
                    runBlocking { repository.changeUser(server, kid) }
                }
                JellyfinServerUsers(server, listOf(parent, kid, olderKid, teen))
            }
            onUpdate = { given ->
                if (given == parent.accessToken && armed) {
                    armed = false
                    val switch = ioScope.async { repository.changeUser(server, kid) }
                    runBlocking { withTimeoutOrNull(WAIT_MS) { switch.join() } }
                }
            }

            gate.restoreGated(serverId, parent.id)
            // Whichever way it interleaved, the kid's switch is done
            withTimeoutOrNull(5_000) { while (repository.currentUser?.id != kid.id) delay(10) }

            assertEquals("the parent's PIN profile was restored without the gate", kid.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertEquals(kid.accessToken, tokens.last())
        }

    private fun kidSignIn(): AuthenticationResult =
        AuthenticationResult(user = dto(kid), serverId = serverId.toServerString(), accessToken = kid.accessToken)

    @Test
    fun `a kid sign-in whose unlock expires while it is persisted publishes nothing`() =
        runBlocking {
            protect(ProfileCeiling.Unlimited)
            val gate = gate(ioScope)
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid))
            coEvery { userApi.getCurrentUser() } returns successResponse(dto(kid))
            // Past every check before publication: the five minutes run out while the kid row is saved
            every { serverDao.addOrUpdateUser(any()) } answers {
                now += KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds
                firstArg()
            }

            assertRefused { gate.signInKid(server.url, kidSignIn(), "Emma") }

            assertNull(repository.current.value)
            assertFalse(kid.accessToken in tokens)
            // The journalled switch was aborted with its id: the previous ceiling, not unknown
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    @Test
    fun `a kid sign-in whose unlock expires while publication waits for the main thread publishes nothing`() =
        runBlocking {
            protect(ProfileCeiling.Unlimited)
            val gate = gate(ioScope)
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid))
            coEvery { userApi.getCurrentUser() } returns successResponse(dto(kid))
            // The five minutes run out while the publishing step is dispatched to the main thread
            Dispatchers.setMain(
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        now += KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds
                        mainThread.dispatch(context, block)
                    }
                },
            )

            assertRefused { gate.signInKid(server.url, kidSignIn(), "Emma") }

            assertNull(repository.current.value)
            assertFalse(kid.accessToken in tokens)
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
            error("should have been refused")
        } catch (ex: ProfileGateException) {
            assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
        }
    }

    @Test
    fun `a superseded switch that ends late never clears the unlock granted for the newer selection`() =
        runBlocking {
            protect(ProfileCeiling.Rating(10))
            val gate = gate(ioScope)
            gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId))
            // The older kid's switch is still on the network (its cancellation lands late)
            val inNetwork = CompletableDeferred<Unit>()
            val network = CompletableDeferred<Unit>()
            coEvery { userApi.getCurrentUser() } coAnswers {
                inNetwork.complete(Unit)
                withContext(NonCancellable) { network.await() }
                successResponse(dto(olderKid))
            }
            val older = launch(Dispatchers.IO) { runCatching { gate.switchTo(server, olderKid) } }
            inNetwork.await()

            // The newer selection: a new pick (no earlier unlock), then the parent's PIN for the teen
            gate.clearParentUnlock()
            now += 1
            gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(teen.rowId))
            older.cancel()
            network.complete(Unit)
            older.join()

            assertEquals(UnlockPurpose.Switch(teen.rowId), gate.parentUnlock.value?.purpose)
        }

    @Test
    fun `an escalation whose unlock expires while it is persisted publishes nothing`() =
        runBlocking {
            protect(ProfileCeiling.Rating(10))
            switchTo(kid)
            val gate = gate(ioScope)
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)))
            coEvery { userApi.getCurrentUser() } returns successResponse(dto(olderKid))
            // Past the fresh gate check: the five minutes run out while the older kid's row is saved
            every { serverDao.addOrUpdateUser(any()) } answers {
                now += KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds
                firstArg()
            }

            assertRefused { gate.switchTo(server, olderKid) }

            assertEquals(kid.id, repository.currentUser?.id)
            assertFalse(olderKid.accessToken in tokens)
            // The journalled switch was aborted: the kid's ceiling, not the older kid's (nor unknown)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a user update lands in the coordinator's main-thread step`() =
        runBlocking {
            switchTo(kid)
            val threads = Collections.synchronizedList(mutableListOf<String>())
            val seen = CompletableDeferred<Unit>()
            val collector =
                ioScope.launch(Dispatchers.Unconfined) {
                    repository.currentUserDtoFlow.collect {
                        if (it?.name == "renamed") {
                            // Without the coroutine debug suffix
                            threads += Thread.currentThread().name.substringBefore(" @")
                            seen.complete(Unit)
                        }
                    }
                }

            // The websocket's user-updated message, off the main thread
            withContext(Dispatchers.IO) { repository.updateUserDto(dto(kid).copy(name = "renamed")) }
            seen.await()
            collector.cancel()

            assertEquals(listOf("test-main"), threads)
        }

    private companion object {
        const val WAIT_MS = 500L
    }
}
