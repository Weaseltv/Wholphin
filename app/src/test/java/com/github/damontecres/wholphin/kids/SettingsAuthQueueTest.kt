package com.github.damontecres.wholphin.kids

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Review round 10: the PIN and login changes made on the Settings screen are saved one at a time, in the order they
 * were made, so an earlier one never lands after a later one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAuthQueueTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gate = mockk<KidSessionGateImpl>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val chooserLock = mockk<ProfileChooserLock>(relaxed = true)

    private val parent =
        JellyfinUser(
            rowId = 1,
            id = UUID.randomUUID(),
            name = "parent",
            serverId = UUID.randomUUID(),
            accessToken = "token",
            policyState = PolicyState.ADULT,
        )

    /** Each save as (pin, requireLogin), when it started and when it finished */
    private val started = mutableListOf<Pair<String?, Boolean>>()
    private val finished = mutableListOf<Pair<String?, Boolean>>()

    /** Barriers the saves wait for, in order; a save with none finishes at once */
    private val barriers = ArrayDeque<CompletableDeferred<Unit>>()

    private lateinit var viewModel: PreferencesViewModel

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        every { serverRepository.currentUserFlow } returns flowOf(parent)
        every { serverRepository.currentUserDtoFlow } returns MutableStateFlow(null)
        every { serverRepository.currentUser } returns parent
        every { gate.isDeviceProtected } returns MutableStateFlow(true)
        every { chooserLock.multiProfile } returns MutableStateFlow(true)
        coEvery { serverRepository.updateUserAuth(any(), any(), any()) } coAnswers {
            val save = secondArg<String?>() to thirdArg<Boolean>()
            started += save
            barriers.removeFirstOrNull()?.await()
            finished += save
        }
        viewModel =
            PreferencesViewModel(
                context = mockk<Context>(relaxed = true),
                api = mockk(relaxed = true),
                preferenceDataStore = mockk(relaxed = true),
                navigationManager = mockk(relaxed = true),
                backdropService = mockk(relaxed = true),
                screensaverService = mockk(relaxed = true),
                serverRepository = serverRepository,
                seerrServerRepository = mockk(relaxed = true),
                updateChecker = mockk(relaxed = true),
                serverReportService = mockk(relaxed = true),
                kidSessionGate = gate,
                profileChooserLock = chooserLock,
                accountPins = localAccountPins(serverRepository),
            )
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `a change waits for the one made before it`() =
        runTest(dispatcher) {
            val first = CompletableDeferred<Unit>()
            barriers += first

            viewModel.removeLoginAndPin(parent)
            viewModel.setPin(parent, "URDL")
            viewModel.setRequireLogin(parent)

            assertEquals("a later change started before the earlier one was saved", listOf<Pair<String?, Boolean>>(null to false), started)
            first.complete(Unit)
            val expected = listOf(null to false, "URDL" to false, null to true)
            assertEquals(expected, started)
            assertEquals(expected, finished)
        }

    @Test
    fun `changes made one after another are all saved`() =
        runTest(dispatcher) {
            viewModel.setPin(parent, "URDL")
            viewModel.setPin(parent.copy(pin = "URDL"), "DDUU")
            viewModel.setRequireLogin(parent.copy(pin = "DDUU"))
            viewModel.removeLoginAndPin(parent)

            assertEquals(listOf("URDL" to false, "DDUU" to false, null to true, null to false), finished)
        }
}
