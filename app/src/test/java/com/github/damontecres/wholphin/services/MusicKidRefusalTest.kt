package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.kids.KidContentGuard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Kid content rules (review round 4): kid and unknown sessions get no music at all */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class MusicKidRefusalTest {
    private val playerFactory = mockk<PlayerFactory>(relaxed = true)

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a kid session can't start or queue music`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val guard = mockk<KidContentGuard> { coEvery { currentPolicy() } returns KidContentGuard.Policy("kid", 1) }
            val repository = mockk<ServerRepository>(relaxed = true) { every { sessionEpoch } returns MutableStateFlow(0L) }
            val service = MusicService(context, backgroundScope, mockk(relaxed = true), playerFactory, repository, mockk(), mockk(), guard)

            service.start()
            service.setQueue(emptyList(), false)
            service.playIndex(0)

            // The player is never even created
            verify(exactly = 0) { playerFactory.createAudioPlayer(any(), any(), any()) }
            assertEquals(context.getString(R.string.kid_music_unavailable), ShadowToast.getTextOfLatestToast())
        }
}
