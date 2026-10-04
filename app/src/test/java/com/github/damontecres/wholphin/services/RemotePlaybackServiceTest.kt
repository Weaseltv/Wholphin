package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.updatePlaybackPreferences
import com.github.damontecres.wholphin.test.currentUser
import com.github.damontecres.wholphin.test.server
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RemotePlaybackServiceTest {
    private val testDispatcher = StandardTestDispatcher()

    // A real context so toasts shown on the ignored paths work under Robolectric
    private val context: Context = RuntimeEnvironment.getApplication()
    private val api = mockk<ApiClient>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>()
    private val navigationManager = mockk<NavigationManager>(relaxed = true)
    private val setupNavigationManager = mockk<SetupNavigationManager>(relaxed = true)
    private val userPreferencesService = mockk<UserPreferencesService>()
    private val registry = ActivePlaybackRegistry()
    private val volumeService = mockk<VolumeService>(relaxed = true)

    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val currentUser = currentUser(serverId, userId)
    private val controllingUserId = UUID.randomUUID()

    private lateinit var service: RemotePlaybackService

    @Before
    fun setUp() {
        WholphinDispatchers.configure(testDispatcher)
        setEnabled(true)
        every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
        every { navigationManager.backStack } returns mutableListOf(Destination.Home())
        every { setupNavigationManager.backStack } returns mutableListOf(SetupDestination.AppContent(currentUser))
        service =
            RemotePlaybackService(
                context = context,
                api = api,
                serverRepository = serverRepository,
                navigationManager = navigationManager,
                setupNavigationManager = setupNavigationManager,
                userPreferencesService = userPreferencesService,
                activePlaybackRegistry = registry,
                volumeService = volumeService,
            )
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    private fun setEnabled(enabled: Boolean) {
        val prefs =
            UserPreferences(
                AppPreferences.getDefaultInstance().updatePlaybackPreferences { remoteControlEnabled = enabled },
                null,
            )
        coEvery { userPreferencesService.getCurrent() } returns prefs
        every { userPreferencesService.flow } returns flowOf(prefs)
    }

    private fun playRequest(
        itemIds: List<java.util.UUID>,
        playCommand: PlayCommand = PlayCommand.PLAY_NOW,
    ) = PlayRequest(
        itemIds = itemIds,
        startPositionTicks = null,
        playCommand = playCommand,
        controllingUserId = controllingUserId,
        subtitleStreamIndex = null,
        audioStreamIndex = null,
        mediaSourceId = null,
        startIndex = null,
    )

    private class FakeHandler : ActivePlaybackRegistry.Handler {
        val audio = mutableListOf<Int>()
        val subtitles = mutableListOf<Int>()
        val queued = mutableListOf<Pair<List<java.util.UUID>, Boolean>>()

        override suspend fun setAudioStream(index: Int) {
            audio.add(index)
        }

        override suspend fun setSubtitleStream(index: Int) {
            subtitles.add(index)
        }

        override suspend fun enqueue(
            itemIds: List<java.util.UUID>,
            playNext: Boolean,
        ) {
            queued.add(itemIds to playNext)
        }
    }

    @Test
    fun `Play request while idle navigates to playback`() =
        runTest(testDispatcher) {
            val itemId = UUID.randomUUID()
            service.onPlayRequest(playRequest(listOf(itemId)))

            verify(exactly = 1) { navigationManager.navigateTo(Destination.Playback(itemId, 0L)) }
            verify(exactly = 0) { navigationManager.replaceTop(any()) }
            verify(exactly = 0) { setupNavigationManager.navigateTo(any()) }
        }

    @Test
    fun `Play request during playback replaces the player`() =
        runTest(testDispatcher) {
            val itemId = UUID.randomUUID()
            every { navigationManager.backStack } returns
                mutableListOf(Destination.Home(), Destination.Playback(UUID.randomUUID(), 0L))

            service.onPlayRequest(playRequest(listOf(itemId)))

            verify(exactly = 1) { navigationManager.replaceTop(Destination.Playback(itemId, 0L)) }
            verify(exactly = 0) { navigationManager.navigateTo(any()) }
        }

    @Test
    fun `Play next during playback queues instead of restarting`() =
        runTest(testDispatcher) {
            val handler = FakeHandler()
            registry.register(handler)
            val ids = listOf(UUID.randomUUID(), UUID.randomUUID())

            service.onPlayRequest(playRequest(ids, PlayCommand.PLAY_NEXT))
            service.onPlayRequest(playRequest(ids, PlayCommand.PLAY_LAST))

            assertEquals(listOf(ids to true, ids to false), handler.queued)
            verify(exactly = 0) { navigationManager.navigateTo(any()) }
            verify(exactly = 0) { navigationManager.replaceTop(any()) }
        }

    @Test
    fun `Play next while idle plays now`() =
        runTest(testDispatcher) {
            val itemId = UUID.randomUUID()
            service.onPlayRequest(playRequest(listOf(itemId), PlayCommand.PLAY_NEXT))
            verify(exactly = 1) { navigationManager.navigateTo(Destination.Playback(itemId, 0L)) }
        }

    @Test
    fun `Requests are ignored when the preference is off`() =
        runTest(testDispatcher) {
            setEnabled(false)
            service.onPlayRequest(playRequest(listOf(UUID.randomUUID())))
            val handled =
                service.onGeneralCommand(
                    GeneralCommand(GeneralCommandType.VOLUME_UP, controllingUserId, emptyMap()),
                )

            assertFalse(handled)
            verify(exactly = 0) { navigationManager.navigateTo(any()) }
            coVerify(exactly = 0) { volumeService.adjust(any()) }
        }

    @Test
    fun `Protected profile is not bypassed outside the app`() =
        runTest(testDispatcher) {
            val protectedUser = CurrentUser(server(serverId), user(serverId, userId).copy(pin = "1234"))
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(protectedUser)
            every { setupNavigationManager.backStack } returns mutableListOf(SetupDestination.UserList(protectedUser.server))

            service.onPlayRequest(playRequest(listOf(UUID.randomUUID())))

            verify(exactly = 0) { navigationManager.navigateTo(any()) }
            verify(exactly = 0) { setupNavigationManager.navigateTo(any()) }
        }

    @Test
    fun `Unprotected profile on the user list is signed in and played`() =
        runTest(testDispatcher) {
            every { setupNavigationManager.backStack } returns mutableListOf(SetupDestination.UserList(currentUser.server))
            val itemId = UUID.randomUUID()

            service.onPlayRequest(playRequest(listOf(itemId)))

            verify(exactly = 1) { navigationManager.navigateTo(Destination.Playback(itemId, 0L)) }
            verify(exactly = 1) { setupNavigationManager.navigateTo(SetupDestination.AppContent(currentUser)) }
        }

    @Test
    fun `Volume and mute commands reach the volume service`() =
        runTest(testDispatcher) {
            fun command(
                type: GeneralCommandType,
                args: Map<String, String> = emptyMap(),
            ) = GeneralCommand(type, controllingUserId, args)

            assertTrue(service.onGeneralCommand(command(GeneralCommandType.VOLUME_UP)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.VOLUME_DOWN)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.MUTE)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.UNMUTE)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.TOGGLE_MUTE)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.SET_VOLUME, mapOf("Volume" to "42"))))
            assertFalse(service.onGeneralCommand(command(GeneralCommandType.SET_VOLUME, mapOf("Volume" to "x"))))

            coVerify(exactly = 1) { volumeService.adjust(true) }
            coVerify(exactly = 1) { volumeService.adjust(false) }
            coVerify(exactly = 1) { volumeService.setMuted(true) }
            coVerify(exactly = 1) { volumeService.setMuted(false) }
            coVerify(exactly = 1) { volumeService.toggleMute() }
            coVerify(exactly = 1) { volumeService.setVolume(42) }
        }

    @Test
    fun `Stream commands reach the active playback`() =
        runTest(testDispatcher) {
            fun command(
                type: GeneralCommandType,
                index: String,
            ) = GeneralCommand(type, controllingUserId, mapOf("Index" to index))

            // Nothing playing
            assertFalse(service.onGeneralCommand(command(GeneralCommandType.SET_AUDIO_STREAM_INDEX, "1")))

            val handler = FakeHandler()
            registry.register(handler)
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.SET_AUDIO_STREAM_INDEX, "1")))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, "3")))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX, "-1")))

            assertEquals(listOf(1), handler.audio)
            assertEquals(listOf(3, com.github.damontecres.wholphin.data.model.TrackIndex.DISABLED), handler.subtitles)
        }

    @Test
    fun `Navigation commands use the navigation manager`() =
        runTest(testDispatcher) {
            fun command(type: GeneralCommandType) = GeneralCommand(type, controllingUserId, emptyMap())

            assertTrue(service.onGeneralCommand(command(GeneralCommandType.GO_HOME)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.BACK)))
            assertTrue(service.onGeneralCommand(command(GeneralCommandType.GO_TO_SEARCH)))
            assertFalse(service.onGeneralCommand(command(GeneralCommandType.TAKE_SCREENSHOT)))

            verify(exactly = 1) { navigationManager.goToHome() }
            verify(exactly = 1) { navigationManager.goBack() }
            verify(exactly = 1) { navigationManager.navigateTo(Destination.Search()) }
        }
}
