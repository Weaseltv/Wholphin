package com.github.damontecres.wholphin.services

import android.content.Intent
import android.net.Uri
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.update
import com.github.damontecres.wholphin.test.currentUser
import com.github.damontecres.wholphin.test.movie
import com.github.damontecres.wholphin.test.server
import com.github.damontecres.wholphin.test.user
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.ui.toServerString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class IntentServiceTest {
    private val api: ApiClient = mockk()
    private val serverRepository: ServerRepository = mockk()
    private val userPreferencesService: UserPreferencesService = mockk()

    lateinit var intentService: IntentService

    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val currentUser = currentUser(serverId, userId)

    private val protectedCurrentUser =
        CurrentUser(
            server(serverId),
            user(serverId, userId).copy(pin = "1234"),
        )

    @Before
    fun setup() {
        intentService = IntentService(api, serverRepository, userPreferencesService)
    }

    private fun setupPreferences(block: AppPreferences.Builder.() -> Unit) {
        every { userPreferencesService.flow } returns
            flow {
                emit(
                    UserPreferences(
                        AppPreferences.getDefaultInstance().update(block),
                        null,
                    ),
                )
            }
    }

    @Test
    fun `Test auto sign in with unprotected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            val intent = Intent()
            val result = intentService.prepare(intent)
            assertNull(result)

            coVerify { serverRepository.restoreSession(serverId, userId) }
        }

    @Test
    fun `Test auto sign disabled in with unprotected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = false
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            val intent = Intent()
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
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

            val intent = Intent()
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
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

            val intent = Intent()
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 1) { serverRepository.restoreSession(serverId, userId) }
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
//            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            val intent = Intent()
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
        }

    @Test
    fun `Test user specified with unprotected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = ""
                currentUserId = ""
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.serverDao.getUser(serverId, userId) } returns currentUser.user
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            val intent =
                Intent().apply {
                    putExtra(IntentService.INTENT_SERVER_ID, serverId.toServerString())
                    putExtra(IntentService.INTENT_USER_ID, userId.toServerString())
                }
            val result = intentService.prepare(intent)
            assertNull(result)

            coVerify(exactly = 1) { serverRepository.restoreSession(serverId, userId) }
        }

    @Test
    fun `Test user specified with protected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = ""
                currentUserId = ""
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.serverDao.getUser(serverId, userId) } returns protectedCurrentUser.user
            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            val intent =
                Intent().apply {
                    putExtra(IntentService.INTENT_SERVER_ID, serverId.toServerString())
                    putExtra(IntentService.INTENT_USER_ID, userId.toServerString())
                }
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
        }

    @Test
    fun `Test play intent with Play On parameters`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            val movie = movie()
            val other = movie()
            val userLibraryApi = mockk<UserLibraryApi>()
            every { api.userLibraryApi } returns userLibraryApi
            coEvery { userLibraryApi.getItem(movie.id) } returns successResponse(movie)

            val intent =
                Intent("play").apply {
                    putExtra(IntentService.INTENT_ITEM_IDS, "${other.id.toServerString()}, ${movie.id}")
                    putExtra(IntentService.INTENT_START_INDEX, 1)
                    putExtra("position", 90_000L)
                    putExtra(IntentService.INTENT_MEDIA_SOURCE_ID, "abc")
                    putExtra(IntentService.INTENT_AUDIO_STREAM_INDEX, 2)
                    putExtra(IntentService.INTENT_SUBTITLE_STREAM_INDEX, -1)
                }
            val result = intentService.parseIntent(intent)
            assertTrue(result is IntentResult.Target)
            val destination = (result as IntentResult.Target).destinations.last()
            assertTrue(destination is Destination.Playback)
            destination as Destination.Playback
            assertEquals(movie.id, destination.itemId)
            assertEquals(90_000L, destination.positionMs)
            assertEquals("abc", destination.mediaSourceId)
            assertEquals(2, destination.audioStreamIndex)
            assertEquals(-1, destination.subtitleStreamIndex)
            assertEquals(listOf(other.id, movie.id), destination.itemIds)
            assertEquals(1, destination.startIndex)
        }

    @Test
    fun `Test play intent via URI query parameters`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            val movie = movie()
            val userLibraryApi = mockk<UserLibraryApi>()
            every { api.userLibraryApi } returns userLibraryApi
            coEvery { userLibraryApi.getItem(movie.id) } returns successResponse(movie)

            // `adb shell am start -d <uri>` with no action, so the host selects the action
            val intent =
                Intent().apply {
                    data = Uri.parse("wholphin://play?itemId=${movie.id}&subtitleStreamIndex=-1&audioStreamIndex=1&shuffle=true")
                }
            val result = intentService.parseIntent(intent)
            assertTrue(result is IntentResult.Target)
            val destination = (result as IntentResult.Target).destinations.last() as Destination.Playback
            assertEquals(movie.id, destination.itemId)
            assertEquals(0L, destination.positionMs)
            assertEquals(1, destination.audioStreamIndex)
            assertEquals(-1, destination.subtitleStreamIndex)
            assertTrue(destination.shuffle)
            assertTrue(destination.itemIds.isEmpty())
        }

    @Test
    fun `Test play intent built from a remote request round trips`() =
        runTest {
            setupPreferences {
                signInAutomatically = false
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
            coEvery { serverRepository.serverDao.getUser(serverId, userId) } returns currentUser.user
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            val movie = movie()
            val other = movie()
            val userLibraryApi = mockk<UserLibraryApi>()
            every { api.userLibraryApi } returns userLibraryApi
            coEvery { userLibraryApi.getItem(movie.id) } returns successResponse(movie)

            val request =
                PlayRequest(
                    itemIds = listOf(other.id, movie.id),
                    startPositionTicks = 90.seconds.inWholeTicks,
                    playCommand = PlayCommand.PLAY_NOW,
                    controllingUserId = UUID.randomUUID(),
                    subtitleStreamIndex = -1,
                    audioStreamIndex = 2,
                    mediaSourceId = "abc",
                    startIndex = 1,
                )
            // What RemotePlaybackService.createLaunchIntent builds for the screensaver
            val intent =
                Intent(IntentService.ACTION_PLAYBACK).apply {
                    PlayRequestMapper.toIntentParams(request)!!.forEach { (key, value) -> putExtra(key, value) }
                    putExtra(IntentService.INTENT_SERVER_ID, serverId.toString())
                    putExtra(IntentService.INTENT_USER_ID, userId.toString())
                }

            val result = intentService.parseIntent(intent)
            assertTrue(result is IntentResult.Target)
            val destination = (result as IntentResult.Target).destinations.last()
            assertEquals(PlayRequestMapper.toDestination(request), destination)
            coVerify(exactly = 1) { serverRepository.restoreSession(serverId, userId) }
        }

    @Test
    fun `Test user specified does not exist`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = ""
                currentUserId = ""
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.serverDao.getUser(serverId, userId) } returns null
            coEvery { serverRepository.restoreSession(serverId, userId) } returns null

            val intent =
                Intent().apply {
                    putExtra(IntentService.INTENT_SERVER_ID, serverId.toServerString())
                    putExtra(IntentService.INTENT_USER_ID, userId.toServerString())
                }
            val result = intentService.prepare(intent)
            assertTrue(result is IntentResult.Error)

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
        }
}
