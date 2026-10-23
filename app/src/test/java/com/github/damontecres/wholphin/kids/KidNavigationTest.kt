package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** A kids profile has no Settings (owner 2026-10-23) */
@OptIn(ExperimentalCoroutinesApi::class)
class KidNavigationTest {
    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    private val settingsPages =
        listOf(
            Destination.Settings(PreferenceScreenOption.BASIC),
            Destination.Settings(PreferenceScreenOption.ADVANCED),
            Destination.HomeSettings,
            Destination.SubtitleSettings(false),
            Destination.UserAppPreferences,
            Destination.ProfilePicture,
            Destination.License,
        )

    private val otherPages =
        listOf(
            Destination.Home(),
            Destination.Search(),
            Destination.Favorites,
            Destination.NowPlaying,
            // The update prompt opens by itself on every profile, and Debug from the sign-in screen
            Destination.UpdateApp(),
            Destination.Debug,
        )

    @Test
    fun `settings and the pages only settings opens are settings pages`() {
        settingsPages.forEach { assertTrue("$it", KidNavigation.isSettingsPage(it)) }
        otherPages.forEach { assertFalse("$it", KidNavigation.isSettingsPage(it)) }
    }

    @Test
    fun `a kid or unknown session never opens them, an adult does`() {
        settingsPages.forEach {
            assertFalse("$it", KidNavigation.mayOpen(it, kidOrUnknown = true))
            assertTrue("$it", KidNavigation.mayOpen(it, kidOrUnknown = false))
        }
        otherPages.forEach { assertTrue("$it", KidNavigation.mayOpen(it, kidOrUnknown = true)) }
    }

    // The guard where destinations are shown (review): read from the session itself, so it fails closed

    private val current = MutableStateFlow<CurrentUser?>(null)
    private val navigationManager = mockk<NavigationManager>(relaxed = true)

    private fun guard() =
        KidPageGuardViewModel(
            serverRepository = mockk<ServerRepository> { every { current } returns this@KidNavigationTest.current },
            navigationManager = navigationManager,
        )

    private fun session(state: PolicyState) = CurrentUser(testServer, testUser("Profile").copy(policyState = state))

    private val settings = Destination.Settings(PreferenceScreenOption.BASIC)

    @Test
    fun `no session at all never shows settings`() {
        assertTrue(KidNavigation.kidOrUnknown(null))
        assertFalse(guard().mayShow(settings))
        assertTrue(guard().mayShow(Destination.Home()))
    }

    @Test
    fun `a kid or unknown session is kept out, and sent home`() {
        current.value = session(PolicyState.KID)
        val guard = guard()
        assertFalse(guard.mayShow(settings))
        current.value = session(PolicyState.UNKNOWN)
        assertFalse(guard.mayShow(Destination.HomeSettings))
        guard.leave(settings)
        verify { navigationManager.goToHome() }
    }

    @Test
    fun `switching from a kid to an adult brings settings back`() {
        current.value = session(PolicyState.KID)
        val guard = guard()
        assertFalse(guard.mayShow(settings))
        current.value = session(PolicyState.ADULT)
        assertTrue(guard.mayShow(settings))
        assertTrue(guard.mayShow(Destination.ProfilePicture))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
}
