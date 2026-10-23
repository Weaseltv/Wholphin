package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** A kids profile has no Settings (owner 2026-10-23) */
class KidNavigationTest {
    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
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
}
