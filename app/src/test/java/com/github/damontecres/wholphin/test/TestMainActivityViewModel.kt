package com.github.damontecres.wholphin.test

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.MainActivityViewModel
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.ProfileChooserLock
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.services.AppUpgradeHandler
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DeviceProfileService
import com.github.damontecres.wholphin.services.IntentResult
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.UUID
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestMainActivityViewModel {
    private val testDispatcher = StandardTestDispatcher()

    private val context: Context = mockk()
    private val preferences: DataStore<AppPreferences> = mockk()
    private val serverRepository: ServerRepository = mockk()
    private val setupNavigationManager: SetupNavigationManager = mockk(relaxed = true)
    private val navigationManager: NavigationManager = mockk(relaxed = true)
    private val deviceProfileService: DeviceProfileService = mockk()
    private val backdropService: BackdropService = mockk(relaxed = true)
    private val appUpgradeHandler: AppUpgradeHandler = mockk()
    private val intentService: IntentService = mockk()
    private val serverDao: JellyfinServerDao = mockk()
    private val kidSessionGate: KidSessionGateImpl = mockk()
    private val profileChooserLock: ProfileChooserLock = mockk(relaxed = true)

    val viewModel =
        MainActivityViewModel(
            context,
            preferences,
            serverRepository,
            setupNavigationManager,
            navigationManager,
            deviceProfileService,
            backdropService,
            appUpgradeHandler,
            intentService,
            kidSessionGate,
            profileChooserLock,
        )

    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val currentUser = currentUser(serverId, userId)

    private val protectedCurrentUser =
        CurrentUser(
            server(serverId),
            user(serverId, userId).copy(pin = "1234"),
        )

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        every { appUpgradeHandler.needUpgrade() } returns false
        every { appUpgradeHandler.copySubfont(any()) } returns Unit
        every { serverRepository.serverDao } returns serverDao
        // Kid profiles (weaselfin): a single-profile TV unless a test says otherwise
        coEvery { profileChooserLock.appliesTo(any()) } returns false
        every { profileChooserLock.lastProfile } returns null
        coEvery { kidSessionGate.restoreGated(any(), any()) } returns null
        every { intentService.isNormalLaunch(any()) } returns false
        coEvery { serverDao.getServer(serverId) } returns
            JellyfinServerUsers(
                currentUser.server,
                // User list is unused
                emptyList(),
            )
        mockkStatic(Toast::class)
        val mockToast = mockk<Toast>()
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockToast
        every { mockToast.show() } returns Unit
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
        unmockkStatic(Toast::class)
    }

    private fun setupPreferences(block: AppPreferences.Builder.() -> Unit) {
        every { preferences.data } returns
            flow {
                emit(
                    AppPreferences.getDefaultInstance().update(block),
                )
            }
    }

    @Test
    fun `Test auto sign in with unprotected profile`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { kidSessionGate.restoreGated(serverId, userId) } returns currentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
        }

    @Test
    fun `Test auto sign disabled in with current server`() =
        runTest {
            setupPreferences {
                signInAutomatically = false
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
//            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            verify(exactly = 1) { serverDao.getServer(serverId) }
            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            val args = mutableListOf<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(args)) }
            assertEquals(2, args.size)
            args[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.Loading)
            }
            args[1].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, hot load, with protected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(protectedCurrentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
            verify { serverDao.getServer(serverId) }
            val args = mutableListOf<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(args)) }
            assertEquals(2, args.size)
            args[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.Loading)
            }
            args[1].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, cold load, with protected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            if (BuildConfig.KID_PROFILES_ENABLED) {
                // Kid profiles: the saved row is checked before restoring, so a PIN profile never restores
                coVerify(exactly = 1) { kidSessionGate.restoreGated(serverId, userId) }
                coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            } else {
                coVerify(exactly = 1) { serverRepository.restoreSession(serverId, userId) }
            }
            val args = mutableListOf<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(args)) }
            args.last().let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, no current user`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = ""
                currentUserId = ""
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.restoreSession(null, null) } returns null

            viewModel.appStart(null)
            advanceUntilIdle()

            if (BuildConfig.KID_PROFILES_ENABLED) {
                coVerify(exactly = 1) { kidSessionGate.restoreGated(null, null) }
            } else {
                coVerify(exactly = 1) { serverRepository.restoreSession(null, null) }
            }
            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }

    @Test
    fun `Test intent result is noop`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.NoOp
            every { intentService.isNormalLaunch(any()) } returns true
            coEvery { kidSessionGate.restoreGated(serverId, userId) } returns currentUser

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
        }

    @Test
    fun `Test intent result is error, has server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.Error("Error")

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test intent result is error, does not have server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.Error("Error")

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }

    @Test
    fun `Test intent result is target, has server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Favorites),
                    addHomeToBackStack = true,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
            // Note: navigation manager internally handles Home destination, so it won't be in this list
            assertEquals(1, backstack.size)
            backstack[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is Destination.Favorites)
            }
        }

    @Test
    fun `Test intent result is target, has server, no home backstack`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Favorites),
                    addHomeToBackStack = false,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify(exactly = 0) { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
            assertEquals(1, backstack.size)
            backstack[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is Destination.Favorites)
            }
        }

    @Test
    fun `Test intent result is target, has no server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Favorites),
                    addHomeToBackStack = true,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify(exactly = 0) { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }
    // Kid profiles: "always return to the profile chooser" (plan 4.5)

    private fun assumeKidProfiles() = Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)

    private fun setupMultiProfile() {
        coEvery { profileChooserLock.appliesTo(serverId) } returns true
        setupPreferences {
            signInAutomatically = true
            currentServerId = serverId.toServerString()
            currentUserId = userId.toServerString()
        }
    }

    private fun capturedDestinations(): List<SetupDestination> {
        val args = mutableListOf<SetupDestination>()
        verify { setupNavigationManager.navigateTo(capture(args)) }
        return args
    }

    @Test
    fun `Chooser lock - cold start with more than one profile shows the chooser without restoring`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupMultiProfile()
            every { serverRepository.current } returns MutableStateFlow(null)

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 0) { kidSessionGate.restoreGated(any(), any()) }
            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.UserList)
        }

    @Test
    fun `Chooser lock - background return shows the chooser`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupMultiProfile()
            every { serverRepository.current } returns MutableStateFlow(currentUser)

            viewModel.appStart(null, fromBackground = true, exemptReturn = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { kidSessionGate.restoreGated(any(), any()) }
            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.UserList)
        }

    @Test
    fun `Chooser lock - external player return is exempt`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupMultiProfile()
            every { serverRepository.current } returns MutableStateFlow(currentUser)

            viewModel.appStart(null, fromBackground = true, exemptReturn = true)
            advanceUntilIdle()

            verify(exactly = 0) { setupNavigationManager.navigateTo(any()) }
        }

    @Test
    fun `Chooser lock - single profile TV keeps auto sign in`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { kidSessionGate.restoreGated(serverId, userId) } returns currentUser

            viewModel.appStart(null, fromBackground = true, exemptReturn = false)
            advanceUntilIdle()

            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.AppContent)
        }

    @Test
    fun `Chooser lock - in-app screensaver wake shows the chooser`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            every { serverRepository.current } returns MutableStateFlow(currentUser)

            viewModel.lockToChooser()
            advanceUntilIdle()

            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.UserList)
        }

    @Test
    fun `Chooser lock - deep link is held until a profile is picked`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupMultiProfile()
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            val intent = Intent()
            every { intentService.intentUserId(intent) } returns null

            viewModel.appStart(intent)
            advanceUntilIdle()

            coVerify(exactly = 0) { intentService.parseIntent(any()) }
            verify(exactly = 1) { profileChooserLock.hold(intent, userId) }
            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.UserList)
        }

    @Test
    fun `Gate - cold start never restores a gated profile`() =
        runTest(testDispatcher) {
            assumeKidProfiles()
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(null)
            // The gate refused (eg a pinless adult on a protected TV)
            coEvery { kidSessionGate.restoreGated(serverId, userId) } returns null

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            val last = capturedDestinations().last()
            assertTrue("destination is ${last::class}", last is SetupDestination.UserList)
        }
}
