package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.kids.AddKidError
import com.github.damontecres.wholphin.ui.kids.AddKidStep
import com.github.damontecres.wholphin.ui.kids.AddKidViewModel
import com.github.damontecres.wholphin.ui.kids.AdultPinOutcome
import com.github.damontecres.wholphin.ui.kids.NameError
import com.github.damontecres.wholphin.ui.kids.PinError
import com.github.damontecres.wholphin.ui.kids.Retry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AddKidViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = FakeKidProfilesApi()
    private val gate = FakeKidSessionGate()
    private val quickConnect = FakeKidQuickConnect()
    private val uploader = FakeUserImageUploader()
    private val cache = FakeProfileImageCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val navigation = mockk<SetupNavigationManager>(relaxed = true)
    private val accountPins = localAccountPins(serverRepository)
    private lateinit var viewModel: AddKidViewModel

    private val mom = testUser("Mom", pin = "1234")
    private val step get() = viewModel.step.value

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel =
            AddKidViewModel(
                api = api,
                gate = gate,
                quickConnect = quickConnect,
                pictures = ProfilePictureService(FakeAvatarCatalog(), uploader, cache),
                serverRepository = serverRepository,
                setupNavigationManager = navigation,
                accountPins = accountPins,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun TestScope.unlockMom(users: List<JellyfinUser> = listOf(mom)) {
        viewModel.start(testServer, users)
        advanceUntilIdle()
        if (step is AddKidStep.PickParent) {
            viewModel.selectParent(mom)
            advanceUntilIdle()
        }
        viewModel.submitPin("1234")
        advanceUntilIdle()
    }

    private suspend fun TestScope.fillForm(
        name: String = "Emma",
        level: KidLevel? = KidLevel.KIDS,
    ) {
        unlockMom()
        viewModel.createNew()
        viewModel.updateForm(name = name, level = level)
        viewModel.submitForm()
        advanceUntilIdle()
    }

    private fun assertSignedIn(displayName: String) {
        assertEquals(AddKidStep.Done, step)
        assertEquals(displayName, gate.signedIn.single().second)
        assertEquals(
            "kid-token",
            gate.signedIn
                .single()
                .first.accessToken,
        )
        val destination = slot<SetupDestination>()
        verify { navigation.navigateTo(capture(destination)) }
        assertTrue(destination.captured is SetupDestination.AppContent)
        assertEquals(null, gate.parentUnlock.value)
    }

    @Test
    fun `parents are saved signed-in adults only`() {
        val kid = testUser("Kiddo")
        gate.kinds[kid.id] = ProfileKind.KID
        val unknown = testUser("Unknown")
        gate.kinds[unknown.id] = ProfileKind.UNKNOWN
        val noToken = testUser("Gone", token = null)
        val dad = testUser("Dad", pin = "9")
        assertEquals(listOf(mom, dad), AddKidViewModel.parentsOf(listOf(mom, kid, unknown, noToken, dad), gate))
    }

    @Test
    fun `two parents ask who is adding`() =
        test {
            val dad = testUser("Dad", pin = "9")
            viewModel.start(testServer, listOf(mom, dad))
            advanceUntilIdle()
            assertEquals(AddKidStep.PickParent(listOf(mom, dad)), step)
            viewModel.selectParent(dad)
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(dad), step)
        }

    @Test
    fun `wrong pin and lockout come back from the gate`() =
        test {
            viewModel.start(testServer, listOf(mom))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            gate.pinResults += PinCheckResult.Wrong(2)
            viewModel.submitPin("0000")
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom, PinError.Wrong(2)), step)
            gate.pinResults += PinCheckResult.LockedOut(42L)
            viewModel.submitPin("0000")
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom, PinError.LockedOut(42L)), step)
            assertTrue(api.listCalls.isEmpty())
        }

    @Test
    fun `correct pin with no other adults offers creating or adding an existing kid`() =
        test {
            unlockMom()
            assertEquals(AddKidStep.ChooseHow, step)
            assertTrue(api.listCalls.isEmpty())
            viewModel.createNew()
            assertEquals(AddKidStep.Form(), step)
        }

    @Test
    fun `pinless parent sets a first pin only on an unprotected tv`() =
        test {
            val dad = testUser("Dad")
            viewModel.start(testServer, listOf(dad))
            advanceUntilIdle()
            assertEquals(AddKidStep.CreateFirstPin(dad), step)
            viewModel.createFirstPin("LRDU")
            advanceUntilIdle()
            coVerify { serverRepository.updateUserAuth(dad, "LRDU", false) }
            assertEquals(
                "LRDU",
                gate.verified
                    .single()
                    .first.pin,
            )
            assertEquals(AddKidStep.ChooseHow, step)
        }

    @Test
    fun `a first pin that isn't four arrow presses is not saved`() =
        test {
            val dad = testUser("Dad")
            viewModel.start(testServer, listOf(dad))
            advanceUntilIdle()
            viewModel.createFirstPin("43210")
            viewModel.createFirstPin("432")
            viewModel.createFirstPin("4321")
            advanceUntilIdle()
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
            assertEquals(AddKidStep.CreateFirstPin(dad), step)
        }

    @Test
    fun `back during a pending correct pin check leaves no add-kid unlock`() =
        test {
            viewModel.start(testServer, listOf(mom))
            advanceUntilIdle()
            val storage = CompletableDeferred<Unit>()
            gate.pendingCheck = storage
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(listOf(mom to "1234"), gate.verified)
            // Back while Room/DataStore is still pending
            viewModel.cancel()
            storage.complete(Unit)
            advanceUntilIdle()
            // The check finished (and counted) but granted nothing, and the flow went nowhere
            assertNull(gate.parentUnlock.value)
            assertNull(gate.validUnlockFor(mom.rowId, UnlockPurpose.AddKid))
            assertTrue(api.listCalls.isEmpty())
        }

    @Test
    fun `a newer pin check replaces a pending one, which then grants nothing`() =
        test {
            viewModel.start(testServer, listOf(mom))
            advanceUntilIdle()
            val storage = CompletableDeferred<Unit>()
            gate.pendingCheck = storage
            viewModel.submitPin("1234")
            runCurrent()
            gate.pendingCheck = null
            gate.pinResults += PinCheckResult.Wrong(2)
            viewModel.submitPin("0000")
            advanceUntilIdle()
            // The first, correct check ends after the second, wrong one: it is no longer current
            gate.pinResults += PinCheckResult.Correct
            storage.complete(Unit)
            advanceUntilIdle()
            assertNull(gate.parentUnlock.value)
        }

    @Test
    fun `pinless parent on a protected tv is locked`() =
        test {
            gate.isDeviceProtected.value = true
            val dad = testUser("Dad")
            viewModel.start(testServer, listOf(dad))
            advanceUntilIdle()
            assertEquals(AddKidStep.ParentLocked(dad), step)
            viewModel.createFirstPin("4321")
            advanceUntilIdle()
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
            assertTrue(api.listCalls.isEmpty())
        }

    @Test
    fun `no adult on the tv cannot add a kid`() =
        test {
            viewModel.start(testServer, emptyList())
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.NoParent, Retry.None), step)
        }

    @Test
    fun `existing kid signs in on this tv without creating`() =
        test {
            val emma = testKid("Emma")
            api.connectResults += KidApiResult.Ok(emma.copy(displayName = "Emma B"))
            unlockMom()
            viewModel.signInExisting(emma)
            advanceUntilIdle()
            assertTrue(api.createCalls.isEmpty())
            assertEquals(Triple("token-Mom", "kid-Emma", "000001"), api.connectCalls.single())
            assertSignedIn("Emma B")
        }

    @Test
    fun `form checks the name and level before sending`() =
        test {
            fillForm(name = "  ", level = KidLevel.KIDS)
            assertEquals(NameError.Invalid, (step as AddKidStep.Form).nameError)
            viewModel.updateForm(name = "Emma", level = null)
            viewModel.submitForm()
            assertEquals(AddKidError.PickLevel, (step as AddKidStep.Form).error)
            viewModel.updateForm(name = "A name that is far too long", level = KidLevel.TEENS)
            viewModel.submitForm()
            assertEquals(NameError.Invalid, (step as AddKidStep.Form).nameError)
            assertTrue(api.createCalls.isEmpty())
        }

    @Test
    fun `new kid is created, connected, signed in and gets the picked picture`() =
        test {
            fillForm(name = "  Emma  ", level = KidLevel.OLDER_KIDS)
            assertEquals(AddKidStep.PickPicture("Emma", KidLevel.OLDER_KIDS, null), step)
            viewModel.pickPicture(viewModel.avatars.first())
            advanceUntilIdle()
            val create = api.createCalls.single()
            assertEquals("token-Mom", create.token)
            assertEquals("Emma", create.name)
            assertEquals(KidLevel.OLDER_KIDS, create.level)
            assertTrue(create.key.length >= 16)
            assertEquals("kid-Emma", api.connectCalls.single().second)
            assertSignedIn("Emma")
            val (target, png) = uploader.uploads.single()
            assertEquals(quickConnect.kidUserId, target.userId)
            assertEquals("kid-token", target.accessToken)
            assertEquals("bee-blue", png.decodeToString())
            assertEquals(1, cache.versions.value[quickConnect.kidUserId])
        }

    @Test
    fun `skipping the picture saves none`() =
        test {
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertSignedIn("Emma")
            assertTrue(uploader.uploads.isEmpty())
        }

    @Test
    fun `a failed picture save still signs the kid in`() =
        test {
            uploader.failWith = IllegalStateException("403")
            fillForm()
            viewModel.pickPicture(viewModel.avatars.first())
            advanceUntilIdle()
            assertSignedIn("Emma")
        }

    @Test
    fun `create retry reuses the idempotency key`() =
        test {
            api.createResults += KidApiResult.NetworkError(RuntimeException("offline"))
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Network, Retry.Create("Emma", KidLevel.KIDS)), step)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(2, api.createCalls.size)
            assertEquals(api.createCalls[0].key, api.createCalls[1].key)
            assertSignedIn("Emma")
        }

    @Test
    fun `request in progress waits and retries the same key`() =
        test {
            api.createResults += KidApiResult.Problem(409, KidProblemCodes.REQUEST_IN_PROGRESS)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(2, api.createCalls.size)
            assertEquals(api.createCalls[0].key, api.createCalls[1].key)
            assertSignedIn("Emma")
        }

    @Test
    fun `idempotency conflict gets a new key next time`() =
        test {
            api.createResults += KidApiResult.Problem(409, KidProblemCodes.IDEMPOTENCY_CONFLICT)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidError.Generic, (step as AddKidStep.Failed).error)
            viewModel.retry()
            advanceUntilIdle()
            assertNotEquals(api.createCalls[0].key, api.createCalls[1].key)
        }

    @Test
    fun `a changed form is a new submission with a new key`() =
        test {
            api.createResults += KidApiResult.Problem(409, KidProblemCodes.KID_NAME_TAKEN)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(NameError.Taken, (step as AddKidStep.Form).nameError)
            viewModel.updateForm(name = "Emma 2")
            viewModel.submitForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertNotEquals(api.createCalls[0].key, api.createCalls[1].key)
            assertEquals("Emma 2", api.createCalls[1].name)
        }

    @Test
    fun `invalid request from the website is an inline name error`() =
        test {
            api.createResults += KidApiResult.Problem(400, KidProblemCodes.INVALID_REQUEST)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(NameError.Invalid, (step as AddKidStep.Form).nameError)
        }

    @Test
    fun `connect failure after create reconnects the same kid and never creates again`() =
        test {
            api.connectResults += KidApiResult.NetworkError(RuntimeException("offline"))
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Network, Retry.Connect(testKid("Emma"))), step)
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(1, api.createCalls.size)
            assertEquals(listOf("kid-Emma", "kid-Emma"), api.connectCalls.map { it.second })
            assertSignedIn("Emma")
        }

    @Test
    fun `expired code gets a fresh code for the same kid once`() =
        test {
            api.connectResults += KidApiResult.Problem(400, KidProblemCodes.QUICK_CONNECT_INVALID_CODE)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(2, quickConnect.initiated)
            assertEquals(listOf("000001", "000002"), api.connectCalls.map { it.third })
            assertEquals(1, api.createCalls.size)
            assertSignedIn("Emma")
        }

    @Test
    fun `a second invalid code stops with a retry for the same kid`() =
        test {
            repeat(2) { api.connectResults += KidApiResult.Problem(400, KidProblemCodes.QUICK_CONNECT_INVALID_CODE) }
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Generic, Retry.Connect(testKid("Emma"))), step)
        }

    @Test
    fun `quick connect that never authenticates times out to a retry`() =
        test {
            quickConnect.pollsUntilAuthenticated = Int.MAX_VALUE
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(2, quickConnect.initiated)
            assertTrue(step is AddKidStep.Failed)
            assertTrue(gate.signedIn.isEmpty())
        }

    @Test
    fun `kid not found on connect refreshes the list`() =
        test {
            val emma = ownKid("Emma")
            api.listResults += KidApiResult.Ok(KidList(listOf(emma), 5))
            api.listResults += KidApiResult.Ok(KidList(emptyList(), 5))
            api.connectResults += KidApiResult.Problem(404, KidProblemCodes.KID_NOT_FOUND)
            quickConnect.pollsUntilAuthenticated = Int.MAX_VALUE
            unlockMom()
            viewModel.addExisting()
            runCurrent()
            assertEquals(listOf(emma), (step as AddKidStep.Pairing).kids)
            viewModel.signInExisting(emma)
            runCurrent()
            assertEquals(2, api.listCalls.size)
            assertEquals(emptyList<KidProfile>(), (step as AddKidStep.Pairing).kids)
            viewModel.cancel()
        }

    @Test
    fun `contract errors map to what the tv shows`() {
        fun map(
            code: String,
            retryAfter: Long? = null,
        ) = AddKidViewModel.errorFor(KidApiResult.Problem(400, code, retryAfter))
        assertEquals(AddKidError.NotAvailable, map(KidProblemCodes.KIDS_UNAVAILABLE))
        assertEquals(AddKidError.LimitReached, map(KidProblemCodes.KID_LIMIT_REACHED))
        assertEquals(AddKidError.AccessInactive, map(KidProblemCodes.ACCESS_REQUIRED))
        assertEquals(AddKidError.SignInAgain, map(KidProblemCodes.AUTHENTICATION_REQUIRED))
        assertEquals(AddKidError.KidNotActive, map(KidProblemCodes.KID_NOT_ACTIVE))
        assertEquals(AddKidError.RateLimited(2), map(KidProblemCodes.RATE_LIMITED, 90_000))
        assertEquals(AddKidError.RateLimited(null), map(KidProblemCodes.RATE_LIMITED))
        assertEquals(AddKidError.Generic, map(KidProblemCodes.KID_CALLER_FORBIDDEN))
        assertEquals(AddKidError.Generic, map(KidProblemCodes.WEASELPLEX_UNAVAILABLE))
    }

    @Test
    fun `flag off on create shows unavailable with a retry`() =
        test {
            api.createResults += KidApiResult.Problem(404, KidProblemCodes.KIDS_UNAVAILABLE)
            fillForm()
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.NotAvailable, Retry.Create("Emma", KidLevel.KIDS)), step)
        }

    @Test
    fun `cancel drops the parent unlock`() =
        test {
            unlockMom()
            viewModel.cancel()
            assertEquals(null, gate.parentUnlock.value)
            assertTrue(gate.clearCount > 0)
        }

    private val unlockExpiry = KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds

    @Test
    fun `an expired parent unlock goes back to the PIN before creating`() =
        test {
            fillForm()
            gate.now += unlockExpiry
            viewModel.pickPicture(null)
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(api.createCalls.isEmpty())
        }

    @Test
    fun `an expired parent unlock goes back to the PIN before connecting`() =
        test {
            unlockMom()
            gate.now += unlockExpiry
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(api.connectCalls.isEmpty())
            assertEquals(0, quickConnect.initiated)
        }

    @Test
    fun `an unlock expiring while the code is approved stops before the kid signs in`() =
        test {
            unlockMom()
            quickConnect.onPoll = { gate.now += unlockExpiry }
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(gate.signedIn.isEmpty())
        }

    @Test
    fun `an unlock for another parent or action doesn't count`() =
        test {
            val dad = testUser("Dad", pin = "9")
            unlockMom()
            gate.parentUnlock.value = ParentUnlock(dad.rowId, gate.now, UnlockPurpose.AddKid)
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)

            gate.parentUnlock.value = ParentUnlock(mom.rowId, gate.now, UnlockPurpose.Switch(dad.rowId))
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(api.connectCalls.isEmpty())
        }

    @Test
    fun `the parent PIN unlocks the add-kid flow only`() =
        test {
            unlockMom()
            assertEquals(UnlockPurpose.AddKid, gate.parentUnlock.value?.purpose)
        }

    @Test
    fun `a kid sign-in refused at publication goes back to the parent's PIN`() =
        test {
            unlockMom()
            // The unlock ran out during the sign-in's network calls: the gate published nothing
            gate.signInFailure = ProfileGateException(GateDecision.NeedsParentUnlock)
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            verify(exactly = 0) { navigation.navigateTo(any()) }
        }

    /** One of the account's own kids, with its media user (contract 4) */
    private fun ownKid(name: String) =
        testKid(name).copy(mediaUserId = UUID.nameUUIDFromBytes("media-$name".toByteArray()).toString().replace("-", ""))

    // Contract 12.4: every other saved adult on the TV has a PIN before a kid is added

    private val dad = testUser("Dad")

    @Test
    fun `other saved adults with a token and no pin create one or leave first`() =
        test {
            val gran = testUser("Gran", token = null)
            val aunt = testUser("Aunt", pin = "UDLR")
            val kid = testUser("Kiddo")
            gate.kinds[kid.id] = ProfileKind.KID
            val unknown = testUser("Unknown")
            gate.kinds[unknown.id] = ProfileKind.UNKNOWN
            unlockMom(listOf(mom, dad, gran, aunt, kid, unknown))
            assertEquals(AddKidStep.AdultPins(listOf(dad)), step)
            // Their account PINs were pulled first (contract 11.2)
            coVerify { accountPins.refresh(dad) }
            coVerify { accountPins.refresh(aunt) }
            coVerify(exactly = 0) { accountPins.refresh(gran) }
        }

    @Test
    fun `an adult whose account already has a pin counts`() =
        test {
            coEvery { accountPins.refresh(dad) } returns dad.copy(pin = "UDLR")
            unlockMom(listOf(mom, dad))
            assertEquals(AddKidStep.ChooseHow, step)
        }

    @Test
    fun `create pin saves to the adult's account first, then the kid step is next`() =
        test {
            unlockMom(listOf(mom, dad))
            assertEquals(AdultPinOutcome.NOT_SAVED, viewModel.createAdultPin(dad, "432"))
            assertEquals(AdultPinOutcome.SAVED, viewModel.createAdultPin(dad, "UDLR"))
            // Create-only (review round 1), never the overwriting set
            coVerify(exactly = 1) { accountPins.createIfNoPin(dad, "UDLR", any()) }
            coVerify(exactly = 0) { accountPins.set(dad, any(), any()) }
            coVerify { serverRepository.updateUserAuth(dad, "UDLR", false) }
            assertEquals(AddKidStep.ChooseHow, step)
        }

    @Test
    fun `a pin the account couldn't save changes nothing`() =
        test {
            coEvery { accountPins.createIfNoPin(dad, any(), any()) } returns PinCreate.FAILED
            unlockMom(listOf(mom, dad))
            assertEquals(AdultPinOutcome.NOT_SAVED, viewModel.createAdultPin(dad, "UDLR"))
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
            assertEquals(AddKidStep.AdultPins(listOf(dad)), step)
        }

    @Test
    fun `an account that already has a pin is adopted, never overwritten`() =
        test {
            coEvery { accountPins.createIfNoPin(dad, any(), any()) } returns PinCreate.ALREADY_SET
            unlockMom(listOf(mom, dad))
            assertEquals(AdultPinOutcome.ALREADY_HAD_PIN, viewModel.createAdultPin(dad, "UDLR"))
            assertEquals(AddKidStep.ChooseHow, step)
        }

    @Test
    fun `an adult no longer eligible drops off the list`() =
        test {
            val uncle = testUser("Uncle")
            coEvery { accountPins.createIfNoPin(dad, any(), any()) } returns PinCreate.NOT_ELIGIBLE
            unlockMom(listOf(mom, dad, uncle))
            assertEquals(AdultPinOutcome.NOT_SAVED, viewModel.createAdultPin(dad, "UDLR"))
            assertEquals(AddKidStep.AdultPins(listOf(uncle)), step)
        }

    /** The account write held until [written] completes; records what the authorization said after it */
    private fun holdPinWrite(
        written: CompletableDeferred<Unit>,
        authorized: MutableList<Boolean>,
    ) {
        coEvery { accountPins.createIfNoPin(dad, any(), any()) } coAnswers {
            withContext(NonCancellable) {
                written.await()
                val ok = thirdArg<() -> Boolean>()()
                authorized += ok
                if (ok) {
                    serverRepository.updateUserAuth(dad, secondArg(), false)
                    PinCreate.CREATED
                } else {
                    PinCreate.REFUSED
                }
            }
        }
    }

    @Test
    fun `leaving the flow while a pin is saving cancels it and writes nothing more`() =
        test {
            val written = CompletableDeferred<Unit>()
            val authorized = mutableListOf<Boolean>()
            holdPinWrite(written, authorized)
            unlockMom(listOf(mom, dad))
            var outcome: AdultPinOutcome? = null
            launch { outcome = viewModel.createAdultPin(dad, "UDLR") }
            runCurrent()
            viewModel.cancel()
            written.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(false), authorized)
            assertEquals(AdultPinOutcome.NOT_SAVED, outcome)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
        }

    @Test
    fun `an unlock running out while a pin is saving writes nothing more`() =
        test {
            val written = CompletableDeferred<Unit>()
            val authorized = mutableListOf<Boolean>()
            holdPinWrite(written, authorized)
            unlockMom(listOf(mom, dad))
            var outcome: AdultPinOutcome? = null
            launch { outcome = viewModel.createAdultPin(dad, "UDLR") }
            runCurrent()
            gate.now += unlockExpiry
            written.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(false), authorized)
            assertEquals(AdultPinOutcome.NOT_SAVED, outcome)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
            assertEquals(AddKidStep.EnterPin(mom), step)
        }

    @Test
    fun `dismissing the pin screen while saving cancels the save`() =
        test {
            val written = CompletableDeferred<Unit>()
            val authorized = mutableListOf<Boolean>()
            holdPinWrite(written, authorized)
            unlockMom(listOf(mom, dad))
            val screen = launch { viewModel.createAdultPin(dad, "UDLR") }
            runCurrent()
            screen.cancel()
            written.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(false), authorized)
            coVerify(exactly = 0) { serverRepository.updateUserAuth(any(), any(), any()) }
        }

    @Test
    fun `remove takes the adult off this tv under the parent's add-kid unlock`() =
        test {
            val uncle = testUser("Uncle")
            unlockMom(listOf(mom, dad, uncle))
            assertEquals(AddKidStep.AdultPins(listOf(dad, uncle)), step)
            viewModel.removeAdult(dad)
            advanceUntilIdle()
            val authorize = slot<() -> Unit>()
            coVerify { serverRepository.removeUser(dad, capture(authorize)) }
            assertEquals(AddKidStep.AdultPins(listOf(uncle)), step)
            // The removal step itself checks the unlock: still valid now, refused once it ran out
            authorize.captured()
            gate.now += unlockExpiry
            assertThrows(ProfileGateException::class.java) { authorize.captured() }
        }

    @Test
    fun `a failed removal says so and keeps the adult listed`() =
        test {
            coEvery { serverRepository.removeUser(dad, any()) } throws IllegalStateException("db")
            unlockMom(listOf(mom, dad))
            viewModel.removeAdult(dad)
            advanceUntilIdle()
            assertEquals(AddKidStep.AdultPins(listOf(dad), removeFailed = true), step)
        }

    @Test
    fun `an adult who got a pin meanwhile isn't removed`() =
        test {
            unlockMom(listOf(mom, dad))
            coEvery { accountPins.refresh(dad) } returns dad.copy(pin = "UDLR")
            viewModel.removeAdult(dad)
            advanceUntilIdle()
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            assertEquals(AddKidStep.ChooseHow, step)
        }

    @Test
    fun `an unlock that ran out in the adult step asks the parent's pin again`() =
        test {
            unlockMom(listOf(mom, dad))
            gate.now += unlockExpiry
            viewModel.removeAdult(dad)
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            unlockAgain()
            assertEquals(AddKidStep.AdultPins(listOf(dad)), step)
            gate.now += unlockExpiry
            assertEquals(AdultPinOutcome.NOT_SAVED, viewModel.createAdultPin(dad, "UDLR"))
            coVerify(exactly = 0) { accountPins.createIfNoPin(any(), any(), any()) }
            assertEquals(AddKidStep.EnterPin(mom), step)
        }

    @Test
    fun `back in the adult step leaves without a kid`() =
        test {
            unlockMom(listOf(mom, dad))
            viewModel.cancel()
            assertNull(gate.parentUnlock.value)
            assertTrue(api.createCalls.isEmpty())
            assertTrue(gate.signedIn.isEmpty())
        }

    private suspend fun TestScope.unlockAgain() {
        gate.now += 1
        viewModel.submitPin("1234")
        advanceUntilIdle()
    }

    // Contract 12.3: pairing a kid from another account with a code

    @Test
    fun `pairing shows the code and the account's own kids not on this tv yet`() =
        test {
            val avery = testUser("Avery")
            gate.kinds[avery.id] = ProfileKind.KID
            val emma = ownKid("Emma")
            val here = testKid("Avery").copy(mediaUserId = avery.id.toString().uppercase())
            val disabled = ownKid("Zed").copy(state = "disabled")
            api.listResults += KidApiResult.Ok(KidList(listOf(emma, here, disabled), 5))
            quickConnect.pollsUntilAuthenticated = Int.MAX_VALUE
            unlockMom(listOf(mom, avery))
            viewModel.addExisting()
            runCurrent()
            assertEquals(AddKidStep.Pairing(code = "000001", kids = listOf(emma)), step)
            assertEquals(listOf("token-Mom"), api.listCalls)
            assertEquals("000 001", AddKidViewModel.formatCode("000001"))
            // Polled every 2 seconds, and polling stops when the screen is left
            advanceTimeBy(4_001)
            assertEquals(2, quickConnect.polled.size)
            viewModel.cancel()
            advanceTimeBy(20_000)
            assertEquals(2, quickConnect.polled.size)
        }

    @Test
    fun `an expired pairing code is replaced by a new one by itself`() =
        test {
            quickConnect.pollResults += QuickConnectPoll.WAITING
            quickConnect.pollResults += QuickConnectPoll.EXPIRED
            quickConnect.pollsUntilAuthenticated = Int.MAX_VALUE
            unlockMom()
            viewModel.addExisting()
            runCurrent()
            advanceTimeBy(4_001)
            assertEquals(2, quickConnect.initiated)
            assertEquals("000002", (step as AddKidStep.Pairing).code)
            assertEquals(listOf("secret-1", "secret-1"), quickConnect.polled)
            advanceTimeBy(2_000)
            assertEquals("secret-2", quickConnect.polled.last())
            viewModel.cancel()
        }

    @Test
    fun `failed polls back off and keep the code`() =
        test {
            repeat(2) { quickConnect.pollResults += RuntimeException("offline") }
            quickConnect.pollsUntilAuthenticated = Int.MAX_VALUE
            unlockMom()
            viewModel.addExisting()
            runCurrent()
            advanceTimeBy(2_001)
            assertEquals(1, quickConnect.polled.size)
            assertTrue((step as AddKidStep.Pairing).trouble)
            // Next try after 4 s, not 2 s; then 8 s
            advanceTimeBy(3_000)
            assertEquals(1, quickConnect.polled.size)
            advanceTimeBy(1_001)
            assertEquals(2, quickConnect.polled.size)
            advanceTimeBy(7_000)
            assertEquals(2, quickConnect.polled.size)
            advanceTimeBy(1_001)
            assertEquals(3, quickConnect.polled.size)
            assertEquals(AddKidStep.Pairing(code = "000001"), step)
            assertEquals(1, quickConnect.initiated)
            viewModel.cancel()
        }

    @Test
    fun `a code approved for a grown-up is refused and signed out`() =
        test {
            quickConnect.authRating = null
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.PairingRefused, step)
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
            assertTrue(gate.signedIn.isEmpty())
            verify(exactly = 0) { navigation.navigateTo(any()) }
            // "Get a new code", approved for the kid this time
            quickConnect.authRating = 10
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(2, quickConnect.initiated)
            assertSignedIn("Emma")
        }

    @Test
    fun `a sign-in without a policy is refused like a grown-up's`() =
        test {
            quickConnect.authPolicy = false
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.PairingRefused, step)
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
            assertTrue(gate.signedIn.isEmpty())
        }

    @Test
    fun `a paired kid signs in through the gate with its server name and no picture step`() =
        test {
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertSignedIn("Emma")
            assertTrue(api.createCalls.isEmpty())
            assertTrue(api.connectCalls.isEmpty())
            assertTrue(uploader.uploads.isEmpty())
            // Handed to the session: closing the flow afterwards never signs it out
            viewModel.cancel()
            advanceUntilIdle()
            assertTrue(quickConnect.signedOut.isEmpty())
        }

    @Test
    fun `an unlock that ran out while pairing asks the pin again and finishes with the same sign-in`() =
        test {
            unlockMom()
            quickConnect.onPoll = { gate.now += unlockExpiry }
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(gate.signedIn.isEmpty())
            quickConnect.onPoll = {}
            unlockAgain()
            assertSignedIn("Emma")
            assertEquals(1, quickConnect.initiated)
            assertEquals(1, quickConnect.authenticated)
        }

    @Test
    fun `a paired sign-in refused at publication keeps the result for after the pin`() =
        test {
            unlockMom()
            gate.signInFailure = ProfileGateException(GateDecision.NeedsParentUnlock)
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            gate.signInFailure = null
            unlockAgain()
            assertSignedIn("Emma")
            assertEquals(1, quickConnect.authenticated)
        }

    // Review round 1: a sign-in the flow won't use is signed out, never left behind

    @Test
    fun `backing out while a kid's sign-in arrives signs that token out`() =
        test {
            quickConnect.authGate = CompletableDeferred()
            unlockMom()
            viewModel.addExisting()
            runCurrent()
            advanceTimeBy(2_001)
            assertEquals(1, quickConnect.authenticated)
            viewModel.cancel()
            quickConnect.authGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
            assertTrue(gate.signedIn.isEmpty())
        }

    @Test
    fun `backing out while a grown-up's sign-in arrives signs that token out`() =
        test {
            quickConnect.authRating = null
            quickConnect.authGate = CompletableDeferred()
            unlockMom()
            viewModel.addExisting()
            runCurrent()
            advanceTimeBy(2_001)
            viewModel.cancel()
            quickConnect.authGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
            assertTrue(gate.signedIn.isEmpty())
        }

    @Test
    fun `a kid's sign-in kept for the parent's pin is signed out when they back out`() =
        test {
            unlockMom()
            quickConnect.onPoll = { gate.now += unlockExpiry }
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertTrue(quickConnect.signedOut.isEmpty())
            viewModel.cancel()
            advanceUntilIdle()
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
        }

    @Test
    fun `a connect sign-in the expired unlock can't use is signed out`() =
        test {
            unlockMom()
            quickConnect.onPoll = { gate.now += unlockExpiry }
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
        }

    // Review round 2

    @Test
    fun `a sign-in issued as the timeout fires is signed out`() =
        test {
            quickConnect.authTokens += "late-token"
            quickConnect.returnDelays += AddKidViewModel.AUTHENTICATE_TIMEOUT_MS + 1
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            // The late result is signed out; the next code signs the kid in
            assertEquals(listOf("late-token"), quickConnect.signedOut)
            assertEquals(2, quickConnect.authenticated)
            assertSignedIn("Emma")
        }

    @Test
    fun `a pairing sign-in kept by the tv before the gate failed is never signed out`() =
        test {
            // Published, then the switch journal failed: the session (and Room) hold the token
            coEvery { serverRepository.isTokenInUse("kid-token") } returns true
            gate.signInFailure = IllegalStateException("journal")
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Generic, Retry.Pair), step)
            viewModel.cancel()
            advanceUntilIdle()
            assertTrue(quickConnect.signedOut.isEmpty())
        }

    @Test
    fun `a pairing sign-in the gate failed with before keeping it is signed out on back`() =
        test {
            gate.signInFailure = IllegalStateException("network")
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Generic, Retry.Pair), step)
            assertTrue(quickConnect.signedOut.isEmpty())
            viewModel.cancel()
            advanceUntilIdle()
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
        }

    @Test
    fun `a retry of a sign-in the tv kept stays the tv's`() =
        test {
            coEvery { serverRepository.isTokenInUse("kid-token") } returns true
            gate.signInFailure = IllegalStateException("journal")
            unlockMom()
            viewModel.addExisting()
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Generic, Retry.Pair), step)
            // Retried after the unlock ran out: the same result waits for the PIN, still the TV's
            gate.signInFailure = null
            gate.now += unlockExpiry
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(AddKidStep.EnterPin(mom), step)
            viewModel.cancel()
            advanceUntilIdle()
            assertTrue(quickConnect.signedOut.isEmpty())
        }

    @Test
    fun `a connect sign-in kept by the tv before the gate failed is never signed out`() =
        test {
            coEvery { serverRepository.isTokenInUse("kid-token") } returns true
            gate.signInFailure = IllegalStateException("journal")
            unlockMom()
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(AddKidStep.Failed(AddKidError.Generic, Retry.Connect(testKid("Emma"))), step)
            assertTrue(quickConnect.signedOut.isEmpty())
        }

    @Test
    fun `a connect sign-in the gate failed with before keeping it is signed out`() =
        test {
            gate.signInFailure = IllegalStateException("network")
            unlockMom()
            viewModel.signInExisting(testKid("Emma"))
            advanceUntilIdle()
            assertEquals(listOf("kid-token"), quickConnect.signedOut)
        }

    @Test
    fun `a storage failure while saving an adult's pin says it wasn't saved`() =
        test {
            coEvery { accountPins.createIfNoPin(dad, any(), any()) } throws IllegalStateException("room")
            unlockMom(listOf(mom, dad))
            assertEquals(AdultPinOutcome.NOT_SAVED, viewModel.createAdultPin(dad, "UDLR"))
            assertEquals(AddKidStep.AdultPins(listOf(dad)), step)
        }

    @Test
    fun `kid signal, backoff and id rules`() {
        assertEquals(4_000L, AddKidViewModel.backoffMillis(1))
        assertEquals(16_000L, AddKidViewModel.backoffMillis(3))
        assertEquals(30_000L, AddKidViewModel.backoffMillis(9))
        assertEquals("12345", AddKidViewModel.formatCode("12345"))
        assertEquals(
            AddKidViewModel.normalisedId("0123456789abcdef0123456789abcdef"),
            AddKidViewModel.normalisedId("01234567-89AB-CDEF-0123-456789ABCDEF"),
        )
    }
}
