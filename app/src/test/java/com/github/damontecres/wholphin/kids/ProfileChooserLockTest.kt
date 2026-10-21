package com.github.damontecres.wholphin.kids

import android.content.Intent
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.IntentResult
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.test.currentUser
import com.github.damontecres.wholphin.test.user
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** "Always return to the profile chooser" (plan 4.5): Play On lock, exempt returns, held deep links */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ProfileChooserLockTest {
    private val serverRepository = mockk<ServerRepository>()
    private val serverDao = mockk<JellyfinServerDao>()
    private val setupNavigationManager = mockk<SetupNavigationManager>(relaxed = true)
    private val navigationManager = mockk<NavigationManager>(relaxed = true)
    private val intentService = mockk<IntentService>()

    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val current = MutableStateFlow<CurrentUser?>(currentUser(serverId, userId))
    private val setupBackStack = mutableListOf<SetupDestination>()

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        WholphinDispatchers.configure(UnconfinedTestDispatcher())
        every { serverRepository.current } returns current
        every { setupNavigationManager.backStack } returns setupBackStack
        setupBackStack.add(SetupDestination.AppContent(current.value!!))
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun TestScope.lock(profiles: Int): ProfileChooserLock {
        val users: List<JellyfinUser> = List(profiles) { user(serverId) }
        every { serverDao.getUsersFlow(serverId) } returns flowOf(users)
        every { serverDao.countUsers(serverId) } returns profiles
        return ProfileChooserLock(
            serverRepository,
            serverDao,
            setupNavigationManager,
            navigationManager,
            { intentService },
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `Play On works only while a profile is in use`() =
        runTest(UnconfinedTestDispatcher()) {
            val lock = lock(profiles = 2)
            assertFalse(lock.isPlayOnLocked())

            // App in the background
            lock.onActivityStopped(externalPlayer = false)
            assertTrue(lock.isPlayOnLocked())
            lock.onActivityStarted()
            assertFalse(lock.isPlayOnLocked())

            // Chooser showing
            setupBackStack[0] = SetupDestination.UserList(current.value!!.server)
            assertTrue(lock.isPlayOnLocked())
        }

    @Test
    fun `single profile TV keeps Play On and auto sign in`() =
        runTest(UnconfinedTestDispatcher()) {
            val lock = lock(profiles = 1)
            lock.onActivityStopped(externalPlayer = false)
            assertFalse(lock.isPlayOnLocked())
            assertFalse(lock.appliesTo(serverId))
        }

    @Test
    fun `external player and transient activities are exempt, an overlay is not`() =
        runTest(UnconfinedTestDispatcher()) {
            val lock = lock(profiles = 2)
            assertTrue(lock.appliesTo(serverId))

            lock.onActivityStopped(externalPlayer = true)
            assertFalse(lock.inBackground)
            assertTrue(lock.consumeExemptReturn())
            assertFalse(lock.consumeExemptReturn())

            lock.onTransientLaunch()
            lock.onActivityStopped(externalPlayer = false)
            assertTrue(lock.consumeExemptReturn())
            lock.onActivityResumed()

            // A transient overlay that only paused the activity doesn't exempt a later Home press
            lock.onTransientLaunch()
            lock.onActivityResumed()
            lock.onActivityStopped(externalPlayer = false)
            assertTrue(lock.inBackground)
            assertFalse(lock.consumeExemptReturn())
        }

    @Test
    fun `held deep link continues only for the profile that owns it`() =
        runTest(UnconfinedTestDispatcher()) {
            val lock = lock(profiles = 2)
            val intent = Intent()
            coEvery { intentService.parseIntent(intent, true) } returns
                IntentResult.Target(listOf(Destination.Favorites), addHomeToBackStack = false)
            val backStack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backStack

            // Another profile was picked: its home, not the tile
            lock.hold(intent, ownerUserId = userId)
            val sibling = currentUser(serverId, UUID.randomUUID())
            lock.navigateAfterSwitch(sibling)
            coVerify(exactly = 0) { intentService.parseIntent(any(), any()) }
            verify { setupNavigationManager.navigateTo(SetupDestination.AppContent(sibling)) }
            assertTrue(backStack.isEmpty())

            // The owner was picked: the tile opens
            lock.hold(intent, ownerUserId = userId)
            lock.navigateAfterSwitch(current.value!!)
            coVerify(exactly = 1) { intentService.parseIntent(intent, true) }
            assertTrue(backStack.contains(Destination.Favorites))
        }
}
