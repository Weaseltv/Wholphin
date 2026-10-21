package com.github.damontecres.wholphin.kids

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.test.server
import com.github.damontecres.wholphin.ui.setup.ChooserPrompt
import com.github.damontecres.wholphin.ui.setup.PinPurpose
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import java.util.UUID

/** The chooser's use of the gate: adult lockout, PIN prompts, long-press delete rules (plan 4.2) */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooserGateViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val serverDao = mockk<JellyfinServerDao>()
    private val gate = mockk<KidSessionGateImpl>(relaxed = true)
    private val store = mockk<KidProtectionStore>()
    private val chooserLock = mockk<ProfileChooserLock>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>(relaxed = true)

    private val serverId = UUID.randomUUID()
    private val server = server(serverId)

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
    private val pinlessAdult = profile(2, PolicyState.ADULT)
    private val kid = profile(3, PolicyState.KID, 10)
    private val olderKid = profile(4, PolicyState.KID, 14)
    private val kidWithPin = profile(5, PolicyState.KID, 10, pin = "5555")

    private lateinit var viewModel: SwitchUserViewModel

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        Dispatchers.setMain(dispatcher)
        WholphinDispatchers.configure(dispatcher)
        every { serverDao.getServer(serverId) } returns
            JellyfinServerUsers(server, listOf(parent, pinlessAdult, kid, olderKid, kidWithPin))
        coEvery { store.isProtected() } returns true
        coEvery { serverRepository.removeUser(any()) } just runs
        viewModel =
            SwitchUserViewModel(
                context = context,
                jellyfin = jellyfin,
                serverRepository = serverRepository,
                serverDao = serverDao,
                navigationManager = mockk(relaxed = true),
                setupNavigationManager = mockk(relaxed = true),
                imageUrlService = mockk(relaxed = true),
                kidSessionGate = gate,
                kidProtectionStore = store,
                profileChooserLock = chooserLock,
                server = server,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        WholphinDispatchers.reset()
    }

    private val prompt get() = viewModel.state.value.prompt

    @Test
    fun `pinless adult on a protected TV asks another parent`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult

            viewModel.onProfileSelected(pinlessAdult)

            val p = prompt as ChooserPrompt.PickParent
            assertTrue(p.locked)
            assertEquals(listOf(parent), p.parents)
            coVerify(exactly = 0) { gate.switchTo(any(), any()) }
        }

    @Test
    fun `older kid needs the parent's PIN, and a dismissed PIN changes nothing`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock

            viewModel.onProfileSelected(olderKid)
            val p = prompt as ChooserPrompt.Pin
            assertEquals(parent, p.pinOwner)
            assertTrue(p.isParentPin)

            coEvery { gate.verifyParentPin(parent, "0000", any()) } returns PinCheckResult.Wrong(4)
            viewModel.submitPin("0000")
            assertTrue((prompt as ChooserPrompt.Pin).message != null)

            viewModel.dismissPrompt()
            assertNull(prompt)
            verify { gate.clearParentUnlock() }
            coVerify(exactly = 0) { gate.switchTo(any(), any()) }
        }

    @Test
    fun `PIN profiles always prompt for their own PIN`() =
        runTest(dispatcher) {
            viewModel.onProfileSelected(parent)
            val p = prompt as ChooserPrompt.Pin
            assertEquals(parent, p.pinOwner)
            assertEquals(PinPurpose.SWITCH, p.purpose)
            coVerify(exactly = 0) { gate.evaluate(any()) }
        }

    @Test
    fun `a kid row can be removed freely`() =
        runTest(dispatcher) {
            viewModel.removeUser(kid)
            coVerify(exactly = 1) { serverRepository.removeUser(kid) }
            assertNull(prompt)
        }

    @Test
    fun `deleting an adult with a PIN needs that PIN`() =
        runTest(dispatcher) {
            viewModel.removeUser(parent)
            coVerify(exactly = 0) { serverRepository.removeUser(any()) }
            val p = prompt as ChooserPrompt.Pin
            assertEquals(PinPurpose.DELETE, p.purpose)
            assertEquals(parent, p.pinOwner)

            coEvery { gate.verifyParentPin(parent, "1234", any()) } returns PinCheckResult.Correct
            viewModel.submitPin("1234")
            coVerify(exactly = 1) { serverRepository.removeUser(parent) }
        }

    @Test
    fun `deleting a locked pinless adult needs another parent`() =
        runTest(dispatcher) {
            viewModel.removeUser(pinlessAdult)
            coVerify(exactly = 0) { serverRepository.removeUser(any()) }
            val pick = prompt as ChooserPrompt.PickParent
            assertTrue(pick.locked)
            assertEquals(PinPurpose.DELETE, pick.purpose)

            viewModel.pickParent(parent)
            coEvery { gate.verifyParentPin(parent, "1234", any()) } returns PinCheckResult.Correct
            viewModel.submitPin("1234")
            coVerify(exactly = 1) { serverRepository.removeUser(pinlessAdult) }
        }

    @Test
    fun `deleting a pinless adult on an unprotected TV is free`() =
        runTest(dispatcher) {
            coEvery { store.isProtected() } returns false
            viewModel.removeUser(pinlessAdult)
            coVerify(exactly = 1) { serverRepository.removeUser(pinlessAdult) }
        }

    @Test
    fun `server sign-in as a new adult from a PIN kid's prompt needs the adult's own PIN`() =
        runTest(dispatcher) {
            // The kid was picked, then "use server sign-in" was approved by an adult not saved on this TV
            val adultId = UUID.randomUUID()
            every { serverDao.getUser(serverId, adultId) } returns null
            every { serverDao.getUser(serverId, kidWithPin.id) } returns kidWithPin
            val auth =
                AuthenticationResult(
                    user =
                        UserDto(
                            id = adultId,
                            name = "new adult",
                            hasPassword = false,
                            hasConfiguredPassword = false,
                            hasConfiguredEasyPassword = false,
                            policy = mockk<UserPolicy> { every { maxParentalRating } returns null },
                        ),
                    accessToken = "adult-token",
                    serverId = serverId.toServerString(),
                )
            val api = mockk<ApiClient>()
            val userApi = mockk<UserApi>()
            every { jellyfin.createApi(any(), any(), any(), any(), any()) } returns api
            every { api.userApi } returns userApi
            coEvery { userApi.authenticateUserByName(any<AuthenticateUserByName>()) } returns Response(auth, 200, emptyMap())

            viewModel.login(server, kidWithPin, "adult", "secret")

            val pending = viewModel.state.value.pendingPinSetup
            assertNotNull("the new adult must set their own PIN first", pending)
            assertNull(pending!!.existingUser)
            coVerify(exactly = 0) { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a failed authorized switch drops the unlock, and so does closing the sign-in dialog`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock andThen GateDecision.Allow
            viewModel.onProfileSelected(olderKid)
            verify(exactly = 1) { gate.clearParentUnlock() }

            coEvery { gate.verifyParentPin(parent, "1234", any()) } returns PinCheckResult.Correct
            // The older kid's saved token has expired
            coEvery { gate.switchTo(server, olderKid) } throws InvalidStatusException(401)
            viewModel.submitPin("1234")

            coVerify { gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)) }
            assertEquals(olderKid, viewModel.state.value.switchError?.user)
            verify(exactly = 2) { gate.clearParentUnlock() }

            // The sign-in dialog that follows is dismissed
            viewModel.cancelQuickConnect()
            verify(exactly = 3) { gate.clearParentUnlock() }
        }

    @Test
    fun `a delete PIN is bound to deleting that profile`() =
        runTest(dispatcher) {
            viewModel.removeUser(pinlessAdult)
            viewModel.pickParent(parent)
            coEvery { gate.verifyParentPin(parent, "1234", any()) } returns PinCheckResult.Correct
            viewModel.submitPin("1234")
            coVerify { gate.verifyParentPin(parent, "1234", UnlockPurpose.Remove(pinlessAdult.rowId)) }
        }
}
