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
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The chooser's selection (review round 3): one selection from the pick to navigation, and the latest one wins */
@OptIn(ExperimentalCoroutinesApi::class)
class ChooserSelectionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val serverDao = mockk<JellyfinServerDao>()
    private val gate = mockk<KidSessionGateImpl>(relaxed = true)
    private val store = mockk<KidProtectionStore>()
    private val chooserLock = mockk<ProfileChooserLock>(relaxed = true)

    private val serverId = UUID.randomUUID()
    private val server = server(serverId)

    private fun profile(
        rowId: Int,
        rating: Int,
    ) = JellyfinUser(
        rowId = rowId,
        id = UUID.randomUUID(),
        name = "user$rowId",
        serverId = serverId,
        accessToken = "token$rowId",
        policyState = PolicyState.KID,
        maxParentalRating = rating,
    )

    private val kid = profile(3, 10)
    private val olderKid = profile(4, 14)

    private lateinit var viewModel: SwitchUserViewModel

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server, listOf(kid, olderKid))
        coEvery { store.isProtected() } returns true
        viewModel =
            SwitchUserViewModel(
                context = mockk<Context>(relaxed = true),
                jellyfin = mockk(relaxed = true),
                serverRepository = mockk<ServerRepository>(relaxed = true),
                serverDao = serverDao,
                navigationManager = mockk(relaxed = true),
                setupNavigationManager = mockk(relaxed = true),
                imageUrlService = mockk(relaxed = true),
                kidSessionGate = gate,
                kidProtectionStore = store,
                profileChooserLock = chooserLock,
                accountPins = localAccountPins(),
                accountAvatars = mockk(relaxed = true),
                pictures = mockk(relaxed = true),
                savedProfileChecks = mockk(relaxed = true),
                networkAvailability = onlineNetwork(),
                walkthroughService = mockk(relaxed = true),
                server = server,
            )
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `an older pick still in the gate never cancels or overrides a newer pick's switch`() =
        runTest(dispatcher) {
            // The first pick's gate check is slow (protection read)...
            val olderGate = CompletableDeferred<GateDecision>()
            coEvery { gate.evaluate(kid) } coAnswers { olderGate.await() }
            // ...while the second pick is already switching
            coEvery { gate.evaluate(olderKid) } returns GateDecision.Allow
            val newerSwitch = CompletableDeferred<Unit>()
            coEvery { gate.switchTo(server, olderKid, any(), any()) } coAnswers {
                newerSwitch.await()
                CurrentUser(server, olderKid)
            }
            coEvery { gate.switchTo(server, kid, any(), any()) } returns CurrentUser(server, kid)

            viewModel.onProfileSelected(kid)
            viewModel.onProfileSelected(olderKid)
            olderGate.complete(GateDecision.Allow)
            newerSwitch.complete(Unit)

            coVerify(exactly = 0) { gate.switchTo(server, kid, any(), any()) }
            coVerify(exactly = 0) { chooserLock.navigateAfterSwitch(CurrentUser(server, kid), any()) }
            coVerify(exactly = 1) { chooserLock.navigateAfterSwitch(CurrentUser(server, olderKid), any()) }
        }
}
