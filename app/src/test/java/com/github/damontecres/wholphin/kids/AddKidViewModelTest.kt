package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.kids.AddKidError
import com.github.damontecres.wholphin.ui.kids.AddKidStep
import com.github.damontecres.wholphin.ui.kids.AddKidViewModel
import com.github.damontecres.wholphin.ui.kids.NameError
import com.github.damontecres.wholphin.ui.kids.PinError
import com.github.damontecres.wholphin.ui.kids.Retry
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun TestScope.unlockMom() {
        viewModel.start(testServer, listOf(mom))
        advanceUntilIdle()
        viewModel.submitPin("1234")
        advanceUntilIdle()
    }

    private suspend fun TestScope.fillForm(
        name: String = "Emma",
        level: KidLevel? = KidLevel.KIDS,
    ) {
        unlockMom()
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
    fun `correct pin lists kids with the parent's own token`() =
        test {
            unlockMom()
            assertEquals(listOf("token-Mom"), api.listCalls)
            assertEquals(AddKidStep.Form(), step)
        }

    @Test
    fun `pinless parent sets a first pin only on an unprotected tv`() =
        test {
            val dad = testUser("Dad")
            viewModel.start(testServer, listOf(dad))
            advanceUntilIdle()
            assertEquals(AddKidStep.CreateFirstPin(dad), step)
            viewModel.createFirstPin("4321")
            advanceUntilIdle()
            coVerify { serverRepository.updateUserAuth(dad, "4321", false) }
            assertEquals(
                "4321",
                gate.verified
                    .single()
                    .first.pin,
            )
            assertEquals(listOf("token-Dad"), api.listCalls)
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
            api.listResults += KidApiResult.Ok(KidList(listOf(emma), 5))
            api.connectResults += KidApiResult.Ok(emma.copy(displayName = "Emma B"))
            unlockMom()
            assertEquals(AddKidStep.ChooseKid(listOf(emma), 5), step)
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
            assertEquals("placeholder-cat", png.decodeToString())
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
            val emma = testKid("Emma")
            api.listResults += KidApiResult.Ok(KidList(listOf(emma), 5))
            api.listResults += KidApiResult.Ok(KidList(emptyList(), 5))
            api.connectResults += KidApiResult.Problem(404, KidProblemCodes.KID_NOT_FOUND)
            unlockMom()
            viewModel.signInExisting(emma)
            advanceUntilIdle()
            assertEquals(2, api.listCalls.size)
            assertTrue(step is AddKidStep.Form)
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
    fun `limit reached on create offers a retry, flag off shows unavailable`() =
        test {
            api.listResults += KidApiResult.Problem(404, KidProblemCodes.KIDS_UNAVAILABLE)
            unlockMom()
            assertEquals(AddKidStep.Failed(AddKidError.NotAvailable, Retry.LoadKids), step)
        }

    @Test
    fun `cancel drops the parent unlock`() =
        test {
            unlockMom()
            viewModel.cancel()
            assertEquals(null, gate.parentUnlock.value)
            assertTrue(gate.clearCount > 0)
        }
}
