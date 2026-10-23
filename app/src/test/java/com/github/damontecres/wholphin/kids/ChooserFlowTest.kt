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
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

/**
 * One owner for every chooser flow (review round 4): a flow the user dismissed, Backed out of or replaced never grants
 * an unlock, publishes a session, removes a profile, shows a prompt again or navigates. The requests a flow waits on
 * are held by barriers that ignore cancellation, as a request already on the wire (or a step that can't be
 * interrupted) would, so each late continuation really runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooserFlowTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val accountPins = localAccountPins()
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
    private val kid = profile(3, PolicyState.KID, 10)
    private val olderKid = profile(4, PolicyState.KID, 14)

    /** Sessions the repository published (past its publishing step's check) */
    private val published = mutableListOf<CurrentUser>()

    /** Sessions the chooser navigated into */
    private val navigated = mutableListOf<CurrentUser>()

    /** Sessions withdrawn after their flow was left */
    private val withdrawn = mutableListOf<CurrentUser>()

    /** Unlocks the gate granted (only through the flow's admission) */
    private val granted = mutableListOf<UnlockPurpose?>()

    private lateinit var viewModel: SwitchUserViewModel

    /** What the repository's publishing step does: the last check, then the session is out */
    private fun publish(
        current: CurrentUser,
        authorize: ((CurrentUser) -> Unit)?,
    ): CurrentUser {
        authorize?.invoke(current)
        published += current
        return current
    }

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server, listOf(parent, kid, olderKid))
        every { serverDao.getUser(any(), any()) } returns null
        coEvery { store.isProtected() } returns true
        coEvery { gate.evaluate(any()) } returns GateDecision.Allow
        coEvery { gate.switchTo(server, any(), any(), any()) } coAnswers {
            publish(CurrentUser(server, secondArg()), thirdArg())
        }
        coEvery { gate.grantUnlockForFreshAuth(any(), any()) } answers {
            secondArg<(() -> Unit) -> Unit>().invoke { granted += null }
        }
        coEvery { chooserLock.navigateAfterSwitch(any(), any()) } coAnswers {
            secondArg<() -> Boolean>().invoke().also { if (it) navigated += firstArg<CurrentUser>() }
        }
        coEvery { serverRepository.endSessionForChooser(any<CurrentUser>()) } answers { withdrawn += firstArg<CurrentUser>() }
        // The navigating step enters what its flow published (review round 6)
        every { serverRepository.markEntered(any(), any()) } returns true
        viewModel =
            SwitchUserViewModel(
                context = mockk<Context>(relaxed = true),
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
                server = server,
            )
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private val state get() = viewModel.state.value

    /** The parent's PIN is checked once [checked] completes; a correct one grants only if its flow admits it */
    private fun parentPinCheckedWhen(
        checked: CompletableDeferred<Unit>,
        result: PinCheckResult,
    ) {
        coEvery { gate.verifyParentPin(parent, any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { checked.await() }
            if (result == PinCheckResult.Correct) {
                val purpose = thirdArg<UnlockPurpose?>()
                arg<(() -> Unit) -> Unit>(3).invoke { granted += purpose }
            }
            result
        }
    }

    /** A new adult's completed sign-in, with no row on this TV */
    private fun newAdultAuth(adultId: UUID = UUID.randomUUID()) =
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

    @Test
    fun `a fresh sign-in on a protected TV caches the account's PIN instead of asking for a new one`() =
        runTest(dispatcher) {
            val auth = newAdultAuth()
            val adult =
                CurrentUser(
                    server,
                    JellyfinUser(id = auth.user!!.id, name = "new adult", serverId = serverId, accessToken = "adult-token", pin = "ULLD"),
                )
            coEvery { accountPins.readAccount(server.url, auth.user!!.id, "adult-token") } returns
                AccountPinValue("ULLD", "2026-10-21T00:00:00Z")
            coEvery { serverRepository.changeUser(server.url, auth, null, any(), "ULLD", any(), any(), any()) } coAnswers
                { publish(adult, arg(6)) }

            viewModel.signIn(server.url, existingUser = null, onError = {}) { auth }

            assertNull("no new PIN over the account's", state.pendingPinSetup)
            assertEquals(listOf(adult), published)
            coVerify(exactly = 0) { accountPins.upload(any(), any(), any(), any()) }
        }

    @Test
    fun `a sign-in whose account PIN can't be read on a protected TV publishes nothing until it can`() =
        runTest(dispatcher) {
            val auth = newAdultAuth()
            val adult =
                CurrentUser(
                    server,
                    JellyfinUser(id = auth.user!!.id, name = "new adult", serverId = serverId, accessToken = "adult-token", pin = "ULLD"),
                )
            coEvery { accountPins.readAccount(any(), any(), any()) } returns null
            coEvery { serverRepository.changeUser(server.url, auth, null, any(), "ULLD", any(), any(), any()) } coAnswers
                { publish(adult, arg(6)) }

            viewModel.signIn(server.url, existingUser = null, onError = {}) { auth }

            assertNotNull(state.pendingPinCheck)
            assertNull("never a new PIN over one the account may have", state.pendingPinSetup)
            assertTrue(published.isEmpty())

            // Try again, once the account can be read
            coEvery { accountPins.readAccount(any(), any(), any()) } returns AccountPinValue("ULLD", "t")
            viewModel.retryPinCheck()
            assertNull(state.pendingPinCheck)
            assertEquals(listOf(adult), published)
            assertEquals(listOf(adult), navigated)
        }

    @Test
    fun `Back from the PIN check leaves the sign-in`() =
        runTest(dispatcher) {
            coEvery { accountPins.readAccount(any(), any(), any()) } returns null
            viewModel.signIn(server.url, existingUser = null, onError = {}) { newAdultAuth() }
            viewModel.cancelPinCheck()
            assertNull(state.pendingPinCheck)
            assertTrue(published.isEmpty())
        }

    /** A new adult without a PIN signs in on this protected TV: the chooser asks them to set a PIN first */
    private fun signInPinlessAdult(): Pair<AuthenticationResult, CurrentUser> {
        val adultId = UUID.randomUUID()
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
        viewModel.signIn(server.url, existingUser = null, onError = {}) { auth }
        assertNotNull("the adult sets a PIN before anything is published", state.pendingPinSetup)
        val adult = JellyfinUser(id = adultId, name = "new adult", serverId = serverId, accessToken = "adult-token", pin = "LRDU")
        return auth to CurrentUser(server, adult)
    }

    @Test
    fun `Back from a PIN setup whose requests are paused never publishes the adult over the kid picked next`() =
        runTest(dispatcher) {
            val (auth, adult) = signInPinlessAdult()
            val requests = CompletableDeferred<Unit>()
            coEvery { serverRepository.changeUser(server.url, auth, null, any(), "LRDU", any(), any(), any()) } coAnswers {
                withContext(NonCancellable) { requests.await() }
                publish(adult, arg(6))
            }

            viewModel.completePinSetup("LRDU")
            // Back, then the owner picks the kid; the adult's requests resume afterwards
            viewModel.cancelPinSetup()
            viewModel.onProfileSelected(kid)
            requests.complete(Unit)

            assertEquals(listOf(CurrentUser(server, kid)), published)
            assertEquals(listOf(CurrentUser(server, kid)), navigated)
            assertTrue("no unlock from the abandoned sign-in", granted.isEmpty())
            assertNull(state.pendingPinSetup)
        }

    @Test
    fun `a sign-in left after it published is withdrawn, never navigated into`() =
        runTest(dispatcher) {
            val (auth, adult) = signInPinlessAdult()
            val returning = CompletableDeferred<Unit>()
            coEvery { serverRepository.changeUser(server.url, auth, null, any(), "LRDU", any(), any(), any()) } coAnswers {
                val current = publish(adult, arg(6))
                // Published; Back lands before the switch returns from the main thread
                withContext(NonCancellable) { returning.await() }
                current
            }

            viewModel.completePinSetup("LRDU")
            viewModel.cancelPinSetup()
            returning.complete(Unit)

            assertEquals(listOf(adult), published)
            assertTrue(navigated.isEmpty())
            assertEquals(listOf(adult), withdrawn)
            assertTrue(granted.isEmpty())
        }

    @Test
    fun `a parent PIN dismissed while it is checked grants nothing and opens nothing`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock andThen GateDecision.Allow
            viewModel.onProfileSelected(olderKid)
            assertTrue((state.prompt as ChooserPrompt.Pin).isParentPin)
            val checked = CompletableDeferred<Unit>()
            parentPinCheckedWhen(checked, PinCheckResult.Correct)

            viewModel.submitPin("1234")
            viewModel.dismissPrompt()
            checked.complete(Unit)

            assertTrue(granted.isEmpty())
            assertNull(state.prompt)
            coVerify(exactly = 0) { gate.switchTo(any(), any(), any(), any()) }
            assertTrue(published.isEmpty())
            assertTrue(navigated.isEmpty())
        }

    @Test
    fun `a wrong PIN answered after the prompt was dismissed never brings the prompt back`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock
            viewModel.onProfileSelected(olderKid)
            val checked = CompletableDeferred<Unit>()
            parentPinCheckedWhen(checked, PinCheckResult.Wrong(3))

            viewModel.submitPin("0000")
            viewModel.dismissPrompt()
            checked.complete(Unit)

            assertNull(state.prompt)
        }

    @Test
    fun `a newer pick while a parent PIN is checked leaves the older pick without an unlock or a switch`() =
        runTest(dispatcher) {
            coEvery { gate.evaluate(olderKid) } returns GateDecision.NeedsParentUnlock andThen GateDecision.Allow
            viewModel.onProfileSelected(olderKid)
            val checked = CompletableDeferred<Unit>()
            parentPinCheckedWhen(checked, PinCheckResult.Correct)

            viewModel.submitPin("1234")
            viewModel.onProfileSelected(kid)
            checked.complete(Unit)

            assertTrue(granted.isEmpty())
            assertEquals(listOf(CurrentUser(server, kid)), navigated)
            coVerify(exactly = 0) { gate.switchTo(any(), olderKid, any(), any()) }
        }

    @Test
    fun `a delete PIN dismissed while it is checked removes nothing`() =
        runTest(dispatcher) {
            viewModel.removeUser(parent)
            val checked = CompletableDeferred<Unit>()
            parentPinCheckedWhen(checked, PinCheckResult.Correct)

            viewModel.submitPin("1234")
            viewModel.dismissPrompt()
            checked.complete(Unit)

            assertTrue(granted.isEmpty())
            coVerify(exactly = 0) { serverRepository.removeUser(any(), any()) }
        }

    @Test
    fun `a Quick Connect approved after its dialog closed publishes nothing`() =
        runTest(dispatcher) {
            val approved = CompletableDeferred<Unit>()
            val kidAuth =
                AuthenticationResult(
                    user =
                        UserDto(
                            id = kid.id,
                            name = kid.name,
                            hasPassword = false,
                            hasConfiguredPassword = false,
                            hasConfiguredEasyPassword = false,
                            policy = mockk<UserPolicy> { every { maxParentalRating } returns 10 },
                        ),
                    accessToken = "kid-token",
                    serverId = serverId.toServerString(),
                )
            coEvery { serverRepository.changeUser(server.url, kidAuth, any(), any(), any(), any(), any(), any()) } coAnswers {
                publish(CurrentUser(server, kid), arg(6))
            }
            viewModel.signIn(server.url, existingUser = null, onError = {}) {
                withContext(NonCancellable) { approved.await() }
                kidAuth
            }

            viewModel.cancelQuickConnect()
            approved.complete(Unit)

            assertTrue(published.isEmpty())
            assertTrue(navigated.isEmpty())
        }

    /** The switch to [user] reaches its publishing step only once [requests] completes */
    private fun switchHeldUntil(
        user: JellyfinUser,
        requests: CompletableDeferred<Unit>,
    ) {
        coEvery { gate.switchTo(server, user, any(), any()) } coAnswers {
            withContext(NonCancellable) { requests.await() }
            publish(CurrentUser(server, user), thirdArg())
        }
    }

    @Test
    fun `opening Add kid or Add User while a pick is still switching never publishes or navigates behind it`() =
        runTest(dispatcher) {
            val requests = CompletableDeferred<Unit>()
            switchHeldUntil(olderKid, requests)
            viewModel.onProfileSelected(olderKid)
            var unlocksCleared = 0
            every { gate.clearParentUnlock() } answers { unlocksCleared++ }

            // What SwitchUserContent calls before either dialog shows
            viewModel.leaveForOtherAction()
            requests.complete(Unit)

            assertTrue(published.isEmpty())
            assertTrue(navigated.isEmpty())
            assertEquals("the left pick's unlock goes with it", 1, unlocksCleared)
        }

    @Test
    fun `the current flow still publishes, grants and navigates`() =
        runTest(dispatcher) {
            val (auth, adult) = signInPinlessAdult()
            coEvery { serverRepository.changeUser(server.url, auth, null, any(), "LRDU", any(), any(), any()) } coAnswers {
                publish(adult, arg(6))
            }

            viewModel.completePinSetup("LRDU")

            assertEquals(listOf(adult), published)
            assertEquals(listOf(adult), navigated)
            assertEquals(listOf<UnlockPurpose?>(null), granted)
            assertTrue(withdrawn.isEmpty())
            assertNull(state.pendingPinSetup)
        }
}
