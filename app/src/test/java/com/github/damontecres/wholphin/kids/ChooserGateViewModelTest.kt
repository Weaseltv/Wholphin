package com.github.damontecres.wholphin.kids

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
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

    /** Never switched into since kid profiles: not known to be an adult */
    private val unknownProfile = profile(6, PolicyState.UNKNOWN)
    private val accountPins = localAccountPins()

    private lateinit var viewModel: SwitchUserViewModel

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        Dispatchers.setMain(dispatcher)
        WholphinDispatchers.configure(dispatcher)
        every { serverDao.getServer(serverId) } returns
            JellyfinServerUsers(server, listOf(parent, pinlessAdult, kid, olderKid, kidWithPin, unknownProfile))
        coEvery { store.isProtected() } returns true
        coEvery { serverRepository.removeUser(any(), any()) } just runs
        coEvery { serverRepository.removePinlessUser(any(), any(), any()) } returns true
        viewModel =
            SwitchUserViewModel(
                context = context,
                jellyfin = mockk(relaxed = true),
                serverRepository = serverRepository,
                serverDao = serverDao,
                navigationManager = mockk(relaxed = true),
                setupNavigationManager = mockk(relaxed = true),
                imageUrlService = mockk(relaxed = true),
                kidSessionGate = gate,
                kidProtectionStore = store,
                profileChooserLock = chooserLock,
                accountPins = accountPins,
                accountAvatars = mockk(relaxed = true),
                pictures = mockk(relaxed = true),
                savedProfileChecks = mockk(relaxed = true),
                networkAvailability = onlineNetwork(),
                server = server,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        WholphinDispatchers.reset()
    }

    private val prompt get() = viewModel.state.value.prompt

    /** The parent's PIN check; a correct one grants through the asking flow's admission, as the real gate does */
    private fun parentPin(
        pin: String,
        result: PinCheckResult,
    ) {
        coEvery { gate.verifyParentPin(parent, pin, any(), any()) } answers {
            if (result == PinCheckResult.Correct) arg<(() -> Unit) -> Unit>(3).invoke {}
            result
        }
    }

    @Test
    fun `pinless adult on a protected TV needs a PIN, never another parent's`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult

            viewModel.onProfileSelected(pinlessAdult)

            // Contract 12.4 (v1.7)
            assertEquals(ChooserPrompt.NeedsPin(pinlessAdult), prompt)
            coVerify(exactly = 0) { gate.switchTo(any(), any(), any(), any()) }
            coVerify(exactly = 0) { gate.verifyParentPin(any(), any(), any(), any()) }
        }

    @Test
    fun `back on the needs-a-PIN panel changes nothing`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult
            viewModel.onProfileSelected(pinlessAdult)
            viewModel.dismissPrompt()
            assertNull(prompt)
            assertNull(viewModel.confirmRemoveNeedsPin())
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            coVerify(exactly = 0) { gate.switchTo(any(), any(), any(), any()) }
        }

    @Test
    fun `remove from this TV on the panel asks first, then needs no PIN`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult
            viewModel.onProfileSelected(pinlessAdult)
            // Nothing is removed before the question is answered
            assertNull(viewModel.confirmRemoveNeedsPin())
            viewModel.askRemoveNeedsPin()
            assertEquals(ChooserPrompt.NeedsPin(pinlessAdult, confirmRemove = true), prompt)
            viewModel.cancelRemoveNeedsPin()
            assertEquals(ChooserPrompt.NeedsPin(pinlessAdult), prompt)
            coVerify(exactly = 0) { serverRepository.removePinlessUser(any(), any(), any()) }

            viewModel.askRemoveNeedsPin()
            viewModel.confirmRemoveNeedsPin()
            coVerify(exactly = 1) { serverRepository.removePinlessUser(pinlessAdult, true, any()) }
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            coVerify(exactly = 0) { gate.verifyParentPin(any(), any(), any(), any()) }
            assertNull(prompt)
        }

    @Test
    fun `removing from the panel asks for a PIN the account got meanwhile`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult
            val withPin = pinlessAdult.copy(pin = "UDLR")
            coEvery { accountPins.refresh(pinlessAdult) } returnsMany listOf(pinlessAdult, withPin)
            viewModel.onProfileSelected(pinlessAdult)
            viewModel.askRemoveNeedsPin()
            viewModel.confirmRemoveNeedsPin()
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            coVerify(exactly = 0) { serverRepository.removePinlessUser(any(), any(), any()) }
            val p = prompt as ChooserPrompt.Pin
            assertEquals(PinPurpose.DELETE, p.purpose)
            assertEquals(withPin, p.pinOwner)
        }

    private fun adultAuth(
        id: UUID,
        token: String = "adult-token",
    ) = AuthenticationResult(
        user =
            UserDto(
                id = id,
                name = "adult",
                hasPassword = false,
                hasConfiguredPassword = false,
                hasConfiguredEasyPassword = false,
                policy = mockk<UserPolicy> { every { maxParentalRating } returns null },
            ),
        accessToken = token,
        serverId = serverId.toServerString(),
    )

    @Test
    fun `create a PIN signs that profile in again, then sets its PIN and goes in`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(pinlessAdult) } returns GateDecision.LockedAdult
            every { serverDao.getUser(serverId, pinlessAdult.id) } returns pinlessAdult
            coEvery { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any(), any(), any()) } returns
                CurrentUser(server, pinlessAdult)
            viewModel.onProfileSelected(pinlessAdult)
            // "Create a PIN": the panel closes and the sign-in dialog opens for that profile
            viewModel.dismissPrompt()
            val auth = adultAuth(pinlessAdult.id)
            viewModel.signIn(server.url, existingUser = pinlessAdult, onError = {}) { auth }

            val pending = viewModel.state.value.pendingPinSetup
            assertNotNull("the fresh sign-in asks for the new PIN first", pending)
            assertEquals(pinlessAdult, pending!!.existingUser)
            coVerify(exactly = 0) { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any(), any(), any()) }

            assertTrue(viewModel.completePinSetup("UDLR"))
            coVerify { accountPins.upload(server.url, pinlessAdult.id, "adult-token", "UDLR") }
            coVerify(exactly = 1) {
                serverRepository.changeUser(server.url, auth, pinlessAdult, any(), "UDLR", any(), any(), any())
            }
        }

    @Test
    fun `a fresh sign-in as another account never sets this profile's PIN nor publishes it`() =
        runTest(dispatcher) {
            val otherId = UUID.randomUUID()
            every { serverDao.getUser(serverId, otherId) } returns null
            coEvery { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any(), any(), any()) } returns
                CurrentUser(server, parent)
            viewModel.signIn(server.url, existingUser = pinlessAdult, onError = {}) { adultAuth(otherId, "other-token") }

            val pending = viewModel.state.value.pendingPinSetup
            assertNotNull(pending)
            assertNull(pending!!.existingUser)
            viewModel.completePinSetup("UDLR")

            coVerify(exactly = 0) { accountPins.upload(any(), pinlessAdult.id, any(), any()) }
            coVerify(exactly = 0) { accountPins.set(pinlessAdult, any(), any()) }
            coVerify(exactly = 0) { serverRepository.updateUserAuth(pinlessAdult, any(), any()) }
            coVerify(exactly = 0) {
                serverRepository.changeUser(any<String>(), any(), pinlessAdult, any(), any(), any(), any(), any())
            }
            coVerify { accountPins.upload(server.url, otherId, "other-token", "UDLR") }
        }

    @Test
    fun `older kid needs the parent's PIN, and a dismissed PIN changes nothing`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock

            viewModel.onProfileSelected(olderKid)
            val p = prompt as ChooserPrompt.Pin
            assertEquals(parent, p.pinOwner)
            assertTrue(p.isParentPin)

            parentPin("0000", PinCheckResult.Wrong(4))
            viewModel.submitPin("0000")
            assertTrue((prompt as ChooserPrompt.Pin).message != null)

            viewModel.dismissPrompt()
            assertNull(prompt)
            verify { gate.clearParentUnlock() }
            coVerify(exactly = 0) { gate.switchTo(any(), any(), any(), any()) }
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
            coVerify(exactly = 1) { serverRepository.removeUser(kid, any()) }
            assertNull(prompt)
        }

    @Test
    fun `deleting an adult with a PIN needs that PIN`() =
        runTest(dispatcher) {
            viewModel.removeUser(parent)
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            val p = prompt as ChooserPrompt.Pin
            assertEquals(PinPurpose.DELETE, p.purpose)
            assertEquals(parent, p.pinOwner)

            parentPin("1234", PinCheckResult.Correct)
            viewModel.submitPin("1234")
            coVerify(exactly = 1) { serverRepository.removeUser(parent, any()) }
        }

    @Test
    fun `deleting a pinless adult on a protected TV needs no PIN`() =
        runTest(dispatcher) {
            // Contract 12.4 (v1.7): it can't be used on a TV with kids anyway
            viewModel.removeUser(pinlessAdult)
            coVerify(exactly = 1) { serverRepository.removePinlessUser(pinlessAdult, true, any()) }
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            coVerify(exactly = 0) { gate.verifyParentPin(any(), any(), any(), any()) }
            assertNull(prompt)
        }

    @Test
    fun `a PIN cached between the pull and the removal keeps the adult and is asked for`() =
        runTest(dispatcher) {
            // The pull found no PIN; the chooser's account sync then cached one, so the conditional delete keeps the row
            val withPin = pinlessAdult.copy(pin = "UDLR")
            coEvery { serverRepository.removePinlessUser(pinlessAdult, true, any()) } answers {
                every { serverDao.getServer(serverId) } returns
                    JellyfinServerUsers(server, listOf(parent, withPin, kid, olderKid, kidWithPin, unknownProfile))
                false
            }
            viewModel.removeUser(pinlessAdult)
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            val p = prompt as ChooserPrompt.Pin
            assertEquals(PinPurpose.DELETE, p.purpose)
            assertEquals(withPin, p.pinOwner)

            // Its own PIN then removes it, as for any adult with a PIN
            coEvery { gate.verifyParentPin(withPin, "UDLR", any(), any()) } answers {
                arg<(() -> Unit) -> Unit>(3).invoke {}
                PinCheckResult.Correct
            }
            viewModel.submitPin("UDLR")
            coVerify(exactly = 1) { serverRepository.removeUser(withPin, any()) }
        }

    @Test
    fun `deleting a pinless profile not known to be an adult still needs another parent`() =
        runTest(dispatcher) {
            viewModel.removeUser(unknownProfile)
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
            val pick = prompt as ChooserPrompt.PickParent
            assertTrue(pick.locked)
            assertEquals(PinPurpose.DELETE, pick.purpose)

            viewModel.pickParent(parent)
            parentPin("1234", PinCheckResult.Correct)
            viewModel.submitPin("1234")
            coVerify(exactly = 1) { serverRepository.removeUser(unknownProfile, any()) }
        }

    @Test
    fun `deleting a pinless adult on an unprotected TV is free`() =
        runTest(dispatcher) {
            coEvery { store.isProtected() } returns false
            viewModel.removeUser(pinlessAdult)
            coVerify(exactly = 1) { serverRepository.removePinlessUser(pinlessAdult, false, any()) }
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

            viewModel.signIn(server.url, existingUser = kidWithPin, onError = {}) { auth }

            val pending = viewModel.state.value.pendingPinSetup
            assertNotNull("the new adult must set their own PIN first", pending)
            assertNull(pending!!.existingUser)
            coVerify(exactly = 0) { serverRepository.changeUser(any<String>(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a failed authorized switch drops the unlock, and so does closing the sign-in dialog`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock andThen GateDecision.Allow
            viewModel.onProfileSelected(olderKid)
            verify(exactly = 1) { gate.clearParentUnlock() }

            parentPin("1234", PinCheckResult.Correct)
            // The older kid's saved token has expired
            coEvery { gate.switchTo(server, olderKid, any(), any()) } throws InvalidStatusException(401)
            viewModel.submitPin("1234")

            coVerify { gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId), any()) }
            assertEquals(
                olderKid,
                viewModel.state.value.switchError
                    ?.user,
            )
            // Only the failed switch's own unlock is spent, never one granted for another selection
            verify(exactly = 1) { gate.clearParentUnlock(UnlockPurpose.Switch(olderKid.rowId)) }
            verify(exactly = 1) { gate.clearParentUnlock() }

            // The sign-in dialog that follows is dismissed
            viewModel.cancelQuickConnect()
            verify(exactly = 2) { gate.clearParentUnlock() }
        }

    @Test
    fun `a delete PIN is bound to deleting that profile`() =
        runTest(dispatcher) {
            viewModel.removeUser(unknownProfile)
            viewModel.pickParent(parent)
            parentPin("1234", PinCheckResult.Correct)
            viewModel.submitPin("1234")
            coVerify { gate.verifyParentPin(parent, "1234", UnlockPurpose.Remove(unknownProfile.rowId), any()) }
        }

    @Test
    fun `a pick superseded by a newer one never navigates`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(any()) } returns GateDecision.Allow
            val firstSwitch = CompletableDeferred<Unit>()
            // The first switch finishes even though it is cancelled (publication can't be interrupted)
            coEvery { gate.switchTo(server, kid, any(), any()) } coAnswers {
                withContext(NonCancellable) { firstSwitch.await() }
                CurrentUser(server, kid).also { thirdArg<((CurrentUser) -> Unit)?>()?.invoke(it) }
            }
            coEvery { gate.switchTo(server, olderKid, any(), any()) } coAnswers {
                CurrentUser(server, olderKid).also { thirdArg<((CurrentUser) -> Unit)?>()?.invoke(it) }
            }

            viewModel.onProfileSelected(kid)
            viewModel.onProfileSelected(olderKid)
            firstSwitch.complete(Unit)

            coVerify(exactly = 1) { chooserLock.navigateAfterSwitch(CurrentUser(server, olderKid), any()) }
            coVerify(exactly = 0) { chooserLock.navigateAfterSwitch(CurrentUser(server, kid), any()) }
        }
}
