package com.github.damontecres.wholphin.kids

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.IntentResult
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setup.ChooserPrompt
import com.github.damontecres.wholphin.ui.setup.PinPurpose
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * Review round 6: a session a chooser flow published but never entered (the flow was left before it navigated) is
 * withdrawn before any other switch is evaluated or published, so a replacement pick never inherits its ceiling.
 * Real [ServerRepository], [KidSessionGateImpl], [ProfileChooserLock] and [SwitchUserViewModel] on one
 * [StandardTestDispatcher] with an in-memory store; the request a flow waits on is a barrier that ignores
 * cancellation, as a request already on the wire would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbandonedPublicationTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val jellyfin = mockk<Jellyfin>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val sharedApi = mockk<ApiClient>(relaxed = true)
    private val systemApi = mockk<SystemApi>()
    private val setupNavigationManager = mockk<SetupNavigationManager>(relaxed = true)
    private val navigationManager = mockk<NavigationManager>(relaxed = true)
    private val intentService = mockk<IntentService>()
    private val keyValue = FakePreferencesDataStore()
    private val appPreferences = mockk<DataStore<AppPreferences>>()
    private lateinit var store: KidProtectionStore
    private lateinit var repository: ServerRepository

    private val serverId = UUID.randomUUID()
    private val server = JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")
    private var now = 1_000_000L

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

    /** The pinless Teens profile */
    private val teen = profile(5, PolicyState.KID, 17)
    private val rows = listOf(parent, kid, olderKid, teen)

    /** Sessions the chooser navigated into (the setup destination) */
    private val navigated = mutableListOf<CurrentUser>()

    private fun dto(user: JellyfinUser) =
        UserDto(
            id = user.id,
            name = user.name,
            serverName = "test server",
            hasPassword = true,
            hasConfiguredPassword = true,
            hasConfiguredEasyPassword = false,
            policy = mockk<UserPolicy> { every { maxParentalRating } returns user.maxParentalRating },
        )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(dispatcher)
        store = KidProtectionStore(keyValue)
        coEvery { appPreferences.updateData(any()) } returns AppPreferences.getDefaultInstance()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        // Each profile's own scoped client fetches that profile's policy
        rows.forEach { row ->
            val scopedApi = mockk<ApiClient>()
            val userApi = mockk<UserApi>()
            every { jellyfin.createApi(any(), row.accessToken, any(), any(), any()) } returns scopedApi
            every { scopedApi.userApi } returns userApi
            every { scopedApi.systemApi } returns systemApi
            coEvery { userApi.getCurrentUser() } returns successResponse(dto(row))
        }
        coEvery { systemApi.getPublicSystemInfo() } returns
            successResponse(PublicSystemInfo(id = serverId.toServerString(), serverName = "test server", version = "10.11.11"))
        every { serverDao.addOrUpdateServer(any()) } just Runs
        every { serverDao.addOrUpdateUser(any()) } answers { firstArg() }
        every { serverDao.updateUserPolicy(any(), any(), any()) } just Runs
        every { serverDao.getUser(any(), any()) } returns null
        rows.forEach { every { serverDao.getUser(serverId, it.id) } returns it }
        every { serverDao.getServer(serverId) } returns JellyfinServerUsers(server, rows)
        every { serverDao.getUsersFlow(serverId) } returns flowOf(rows)
        every { serverDao.countKidUsers() } returns 3
        every { setupNavigationManager.navigateTo(any()) } answers {
            (firstArg<SetupDestination>() as? SetupDestination.AppContent)?.let { navigated += it.current }
        }
        every { navigationManager.backStack } returns mutableListOf()
        repository = ServerRepository(context, jellyfin, serverDao, sharedApi, appPreferences, dispatcher, store)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun TestScope.gate() = KidSessionGateImpl(repository, serverDao, store, backgroundScope, dispatcher).also { it.clock = { now } }

    private suspend fun protect(sessionCeiling: ProfileCeiling) {
        store.markProtected(sessionCeiling)
        store.setSessionCeiling(sessionCeiling)
    }

    private fun TestScope.chooser(gate: KidSessionGateImpl): Pair<SwitchUserViewModel, ProfileChooserLock> {
        val lock =
            ProfileChooserLock(
                repository,
                serverDao,
                setupNavigationManager,
                navigationManager,
                { intentService },
                backgroundScope,
                dispatcher,
            )
        val viewModel =
            SwitchUserViewModel(
                context = context,
                jellyfin = jellyfin,
                serverRepository = repository,
                serverDao = serverDao,
                navigationManager = navigationManager,
                setupNavigationManager = setupNavigationManager,
                imageUrlService = mockk(relaxed = true),
                kidSessionGate = gate,
                kidProtectionStore = store,
                profileChooserLock = lock,
                server = server,
            )
        return viewModel to lock
    }

    /** A launcher tile of the parent's, held until a profile is picked; parsing it waits for [parsed] */
    private fun holdParentTile(
        lock: ProfileChooserLock,
        parsed: CompletableDeferred<Unit>,
    ): Intent {
        val intent = mockk<Intent>()
        coEvery { intentService.parseIntent(intent, true) } coAnswers {
            withContext(NonCancellable) { parsed.await() }
            IntentResult.Target(listOf(Destination.Favorites), addHomeToBackStack = false)
        }
        lock.hold(intent, ownerUserId = parent.id)
        return intent
    }

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try {
            block()
            error("should have been refused")
        } catch (ex: ProfileGateException) {
            assertEquals(GateDecision.NeedsParentUnlock, ex.decision)
        }
    }

    @Test
    fun `a pinless Teens pick after an abandoned adult pick needs a parent, never the adult's ceiling`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val (viewModel, lock) = chooser(gate)
            val parsed = CompletableDeferred<Unit>()
            holdParentTile(lock, parsed)

            // Adult flow A: the parent's own PIN, then the switch publishes the parent (unlimited); its held tile is
            // still being parsed, so it hasn't navigated
            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(parent.id, repository.currentUser?.id)
            // Not entered yet: its switch is still journalled, so the ceiling reads unknown (review round 7)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())

            // The owner abandons A and picks the pinless Teens profile; A's request finishes afterwards
            viewModel.onProfileSelected(teen)
            runCurrent()
            parsed.complete(Unit)
            runCurrent()

            assertEquals(
                "the Teens profile needs a parent's PIN",
                ChooserPrompt.Pin(target = teen, pinOwner = parent, purpose = PinPurpose.SWITCH),
                viewModel.state.value.prompt,
            )
            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertTrue(navigated.isEmpty())
            assertTrue("A's tile stays held", lock.held.value != null)

            // With the parent's PIN the Teens profile opens, at its own ceiling
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(listOf(teen.id), navigated.map { it.user.id })
            assertEquals(teen.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(17), store.sessionCeiling())
        }

    /** Publishes [user] as a chooser flow ([owner]) would, without navigating into it */
    private suspend fun publishUnentered(
        user: JellyfinUser,
        owner: Any,
    ): CurrentUser = repository.changeUser(server, user, authorize = {}, entryOwner = owner)

    @Test
    fun `a switch is gated against the ceiling from before an unentered publication, in the same lock`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val ownerA = Any()
            val adult = publishUnentered(parent, ownerA)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())

            // B's switch, before A's own withdrawal ran: its fresh gate check sees the ceiling from before A
            var seen: ProfileCeiling? = null
            assertRefused {
                repository.changeUser(server, teen, beforePublish = {
                    seen = store.sessionCeiling()
                    throw ProfileGateException(GateDecision.NeedsParentUnlock)
                }, authorize = {}, entryOwner = Any())
            }
            assertEquals(ProfileCeiling.Rating(10), seen)
            assertNull(repository.current.value)
            // A can't be entered any more
            assertFalse(repository.markEntered(ownerA, adult))

            // Through the gate: refused without a parent, at the ceiling from before A
            assertRefused { gate.switchTo(server, teen, entryOwner = Any()) }
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())

            // A's late withdrawal changes nothing; a parent's unlock opens the Teens profile at its own ceiling
            repository.endSessionForChooser(adult)
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(teen.rowId)))
            val ownerB = Any()
            val teens = gate.switchTo(server, teen, entryOwner = ownerB)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            repository.endSessionForChooser(adult)
            assertTrue(repository.markEntered(ownerB, teens))
            repository.finishEntry()
            assertEquals(teen.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(17), store.sessionCeiling())
        }

    @Test
    fun `a gate evaluation withdraws an unentered publication first`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            publishUnentered(parent, Any())

            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `an intent never restores an unentered session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            publishUnentered(parent, Any())
            val gate = gate()

            // The abandoned adult's own deep link: not restored
            assertNull(gate.restoreGated(serverId, parent.id))
            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())

            // Another adult's flow abandoned; the Teens profile's link is gated against the ceiling from before it
            publishUnentered(parent, Any())
            assertNull(gate.restoreGated(serverId, teen.id))
            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `an entered publication stays and its ceiling applies to the next switch`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val owner = Any()
            val adult = publishUnentered(parent, owner)
            assertTrue(repository.markEntered(owner, adult))
            repository.finishEntry()
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())

            // From the parent's session, the Teens profile opens freely
            val gate = gate()
            assertEquals(GateDecision.Allow, gate.evaluate(teen))
            assertEquals(parent.id, repository.currentUser?.id)
            gate.switchTo(server, teen)
            assertEquals(teen.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(17), store.sessionCeiling())
        }

    @Test
    fun `an adult pick on the chooser enters its session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val (viewModel, _) = chooser(gate)

            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()

            assertEquals(listOf(parent.id), navigated.map { it.user.id })
            // Entered: the next evaluation keeps it
            assertEquals(GateDecision.Allow, gate.evaluate(teen))
            assertEquals(parent.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    @Test
    fun `a single profile opened automatically enters its session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val (viewModel, _) = chooser(gate)

            // What SwitchUserContent does when only one profile is saved
            viewModel.trySwitchUser(kid)
            runCurrent()

            assertEquals(listOf(kid.id), navigated.map { it.user.id })
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(olderKid))
            assertEquals(kid.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `a held tile continued for its owner enters its session`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val (viewModel, lock) = chooser(gate)
            val backStack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backStack
            val parsed = CompletableDeferred<Unit>()
            holdParentTile(lock, parsed)
            parsed.complete(Unit)

            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()

            assertEquals(listOf(parent.id), navigated.map { it.user.id })
            assertTrue(backStack.contains(Destination.Favorites))
            assertNull(lock.held.value)
            assertEquals(GateDecision.Allow, gate.evaluate(teen))
            assertEquals(parent.id, repository.currentUser?.id)
        }

    /** Process death (review round 7): the store and coordinator start over; only the key-value storage survives */
    private fun restart() {
        navigated.clear()
        store = KidProtectionStore(keyValue)
        repository = ServerRepository(context, jellyfin, serverDao, sharedApi, appPreferences, dispatcher, store)
    }

    @Test
    fun `an adult published but not entered before the process died leaves the TV gated`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val (viewModel, lock) = chooser(gate())
            holdParentTile(lock, CompletableDeferred())

            // The parent's PIN publishes the parent; parsing its held tile delays navigation, and the process dies
            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(parent.id, repository.currentUser?.id)
            assertTrue(navigated.isEmpty())
            restart()

            // Never the adult's ceiling: gated (unknown), so the pinless Teens profile needs a parent
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            val gate = gate()
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(teen))
            val (chooser, _) = chooser(gate)
            chooser.onProfileSelected(teen)
            runCurrent()
            assertEquals(
                ChooserPrompt.Pin(target = teen, pinOwner = parent, purpose = PinPurpose.SWITCH),
                chooser.state.value.prompt,
            )
            assertNull(repository.current.value)
            assertTrue(navigated.isEmpty())

            // With the parent's PIN it opens, at its own ceiling, and that survives the next restart
            chooser.submitPin("1234")
            runCurrent()
            assertEquals(listOf(teen.id), navigated.map { it.user.id })
            restart()
            assertEquals(ProfileCeiling.Rating(17), store.sessionCeiling())
        }

    @Test
    fun `a publication marked entered but not yet recorded restarts gated`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val owner = Any()
            assertTrue(repository.markEntered(owner, publishUnentered(parent, owner)))
            restart()

            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
        }

    @Test
    fun `an entry not yet recorded is recorded before the next gate evaluation`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val owner = Any()
            assertTrue(repository.markEntered(owner, publishUnentered(parent, owner)))

            // The entered session stays, at its own ceiling
            assertEquals(GateDecision.Allow, gate().evaluate(teen))
            assertEquals(parent.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    @Test
    fun `a withdrawn publication leaves the ceiling from before it, also after a restart`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            publishUnentered(parent, Any())

            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            assertEquals(GateDecision.Allow, gate().evaluate(kid))
        }

    @Test
    fun `an ownerless publication completes its switch when it publishes`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(14))
            // Cold-start and intent restores publish without a chooser flow to enter them
            assertEquals(kid.id, gate().restoreGated(serverId, kid.id)?.user?.id)
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    @Test
    fun `an entered adult pick keeps its ceiling across a restart`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val (viewModel, _) = chooser(gate())

            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(listOf(parent.id), navigated.map { it.user.id })
            restart()

            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
            assertEquals(GateDecision.Allow, gate().evaluate(teen))
        }

    @Test
    fun `a held tile continued for its owner keeps its ceiling across a restart`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val (viewModel, lock) = chooser(gate())
            every { navigationManager.backStack } returns mutableListOf()
            val parsed = CompletableDeferred<Unit>()
            holdParentTile(lock, parsed)

            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()
            // Not entered yet: the journal is still open
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            parsed.complete(Unit)
            runCurrent()
            assertEquals(listOf(parent.id), navigated.map { it.user.id })
            restart()

            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    @Test
    fun `a single profile opened automatically keeps its ceiling across a restart`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(14))
            val (viewModel, _) = chooser(gate())

            viewModel.trySwitchUser(kid)
            runCurrent()
            assertEquals(listOf(kid.id), navigated.map { it.user.id })
            restart()

            assertEquals(ProfileCeiling.Rating(10), store.sessionCeiling())
        }

    /**
     * Review round 8: what an older app version left behind when it died mid-switch, the round 2 format: a pending
     * flag without a switch id, over the entered adult's stored ceiling
     */
    private suspend fun legacyPendingRecord() =
        keyValue.edit {
            it[booleanPreferencesKey("kids.protection")] = true
            it[stringPreferencesKey("kids.sessionCeiling")] = "unlimited"
            it[booleanPreferencesKey("kids.switchPending")] = true
        }

    /** Review round 8: an entered adult, then a chooser publishes the Kids profile and dies before entering it */
    private suspend fun heldRecordOfDeadProcess() {
        protect(ProfileCeiling.Unlimited)
        publishUnentered(kid, Any())
        restart()
    }

    /** Fails the next save of the current profile, a step every switch takes after its journal began */
    private fun failNextSave() {
        var fail = true
        coEvery { appPreferences.updateData(any()) } answers {
            if (fail) {
                fail = false
                throw IOException("disk full")
            }
            AppPreferences.getDefaultInstance()
        }
    }

    @Test
    fun `an authorized recovery switch that fails after a held publication died keeps the TV gated`() =
        runTest(dispatcher) {
            heldRecordOfDeadProcess()
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            val gate = gate()
            val (chooser, _) = chooser(gate)

            // A parent authorizes the Older kids profile; its switch fails after it was journalled, before publishing
            chooser.onProfileSelected(olderKid)
            runCurrent()
            failNextSave()
            chooser.submitPin("1234")
            runCurrent()
            assertNull(repository.current.value)
            assertTrue(navigated.isEmpty())

            // Never the adult's ceiling the dead switch hid: the pinless Teens profile still needs a parent
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            chooser.onProfileSelected(teen)
            runCurrent()
            assertEquals(
                ChooserPrompt.Pin(target = teen, pinOwner = parent, purpose = PinPurpose.SWITCH),
                chooser.state.value.prompt,
            )
            assertTrue(navigated.isEmpty())
            restart()
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
        }

    @Test
    fun `an authorized recovery switch cancelled after a held publication died keeps the TV gated`() =
        runTest(dispatcher) {
            heldRecordOfDeadProcess()
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)))
            val saving = CompletableDeferred<Unit>()
            coEvery { appPreferences.updateData(any()) } coAnswers {
                saving.await()
                AppPreferences.getDefaultInstance()
            }

            val switch = launch { gate.switchTo(server, olderKid, entryOwner = Any()) }
            runCurrent()
            switch.cancel()
            runCurrent()

            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(teen))
            restart()
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
        }

    @Test
    fun `an authorized switch that fails after an older version's unfinished switch keeps the TV gated`() =
        runTest(dispatcher) {
            legacyPendingRecord()
            restart()
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)))
            failNextSave()

            try {
                gate.switchTo(server, olderKid)
                error("should have failed")
            } catch (_: IOException) {
            }

            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(teen))
            restart()
            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
        }

    @Test
    fun `an authorized switch cancelled after an older version's unfinished switch keeps the TV gated`() =
        runTest(dispatcher) {
            legacyPendingRecord()
            restart()
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)))
            val saving = CompletableDeferred<Unit>()
            coEvery { appPreferences.updateData(any()) } coAnswers {
                saving.await()
                AppPreferences.getDefaultInstance()
            }

            val switch = launch { gate.switchTo(server, olderKid, entryOwner = Any()) }
            runCurrent()
            switch.cancel()
            runCurrent()

            assertNull(repository.current.value)
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate.evaluate(teen))
        }

    @Test
    fun `a successful recovery switch applies its destination's ceiling`() =
        runTest(dispatcher) {
            heldRecordOfDeadProcess()
            val (chooser, _) = chooser(gate())

            chooser.onProfileSelected(olderKid)
            runCurrent()
            chooser.submitPin("1234")
            runCurrent()

            assertEquals(listOf(olderKid.id), navigated.map { it.user.id })
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
            assertEquals(GateDecision.NeedsParentUnlock, gate().evaluate(teen))
            assertEquals(GateDecision.Allow, gate().evaluate(kid))
        }

    @Test
    fun `a successful switch after an older version's unfinished switch applies its destination's ceiling`() =
        runTest(dispatcher) {
            legacyPendingRecord()
            restart()
            val gate = gate()
            assertEquals(PinCheckResult.Correct, gate.verifyParentPin(parent, "1234", UnlockPurpose.Switch(olderKid.rowId)))

            gate.switchTo(server, olderKid)

            assertEquals(olderKid.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Rating(14), store.sessionCeiling())
        }

    /** Fails the next write of the key-value storage that records [ceiling] with no switch journalled */
    private fun failNextFinalization(ceiling: String) {
        var fail = true
        keyValue.beforeUpdate = {
            if (fail && it[stringPreferencesKey("kids.sessionCeiling")] == ceiling && it[stringPreferencesKey("kids.switchId")] == null) {
                fail = false
                throw IOException("disk full")
            }
        }
    }

    @Test
    fun `an entry whose recording failed is recorded by the next gate evaluation`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val owner = Any()
            assertTrue(repository.markEntered(owner, publishUnentered(parent, owner)))
            failNextFinalization("unlimited")

            repository.finishEntry()
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())

            // The backstop records it: the entered adult session stays, at its own ceiling
            assertEquals(GateDecision.Allow, gate().evaluate(teen))
            assertEquals(parent.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
            restart()
            assertEquals(ProfileCeiling.Unlimited, store.sessionCeiling())
        }

    @Test
    fun `an adult pick whose entry failed to record is recorded before the next switch`() =
        runTest(dispatcher) {
            protect(ProfileCeiling.Rating(10))
            val gate = gate()
            val (viewModel, _) = chooser(gate)
            failNextFinalization("unlimited")

            viewModel.onProfileSelected(parent)
            runCurrent()
            viewModel.submitPin("1234")
            runCurrent()
            assertEquals(listOf(parent.id), navigated.map { it.user.id })
            assertEquals(ProfileCeiling.Unknown, store.sessionCeiling())

            // From the entered parent's session the Teens profile opens without a PIN, at its own ceiling
            gate.switchTo(server, teen)
            assertEquals(teen.id, repository.currentUser?.id)
            assertEquals(ProfileCeiling.Rating(17), store.sessionCeiling())
        }
}
