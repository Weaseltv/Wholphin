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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * "Turn off kid protection on this TV" from Settings (review round 5): a prompt dismissed while its PIN is checked, or
 * while protection is being cleared, grants nothing and turns nothing off. The requests are held by barriers that
 * ignore cancellation, so each late continuation really runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnOffProtectionTest {
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
            pin = "1234",
            policyState = PolicyState.ADULT,
        )

    /** Unlocks the gate granted (only through the submission's admission) */
    private val granted = mutableListOf<UnlockPurpose?>()

    /** Whether protection was cleared (its last check passed) */
    private var cleared = false

    /** What the prompt was told */
    private val results = mutableListOf<Pair<PinCheckResult, Boolean>>()

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
        pinCheckedWhen(CompletableDeferred(Unit))
        clearedWhen(CompletableDeferred(Unit))
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

    /** The parent's correct PIN is checked once [checked] completes; it grants only if its submission admits it */
    private fun pinCheckedWhen(checked: CompletableDeferred<Unit>) {
        coEvery { gate.verifyParentPin(parent, any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { checked.await() }
            val purpose = thirdArg<UnlockPurpose?>()
            arg<(() -> Unit) -> Unit>(3).invoke { granted += purpose }
            PinCheckResult.Correct
        }
    }

    /** Protection is cleared once [clearing] completes, if the gate's last check (with the prompt's) passes then */
    private fun clearedWhen(clearing: CompletableDeferred<Unit>) {
        coEvery { gate.turnOffProtection(any()) } coAnswers {
            withContext(NonCancellable) { clearing.await() }
            firstArg<() -> Boolean>().invoke().also { cleared = it }
        }
    }

    private fun submit() = viewModel.turnOffKidProtection(parent, "1234") { result, turnedOff -> results += result to turnedOff }

    @Test
    fun `a prompt dismissed while its PIN is checked grants nothing and turns nothing off`() =
        runTest(dispatcher) {
            val checked = CompletableDeferred<Unit>()
            pinCheckedWhen(checked)

            submit()
            viewModel.cancelTurnOffKidProtection()
            checked.complete(Unit)

            assertTrue(granted.isEmpty())
            assertFalse(cleared)
            assertTrue("a dismissed prompt is told nothing", results.isEmpty())
        }

    @Test
    fun `a prompt dismissed while protection is being cleared turns nothing off`() =
        runTest(dispatcher) {
            val clearing = CompletableDeferred<Unit>()
            clearedWhen(clearing)

            submit()
            viewModel.cancelTurnOffKidProtection()
            clearing.complete(Unit)

            assertFalse(cleared)
            assertTrue(results.isEmpty())
        }

    @Test
    fun `the open prompt still turns protection off`() =
        runTest(dispatcher) {
            submit()

            assertEquals(listOf<UnlockPurpose?>(UnlockPurpose.TurnOffProtection), granted)
            assertTrue(cleared)
            assertEquals(listOf(PinCheckResult.Correct to true), results)
        }
}
