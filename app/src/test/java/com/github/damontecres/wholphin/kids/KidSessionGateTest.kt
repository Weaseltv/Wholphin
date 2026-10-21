package com.github.damontecres.wholphin.kids

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.test.server
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * ProfileSwitchGate rules (plan 4.2), ParentUnlock and PIN backoff. Kid profiles only exist in the weaselfin flavor.
 */
class KidSessionGateTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var storeFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: KidProtectionStore

    private val serverRepository = mockk<ServerRepository>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val current = MutableStateFlow<CurrentUser?>(null)

    private val serverId = UUID.randomUUID()
    private var now = 1_000_000L

    private val parent = profile(1, PolicyState.ADULT, null, pin = "1234")
    private val pinlessAdult = profile(2, PolicyState.ADULT, null)
    private val kid10 = profile(3, PolicyState.KID, 10)
    private val kid14 = profile(4, PolicyState.KID, 14)
    private val unknown = profile(5, PolicyState.UNKNOWN, null)
    private val kid10WithPin = profile(6, PolicyState.KID, 10, pin = "5555")
    private val rows = listOf(parent, pinlessAdult, kid10, kid14, unknown, kid10WithPin)

    private fun profile(
        rowId: Int,
        state: PolicyState,
        rating: Int?,
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

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        storeFile = File(temporaryFolder.root, "key_value.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { storeFile })
        store = KidProtectionStore(dataStore)
        every { serverRepository.current } returns current
        rows.forEach { row -> every { serverDao.getUser(serverId, row.id) } returns row }
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server(serverId), rows)
        every { serverDao.countKidUsers() } answers { rows.count { it.policyState == PolicyState.KID } }
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun TestScope.gate(kidStore: KidProtectionStore = store) =
        KidSessionGateImpl(
            serverRepository,
            serverDao,
            kidStore,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        ).also { it.clock = { now } }

    private suspend fun protect(sessionCeiling: ProfileCeiling) {
        store.markProtected(sessionCeiling)
        store.setSessionCeiling(sessionCeiling)
    }

    @Test
    fun `unprotected device allows everything`() =
        runTest {
            val gate = gate()
            rows.forEach { assertEquals(GateDecision.Allow, gate.evaluate(it)) }
        }

    @Test
    fun `same or lower kid level switches freely, higher needs a parent`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            assertEquals(GateDecision.Allow, gate.evaluate(kid10))
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid14))
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(parent))
        }

    @Test
    fun `unknown is gated, as target and as session`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(unknown))

            // A protected device without a stored session ceiling gates everything
            store.markProtected(ProfileCeiling.Unlimited)
            dataStore.updateData { it.toMutablePreferences().apply { clear() } }
            store.markProtected(ProfileCeiling.Unknown)
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid10))
        }

    @Test
    fun `pinless adult is locked on a protected device on every path`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            assertEquals(GateDecision.LockedAdult, gate.evaluate(pinlessAdult))
            // Another adult's unlock opens it
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(pinlessAdult.rowId)))
            assertEquals(GateDecision.Allow, gate.evaluate(pinlessAdult))
            gate.clearParentUnlock()
            assertEquals(GateDecision.LockedAdult, gate.evaluate(pinlessAdult))
            // The gated restore used by cold start and intents refuses it before any switch
            current.value = null
            assertNull(gate.restoreGated(serverId, pinlessAdult.id))
            coVerify(exactly = 0) { serverRepository.changeUser(any(), any<JellyfinUser>(), any()) }
        }

    @Test
    fun `a parent unlock opens only the switch it was granted for`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId)))
            assertEquals(GateDecision.Allow, gate.evaluate(kid14))
            assertEquals(GateDecision.LockedAdult, gate.evaluate(pinlessAdult))
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(unknown))
            // Unbound, or bound to another action: opens no switch
            gate.verifyParentPin(parent, "1234")
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid14))
            gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid)
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid14))
        }

    @Test
    fun `a failed authorized switch leaves no unlock for another profile`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId))
            // The older kid's saved token has expired
            coEvery { serverRepository.changeUser(any(), any<JellyfinUser>(), any()) } throws InvalidStatusException(401)
            try {
                gate.switchTo(server(serverId), kid14)
                error("should have failed")
            } catch (_: InvalidStatusException) {
            }
            assertNull(gate.parentUnlock.value)
            // Quick Connect dismissed, then a different pinless adult picked: still locked
            assertEquals(GateDecision.LockedAdult, gate.evaluate(pinlessAdult))
        }

    @Test
    fun `only the add-kid flow under a parent unlock signs a kid in`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            val auth = AuthenticationResult(accessToken = "kid-token", serverId = serverId.toString())
            gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId))
            try {
                gate.signInKid("https://media.example", auth, "Emma")
                error("should have been refused")
            } catch (ex: ProfileGateException) {
                assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
            }
            coVerify(exactly = 0) { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `session ceiling persists across restart`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(kid14))

            // "Restart": a new store and gate over the same file
            val restartedStore = KidProtectionStore(dataStore)
            val restarted = gate(restartedStore)
            assertTrue(restartedStore.isProtected())
            assertEquals(ProfileCeiling.Rating(10), restartedStore.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, restarted.evaluate(kid14))
        }

    @Test
    fun `dismissed parent PIN changes nothing`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId)))
            assertNotNull(gate.parentUnlock.value)
            assertEquals(GateDecision.Allow, gate.evaluate(kid14))
            // Dismissed before switching
            gate.clearParentUnlock()
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid14))
        }

    @Test
    fun `parent unlock expires after five minutes`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId))
            now += KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds - 1
            assertEquals(GateDecision.Allow, gate.evaluate(kid14))
            now += 1
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(kid14))
            assertNull(gate.validUnlock())
        }

    @Test
    fun `a kid's PIN is never a parent PIN`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            assertTrue(gate.verifyParentPin(kid10WithPin, "5555") is PinCheckResult.Wrong)
            assertNull(gate.parentUnlock.value)
            // It still opens the kid's own profile, without an unlock
            assertEquals(PinCheckResult.Correct, gate.verifyProfilePin(kid10WithPin, "5555"))
            assertNull(gate.parentUnlock.value)
        }

    @Test
    fun `PIN backoff locks out after five wrong, doubles, and survives a restart`() =
        runTest {
            val gate = gate()
            repeat(4) { attempt ->
                val result = gate.verifyParentPin(parent, "0000")
                assertEquals(PinCheckResult.Wrong(4 - attempt), result)
            }
            val locked = gate.verifyParentPin(parent, "0000")
            assertEquals(PinCheckResult.LockedOut(now + 30_000L), locked)
            // Even the right PIN is refused while locked out, also after a restart
            assertTrue(gate.verifyParentPin(parent, "1234") is PinCheckResult.LockedOut)
            val restarted = gate(KidProtectionStore(dataStore))
            assertTrue(restarted.verifyParentPin(parent, "1234") is PinCheckResult.LockedOut)

            now += 30_000L
            repeat(5) { restarted.verifyParentPin(parent, "0000") }
            assertEquals(now + 60_000L, store.pinBackoff().lockedUntilMillis)

            now += 60_000L
            assertEquals(PinCheckResult.Correct, restarted.verifyParentPin(parent, "1234"))
            assertEquals(PinBackoff(), store.pinBackoff())
        }

    @Test
    fun `a kid's correct PIN never resets the parent-guess backoff`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            repeat(4) { gate.verifyParentPin(parent, "0000") }
            // A kid opening their own profile in between must not wipe the parent guesses
            assertEquals(PinCheckResult.Correct, gate.verifyProfilePin(kid10WithPin, "5555"))
            assertEquals(4, store.pinBackoff().failures)
            assertTrue(gate.verifyParentPin(parent, "0000") is PinCheckResult.LockedOut)
        }

    @Test
    fun `lockout durations double and cap at an hour`() {
        assertEquals(30_000L, KidSessionGateImpl.lockoutDuration(1))
        assertEquals(60_000L, KidSessionGateImpl.lockoutDuration(2))
        assertEquals(120_000L, KidSessionGateImpl.lockoutDuration(3))
        assertEquals(3_600_000L, KidSessionGateImpl.lockoutDuration(10))
    }

    @Test
    fun `kid session is true for kids and unknown, false for adults and no session`() =
        runTest {
            val gate = gate()
            assertFalse(gate.isKidSession.value)
            current.value = CurrentUser(server(serverId), kid10)
            assertTrue(gate.isKidSession.value)
            current.value = CurrentUser(server(serverId), unknown)
            assertTrue(gate.isKidSession.value)
            current.value = CurrentUser(server(serverId), parent)
            assertFalse(gate.isKidSession.value)
        }

    @Test
    fun `turning protection off needs a parent unlock and no kid rows`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            assertFalse(gate.turnOffProtection())
            gate.verifyParentPin(parent, "1234", UnlockPurpose.TurnOffProtection)
            // Kid rows remain
            assertFalse(gate.turnOffProtection())
            assertTrue(store.isProtected())

            every { serverDao.countKidUsers() } returns 0
            // An unlock granted for something else doesn't count
            gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid)
            assertFalse(gate.turnOffProtection())
            gate.verifyParentPin(parent, "1234", UnlockPurpose.TurnOffProtection)
            assertTrue(gate.turnOffProtection())
            assertFalse(store.isProtected())
            assertEquals(GateDecision.Allow, gate.evaluate(pinlessAdult))
        }

    @Test
    fun `removing kid rows never clears protection or the ceiling`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            every { serverDao.countKidUsers() } returns 0
            val gate = gate()
            assertTrue(store.isProtected())
            assertEquals(GateDecision.LockedAdult, gate.evaluate(pinlessAdult))
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(parent))
        }

    @Test
    fun `gated switch re-checks the refreshed policy before publishing`() =
        runTest {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val beforePublish = io.mockk.slot<suspend (JellyfinUser) -> Unit>()
            coEvery { serverRepository.changeUser(any(), any<JellyfinUser>(), capture(beforePublish)) } coAnswers {
                // The server says this kid is now Older kids
                beforePublish.captured.invoke(kid10.copy(maxParentalRating = 14))
                CurrentUser(server(serverId), kid10)
            }
            try {
                gate.switchTo(server(serverId), kid10)
                error("should have been refused")
            } catch (ex: ProfileGateException) {
                assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
            }
        }

    @Test
    fun `a kid sign-in whose parent unlock expires during its network calls publishes nothing`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid))
            // Started just before the five minutes are up
            now += KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds - 1
            val beforePublish = io.mockk.slot<suspend (JellyfinUser) -> Unit>()
            var published = false
            coEvery {
                serverRepository.changeUser(any<String>(), any(), any(), any(), any(), capture(beforePublish))
            } coAnswers {
                // The sign-in's network calls cross the expiry, then the fresh kid is about to be published
                now += 2
                beforePublish.captured.invoke(kid10)
                published = true
                CurrentUser(server(serverId), kid10)
            }
            val auth = AuthenticationResult(accessToken = "kid-token", serverId = serverId.toString())
            try {
                gate.signInKid("https://media.example", auth, "Emma")
                error("should have been refused")
            } catch (ex: ProfileGateException) {
                assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
            }
            assertFalse(published)
        }

    @Test
    fun `a kid sign-in whose unlock was replaced by another parent action publishes nothing`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid)
            val beforePublish = io.mockk.slot<suspend (JellyfinUser) -> Unit>()
            coEvery {
                serverRepository.changeUser(any<String>(), any(), any(), any(), any(), capture(beforePublish))
            } coAnswers {
                // Meanwhile the unlock was spent on something else
                now += 1
                gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(kid14.rowId))
                beforePublish.captured.invoke(kid10)
                CurrentUser(server(serverId), kid10)
            }
            val auth = AuthenticationResult(accessToken = "kid-token", serverId = serverId.toString())
            try {
                gate.signInKid("https://media.example", auth, "Emma")
                error("should have been refused")
            } catch (ex: ProfileGateException) {
                assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
            }
        }

    @Test
    fun `a kid sign-in under a still valid unlock publishes`() =
        runTest {
            protect(ProfileCeiling.Unlimited)
            val gate = gate()
            gate.verifyParentPin(parent, "1234", UnlockPurpose.AddKid)
            val beforePublish = io.mockk.slot<suspend (JellyfinUser) -> Unit>()
            coEvery {
                serverRepository.changeUser(any<String>(), any(), any(), any(), any(), capture(beforePublish))
            } coAnswers {
                beforePublish.captured.invoke(kid10)
                CurrentUser(server(serverId), kid10)
            }
            val auth = AuthenticationResult(accessToken = "kid-token", serverId = serverId.toString())

            assertEquals(kid10, gate.signInKid("https://media.example", auth, "Emma").user)
            assertNull(gate.parentUnlock.value)
        }
}
