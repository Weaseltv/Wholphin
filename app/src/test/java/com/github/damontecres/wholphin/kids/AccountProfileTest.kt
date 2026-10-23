package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.services.AccountAvatars
import com.github.damontecres.wholphin.services.AvatarAssignments
import com.github.damontecres.wholphin.services.PrimaryImageLookup
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.avatars.PictureStatus
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureViewModel
import com.github.damontecres.wholphin.ui.kids.ChooserPictureAccess
import com.github.damontecres.wholphin.ui.kids.KidProfilesViewModel
import com.github.damontecres.wholphin.ui.kids.PictureAccessState
import com.github.damontecres.wholphin.ui.kids.PictureLock
import com.github.damontecres.wholphin.ui.kids.PinEntered
import com.github.damontecres.wholphin.ui.preferences.PinFlowState
import com.github.damontecres.wholphin.ui.preferences.PinFlowStep
import com.github.damontecres.wholphin.ui.preferences.pinFlowStep
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Contract 11 (v1.4): the account PIN, forgot-PIN threshold, change-PIN steps and account avatars. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountProfileTest {
    private val serverId = testServer.id
    private val serverRepository = mockk<ServerRepository>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val dataStore = FakePreferencesDataStore()
    private val store = KidProtectionStore(dataStore)
    private val remote = FakeAccountPinRemote()

    /** The saved rows, as Room has them; the repository's writes change them */
    private val rows = mutableMapOf<UUID, JellyfinUser>()

    private val parent = adult("Justin", pin = "1234")

    private fun adult(
        name: String,
        pin: String? = null,
        token: String? = "token-$name",
    ) = testUser(name, pin, token).copy(policyState = PolicyState.ADULT)

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        rows[parent.id] = parent
        every { serverDao.getUser(serverId, any()) } answers { rows[secondArg()] }
        every { serverDao.getServer(serverId) } answers { JellyfinServerUsers(testServer, rows.values.toList()) }
        coEvery { serverRepository.updateCachedPin(any(), any()) } coAnswers {
            val user = firstArg<JellyfinUser>()
            rows[user.id] = rows.getValue(user.id).copy(pin = secondArg())
            true
        }
        coEvery { serverRepository.updateUserAuth(any(), any(), any()) } coAnswers {
            val user = firstArg<JellyfinUser>()
            rows[user.id] = rows.getValue(user.id).copy(pin = secondArg(), requireLogin = thirdArg())
        }
    }

    private fun TestScope.pins() =
        AccountPins(remote, serverDao, serverRepository, store, UnconfinedTestDispatcher(testScheduler)).also {
            it.clock = { Instant.parse("2026-10-22T12:00:00Z") }
        }

    // --- Sync decisions (pure) ---

    @Test
    fun `an account PIN replaces the cache`() {
        assertEquals(PinSync.Cache("ULLD"), AccountPinRules.sync(AccountPinValue("ULLD", "t"), "1234", false))
        assertEquals(PinSync.Cache("ULLD"), AccountPinRules.sync(AccountPinValue("ULLD", "t"), null, true))
        assertEquals(PinSync.Keep, AccountPinRules.sync(AccountPinValue("ULLD", "t"), "ULLD", false))
    }

    @Test
    fun `a cached PIN the account never had is uploaded once`() {
        assertEquals(PinSync.Upload("1234"), AccountPinRules.sync(AccountPinValue(null, null), "1234", false))
        assertEquals(PinSync.Upload("1234"), AccountPinRules.sync(AccountPinValue("", null), "1234", true))
        assertEquals(PinSync.Keep, AccountPinRules.sync(AccountPinValue(null, null), null, false))
    }

    @Test
    fun `a PIN removed elsewhere is dropped, except on a protected TV`() {
        assertEquals(PinSync.Cache(null), AccountPinRules.sync(AccountPinValue("", "t"), "1234", false))
        assertEquals(PinSync.Keep, AccountPinRules.sync(AccountPinValue("", "t"), "1234", true))
    }

    // --- Pulls ---

    @Test
    fun `a pull caches the account PIN`() =
        runTest {
            remote.accounts[parent.id] = AccountPinValue("ULLD", "2026-10-21T00:00:00Z")
            val fresh = pins().refresh(parent)
            assertEquals("ULLD", fresh.pin)
            assertEquals("ULLD", rows.getValue(parent.id).pin)
            assertTrue(remote.writes.isEmpty())
        }

    @Test
    fun `a pull uploads a cached PIN the account never had`() =
        runTest {
            remote.accounts[parent.id] = AccountPinValue(null, null)
            pins().refresh(parent)
            assertEquals(listOf(parent.id to "1234"), remote.writes.map { it.first to it.second })
            assertEquals("2026-10-22T12:00:00Z", remote.accounts.getValue(parent.id).updatedAt)
            assertEquals("1234", rows.getValue(parent.id).pin)
        }

    @Test
    fun `a migration never overwrites a PIN saved meanwhile, and adopts it`() =
        runTest {
            // The pull finds no PIN; the website saves one before the migration's own read
            remote.accounts[parent.id] = AccountPinValue(null, null)
            remote.beforeWrite = { remote.accounts[parent.id] = AccountPinValue("ULLD", "2026-10-22T11:59:00Z") }
            val fresh = pins().refresh(parent)
            assertTrue(remote.writes.isEmpty())
            assertEquals(AccountPinValue("ULLD", "2026-10-22T11:59:00Z"), remote.accounts.getValue(parent.id))
            assertEquals("ULLD", fresh.pin)
        }

    @Test
    fun `a migration stops at a PIN removed meanwhile too`() =
        runTest {
            remote.accounts[parent.id] = AccountPinValue(null, null)
            remote.beforeWrite = { remote.accounts[parent.id] = AccountPinValue("", "2026-10-22T11:59:00Z") }
            pins().refresh(parent)
            assertTrue(remote.writes.isEmpty())
            assertEquals(AccountPinValue("", "2026-10-22T11:59:00Z"), remote.accounts.getValue(parent.id))
            // Removed elsewhere: the cache follows (unprotected TV)
            assertEquals(null, rows.getValue(parent.id).pin)
        }

    @Test
    fun `an unreachable account keeps the cached PIN`() =
        runTest {
            remote.readFailure = IllegalStateException("offline")
            val fresh = pins().refresh(parent)
            assertEquals("1234", fresh.pin)
            assertTrue(remote.writes.isEmpty())
            coVerify(exactly = 0) { serverRepository.updateCachedPin(any(), any()) }
        }

    @Test
    fun `refreshAll pulls every saved profile with a token`() =
        runTest {
            val other = adult("Free", pin = null)
            val signedOut = adult("Gone", token = null)
            rows[other.id] = other
            rows[signedOut.id] = signedOut
            remote.accounts[parent.id] = AccountPinValue("ULLD", "t")
            remote.accounts[other.id] = AccountPinValue("RRLL", "t")
            remote.accounts[signedOut.id] = AccountPinValue("DDDD", "t")
            pins().refreshAll(serverId)
            assertEquals("ULLD", rows.getValue(parent.id).pin)
            assertEquals("RRLL", rows.getValue(other.id).pin)
            assertEquals(null, rows.getValue(signedOut.id).pin)
        }

    // --- Writes ---

    @Test
    fun `setting a PIN writes the account first, then the TV`() =
        runTest {
            assertEquals(AccountPinWrite.Saved, pins().set(parent, "UDLR", false))
            assertEquals("UDLR", remote.accounts.getValue(parent.id).pin)
            assertEquals("UDLR", rows.getValue(parent.id).pin)
        }

    @Test
    fun `a failed account write changes nothing on the TV`() =
        runTest {
            remote.writeFailure = IllegalStateException("offline")
            assertEquals(AccountPinWrite.Failed, pins().set(parent, "UDLR", false))
            assertEquals("1234", rows.getValue(parent.id).pin)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
        }

    @Test
    fun `a protected TV never removes a PIN, on the account or on the TV`() =
        runTest {
            store.markProtected(ProfileCeiling.Rating(10))
            assertEquals(AccountPinWrite.RemovalBlocked, pins().set(parent, null, false))
            assertTrue(remote.writes.isEmpty())
            assertEquals("1234", rows.getValue(parent.id).pin)
        }

    // --- Create-only PIN for another adult (contract 12.4, review round 1) ---

    private val dad = adult("Dad")

    @Test
    fun `create PIN writes a pinless account, then the TV`() =
        runTest {
            rows[dad.id] = dad
            assertEquals(PinCreate.CREATED, pins().createIfNoPin(dad, "UDLR") { true })
            assertEquals("UDLR", remote.accounts.getValue(dad.id).pin)
            assertEquals("UDLR", rows.getValue(dad.id).pin)
        }

    @Test
    fun `create PIN never overwrites a PIN the account has, and adopts it`() =
        runTest {
            rows[dad.id] = dad
            // Saved on the website while the screen waited: the TV's earlier pull didn't see it
            remote.accounts[dad.id] = AccountPinValue("LLRR", "t")
            assertEquals(PinCreate.ALREADY_SET, pins().createIfNoPin(dad, "UDLR") { true })
            assertEquals("LLRR", remote.accounts.getValue(dad.id).pin)
            assertTrue(remote.writes.isEmpty())
            assertEquals("LLRR", rows.getValue(dad.id).pin)
        }

    @Test
    fun `create PIN fails closed when the account can't be read`() =
        runTest {
            rows[dad.id] = dad
            remote.readFailure = IllegalStateException("offline")
            assertEquals(PinCreate.FAILED, pins().createIfNoPin(dad, "UDLR") { true })
            assertTrue(remote.writes.isEmpty())
            assertEquals(null, rows.getValue(dad.id).pin)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
        }

    @Test
    fun `create PIN checks eligibility again under the lock`() =
        runTest {
            // Removed from the TV, now a kid, signed out, or a PIN cached meanwhile
            assertEquals(PinCreate.NOT_ELIGIBLE, pins().createIfNoPin(dad, "UDLR") { true })
            rows[dad.id] = dad.copy(policyState = PolicyState.KID, maxParentalRating = 10)
            assertEquals(PinCreate.NOT_ELIGIBLE, pins().createIfNoPin(dad, "UDLR") { true })
            rows[dad.id] = dad.copy(accessToken = null)
            assertEquals(PinCreate.NOT_ELIGIBLE, pins().createIfNoPin(dad, "UDLR") { true })
            rows[dad.id] = dad.copy(pin = "LLRR")
            assertEquals(PinCreate.ALREADY_SET, pins().createIfNoPin(dad, "UDLR") { true })
            assertTrue(remote.writes.isEmpty())
        }

    @Test
    fun `create PIN writes nothing once the authorization ends`() =
        runTest {
            rows[dad.id] = dad
            assertEquals(PinCreate.REFUSED, pins().createIfNoPin(dad, "UDLR") { false })
            assertTrue(remote.writes.isEmpty())
            // Right before the write itself (after its fresh read)
            var allowed = true
            remote.beforeWrite = { allowed = false }
            assertEquals(PinCreate.REFUSED, pins().createIfNoPin(dad, "UDLR") { allowed })
            assertTrue(remote.writes.isEmpty())
            // Between the account write and the cache
            var checks = 0
            remote.beforeWrite = {}
            assertEquals(PinCreate.REFUSED, pins().createIfNoPin(dad, "UDLR") { ++checks <= 2 })
            assertEquals("UDLR", remote.accounts.getValue(dad.id).pin)
            assertEquals(null, rows.getValue(dad.id).pin)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
        }

    @Test
    fun `removing a PIN writes an empty account PIN with its time`() =
        runTest {
            assertEquals(AccountPinWrite.Saved, pins().set(parent, null, true))
            assertEquals(AccountPinValue("", "2026-10-22T12:00:00Z"), remote.accounts.getValue(parent.id))
            assertEquals(null, rows.getValue(parent.id).pin)
            assertTrue(rows.getValue(parent.id).requireLogin)
        }

    @Test
    fun `a login requirement alone is only this TV's`() =
        runTest {
            val pinless = adult("Free")
            rows[pinless.id] = pinless
            assertEquals(AccountPinWrite.Saved, pins().set(pinless, null, true))
            assertTrue(remote.writes.isEmpty())
            assertTrue(rows.getValue(pinless.id).requireLogin)
        }

    // --- The gate: a wrong entry pulls once and checks again ---

    @Test
    fun `a PIN reset on the website works straight away`() =
        runTest {
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            val gate =
                KidSessionGateImpl(serverRepository, serverDao, store, backgroundScope, StandardTestDispatcher(testScheduler), pins())
            remote.accounts[parent.id] = AccountPinValue("ULLD", "2026-10-22T11:00:00Z")
            assertEquals(PinCheckResult.Correct, gate.checkOwnPin(parent, "ULLD"))
            assertEquals("ULLD", rows.getValue(parent.id).pin)
            // The old PIN is wrong now, and counts towards the backoff
            assertEquals(PinCheckResult.Wrong(KidSessionGateImpl.MAX_ATTEMPTS - 1), gate.checkOwnPin(parent, "1234"))
        }

    @Test
    fun `an unreachable account leaves the cached PIN checking as before`() =
        runTest {
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            remote.readFailure = IllegalStateException("offline")
            val gate =
                KidSessionGateImpl(serverRepository, serverDao, store, backgroundScope, StandardTestDispatcher(testScheduler), pins())
            assertEquals(PinCheckResult.Correct, gate.checkOwnPin(parent, "1234"))
            assertEquals(PinCheckResult.Wrong(KidSessionGateImpl.MAX_ATTEMPTS - 1), gate.checkOwnPin(parent, "ULLD"))
        }

    // --- Forgot PIN and Change PIN ---

    @Test
    fun `the forgot-PIN hint shows after more than 3 wrong entries`() {
        assertEquals(3, PinRules.FORGOT_PIN_AFTER_WRONG)
        assertFalse(PinRules.offersForgotPin(0))
        assertFalse(PinRules.offersForgotPin(3))
        assertTrue(PinRules.offersForgotPin(4))
        assertTrue(PinRules.offersForgotPin(5))
    }

    @Test
    fun `change PIN goes current, new, new again, and a mismatch asks for the new PIN again`() {
        // The current PIN is checked by the gate; then the new one twice
        assertEquals(PinFlowStep.ConfirmNewPin("ULLD"), pinFlowStep(PinFlowState.ENTER_PIN, "ULLD", "1234", ""))
        assertEquals(PinFlowStep.Mismatch, pinFlowStep(PinFlowState.CONFIRM_PIN, "ULLR", "1234", "ULLD"))
        assertEquals(PinFlowStep.ConfirmNewPin("RRDD"), pinFlowStep(PinFlowState.ENTER_PIN, "RRDD", "1234", ""))
        assertEquals(PinFlowStep.SaveNewPin("RRDD"), pinFlowStep(PinFlowState.CONFIRM_PIN, "RRDD", "1234", "RRDD"))
        // Exactly 4 arrows
        assertEquals(PinFlowStep.NotFourPresses, pinFlowStep(PinFlowState.ENTER_PIN, "ULLDU", "1234", ""))
    }

    // --- Change picture from the chooser (review round 1) ---

    @Test
    fun `a pinless adult's picture needs a parent's PIN on a protected TV, and isn't offered without one`() {
        val pinless = adult("Free")
        val kid = testUser("Kid").copy(policyState = PolicyState.KID, maxParentalRating = 10)
        // Unprotected: open, as before
        assertEquals(PictureLock.Open, KidProfilesViewModel.pictureLock(pinless, ProfileKind.ADULT, false, listOf(parent)))
        // Protected: another adult's PIN
        assertEquals(
            PictureLock.ParentPin(listOf(parent)),
            KidProfilesViewModel.pictureLock(pinless, ProfileKind.ADULT, true, listOf(parent, adult("Other"))),
        )
        // Protected, no adult with a PIN: hidden
        assertEquals(PictureLock.Hidden, KidProfilesViewModel.pictureLock(pinless, ProfileKind.ADULT, true, listOf(adult("Other"))))
        // An unknown profile is not a kid: it needs the same unlock
        assertEquals(PictureLock.Hidden, KidProfilesViewModel.pictureLock(pinless, ProfileKind.UNKNOWN, true, emptyList()))
        // Its own PIN, and kids stay open
        assertEquals(PictureLock.OwnPin, KidProfilesViewModel.pictureLock(parent, ProfileKind.ADULT, true, emptyList()))
        assertEquals(PictureLock.Open, KidProfilesViewModel.pictureLock(kid, ProfileKind.KID, true, emptyList()))
    }

    @Test
    fun `the chooser menu hides Change picture where nobody could unlock it`() {
        val pinless = adult("Free")
        val gate = FakeKidSessionGate()
        val viewModel = KidProfilesViewModel(gate, pictureService, localAccountPins())
        assertTrue(viewModel.canChangePicture(pinless, listOf(parent, pinless), deviceProtected = true))
        assertFalse(viewModel.canChangePicture(pinless, listOf(pinless), deviceProtected = true))
        assertTrue(viewModel.canChangePicture(pinless, listOf(pinless), deviceProtected = false))
    }

    @Test
    fun `protection turning on while a pinless adult's picker is open drops it for a parent's PIN`() =
        runTest {
            val pinless = adult("Free")
            val gate = FakeKidSessionGate()
            val access = ChooserPictureAccess(pinless, ProfileKind.ADULT, gate, listOf(parent, pinless)) { it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { access.follow() }
            assertEquals(PictureAccessState.Unlocked(pinless, PinEntered.NONE), access.state.value)

            // A kid is added meanwhile: protection comes on
            gate.isDeviceProtected.value = true
            assertEquals(PictureAccessState.Locked(pinless, PictureLock.ParentPin(listOf(parent)), parent), access.state.value)
            assertFalse(access.mayChange())

            // The parent's PIN (through the gate, no unlock kept) opens it again
            assertEquals(PinCheckResult.Correct, access.submitPin("1234"))
            assertEquals(PictureAccessState.Unlocked(pinless, PinEntered.PARENT), access.state.value)
            assertTrue(access.mayChange())
            assertEquals(null, gate.parentUnlock.value)
        }

    @Test
    fun `protection turning on with nobody to unlock closes the picker`() =
        runTest {
            val pinless = adult("Free")
            val gate = FakeKidSessionGate()
            val access = ChooserPictureAccess(pinless, ProfileKind.ADULT, gate, listOf(pinless)) { it }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { access.follow() }
            assertTrue(access.state.value is PictureAccessState.Unlocked)
            gate.isDeviceProtected.value = true
            assertEquals(PictureAccessState.Closed, access.state.value)
        }

    @Test
    fun `every save checks again, and refuses what would now need a PIN`() =
        runTest {
            val pinless = adult("Free")
            val gate = FakeKidSessionGate()
            // Not following protection: only the save-time check stands between
            val access = ChooserPictureAccess(pinless, ProfileKind.ADULT, gate, listOf(parent, pinless)) { it }
            access.decideNow()
            assertTrue(access.mayChange())
            gate.isDeviceProtected.value = true
            assertFalse(access.mayChange())
            assertEquals(PictureAccessState.Closed, access.state.value)
        }

    @Test
    fun `a refused save-time check uploads nothing`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val viewModel = ProfilePictureViewModel(pictureService, mockk(relaxed = true))
                viewModel.start(ProfilePictureTarget(testServer.url, parent.id, "token", "Justin"), mayChange = { false })
                viewModel.pick(pictureService.avatars(isKid = false).first())
                assertTrue(uploader.uploads.isEmpty())
                assertEquals(PictureStatus.Refused, viewModel.state.value.status)
            } finally {
                Dispatchers.resetMain()
            }
        }

    // --- Account avatars ---

    private val uploader = FakeUserImageUploader()
    private val pictureService = ProfilePictureService(FakeAvatarCatalog(), uploader, FakeProfileImageCache())
    private val assignments =
        object : AvatarAssignments {
            val done = mutableSetOf<UUID>()

            override fun isDone(
                serverId: UUID,
                userId: UUID,
            ) = userId in done

            override fun markDone(
                serverId: UUID,
                userId: UUID,
            ) {
                done += userId
            }
        }

    private fun dto(
        maxParentalRating: Int?,
        imageTag: String?,
    ) = UserDto(
        id = UUID.randomUUID(),
        name = "Justin",
        hasPassword = true,
        hasConfiguredPassword = true,
        hasConfiguredEasyPassword = false,
        primaryImageTag = imageTag,
        policy = mockk<UserPolicy> { every { this@mockk.maxParentalRating } returns maxParentalRating },
    )

    @Test
    fun `only an adult without a picture chooses one on a fresh sign-in`() {
        val avatars = AccountAvatars(pictureService, { _, _, _ -> null }, assignments)
        assertTrue(avatars.needsFirstAvatar(dto(null, null)))
        assertFalse(avatars.needsFirstAvatar(dto(null, "tag")))
        assertFalse(avatars.needsFirstAvatar(dto(10, null)))
        assertFalse(avatars.needsFirstAvatar(null))
    }

    @Test
    fun `saved adults without a picture get one random adult avatar, once per user per TV`() =
        runTest {
            val withPicture = adult("Pic")
            val kid = testUser("Kid").copy(policyState = PolicyState.KID, maxParentalRating = 10)
            val offline = adult("Offline")
            val images = mapOf(parent.id to false, withPicture.id to true, kid.id to false)
            val lookup = PrimaryImageLookup { _, userId, _ -> images[userId] }
            val avatars = AccountAvatars(pictureService, lookup, assignments)
            avatars.assignMissing(testServer.url, listOf(parent, withPicture, kid, offline))
            assertEquals(listOf(parent.id), uploader.uploads.map { it.first.userId })
            assertFalse(
                uploader.uploads
                    .single()
                    .first.isKid,
            )
            // Never asked again, whatever happened; the server that couldn't answer is asked next time
            assertEquals(setOf(parent.id, withPicture.id), assignments.done)
            avatars.assignMissing(testServer.url, listOf(parent, withPicture))
            assertEquals(1, uploader.uploads.size)
        }
}

/** An account PIN store in memory; [readFailure] / [writeFailure] make the server unreachable */
class FakeAccountPinRemote : AccountPinRemote {
    val accounts = mutableMapOf<UUID, AccountPinValue>()
    val writes = mutableListOf<Triple<UUID, String?, String>>()
    var readFailure: Exception? = null
    var writeFailure: Exception? = null

    /** Runs inside a write, before its own fresh read (eg another device saving meanwhile) */
    var beforeWrite: () -> Unit = {}

    override suspend fun read(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
    ): AccountPinValue {
        readFailure?.let { throw it }
        return accounts[userId] ?: AccountPinValue(null, null)
    }

    override suspend fun write(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String?,
        updatedAt: String,
    ) {
        writeFailure?.let { throw it }
        beforeWrite()
        writes += Triple(userId, pin, updatedAt)
        accounts[userId] = AccountPinValue(pin.orEmpty(), updatedAt)
    }

    override suspend fun writeIfUnset(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String,
        updatedAt: String,
    ): AccountPinValue? {
        writeFailure?.let { throw it }
        beforeWrite()
        val found = accounts[userId] ?: AccountPinValue(null, null)
        if (found.everSet) return found
        writes += Triple(userId, pin, updatedAt)
        accounts[userId] = AccountPinValue(pin, updatedAt)
        return null
    }

    override suspend fun writeIfNoPin(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String,
        updatedAt: String,
        mayWrite: () -> Boolean,
    ): CreateOnlyWrite {
        readFailure?.let { throw it }
        writeFailure?.let { throw it }
        beforeWrite()
        val found = accounts[userId] ?: AccountPinValue(null, null)
        if (!found.pin.isNullOrBlank()) return CreateOnlyWrite.HasPin(found)
        if (!mayWrite()) return CreateOnlyWrite.Refused
        writes += Triple(userId, pin, updatedAt)
        accounts[userId] = AccountPinValue(pin, updatedAt)
        return CreateOnlyWrite.Written
    }
}
