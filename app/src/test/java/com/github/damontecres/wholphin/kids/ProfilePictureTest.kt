package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.AvatarGroup
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.versionedUserImageUrl
import com.github.damontecres.wholphin.ui.avatars.PictureStatus
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Avatar picker (plan 4.6): save, remove, cache refresh, with fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePictureTest {
    private val dispatcher = StandardTestDispatcher()
    private val uploader = FakeUserImageUploader()
    private val cache = FakeProfileImageCache()
    private val service = ProfilePictureService(FakeAvatarCatalog(), uploader, cache)
    private lateinit var viewModel: ProfilePictureViewModel
    private val userId = UUID.randomUUID()
    private val target = ProfilePictureTarget("https://media.example", userId, "own-token", "Emma")

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = ProfilePictureViewModel(service, mockk<ServerRepository>(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an adult picks from the adults set, with the current picture`() {
        cache.chosen[userId] = "wolf-crimson"
        viewModel.start(target)
        val state = viewModel.state.value
        assertEquals(listOf("alien-black", "wolf-crimson"), state.avatars.map { it.id })
        assertTrue(state.avatars.all { it.group == AvatarGroup.ADULTS })
        assertEquals("wolf-crimson", state.current)
        assertEquals("Emma", state.target?.name)
    }

    @Test
    fun `a kid picks from the kids set`() {
        viewModel.start(target.copy(isKid = true))
        val state = viewModel.state.value
        assertEquals(listOf("bee-blue", "fox-green"), state.avatars.map { it.id })
        assertEquals("avatars/kids/bee-blue.webp", state.avatars.first().assetPath)
    }

    @Test
    fun `a picture picked from the removed placeholders is simply not marked`() {
        // The saved image is on the server; only the picker's "current" mark is lost
        cache.chosen[userId] = "placeholder-fox"
        viewModel.start(target)
        val state = viewModel.state.value
        assertEquals("placeholder-fox", state.current)
        assertTrue(state.avatars.none { it.id == state.current })
    }

    @Test
    fun `save uploads with the profile's own token and bumps the cache key`() =
        runTest(dispatcher) {
            viewModel.start(target)
            viewModel.pick(viewModel.state.value.avatars[1])
            assertEquals(PictureStatus.Saving, viewModel.state.value.status)
            advanceUntilIdle()
            val (uploadedFor, png) = uploader.uploads.single()
            assertEquals(target, uploadedFor)
            assertEquals("wolf-crimson", png.decodeToString())
            assertEquals(PictureStatus.Saved, viewModel.state.value.status)
            assertEquals("wolf-crimson", viewModel.state.value.current)
            assertEquals(mapOf(userId to 1), cache.versions.value)
        }

    @Test
    fun `remove deletes the image and refreshes the cache`() =
        runTest(dispatcher) {
            cache.chosen[userId] = "alien-black"
            viewModel.start(target)
            viewModel.remove()
            advanceUntilIdle()
            assertEquals(listOf(target), uploader.deletes)
            assertNull(viewModel.state.value.current)
            assertNull(cache.chosen[userId])
            assertEquals(mapOf(userId to 1), cache.versions.value)
        }

    @Test
    fun `failed save keeps the old picture and cache key`() =
        runTest(dispatcher) {
            uploader.failWith = IllegalStateException("403")
            cache.chosen[userId] = "alien-black"
            viewModel.start(target)
            viewModel.pick(viewModel.state.value.avatars[1])
            advanceUntilIdle()
            assertEquals(PictureStatus.Failed, viewModel.state.value.status)
            assertEquals("alien-black", viewModel.state.value.current)
            assertTrue(cache.versions.value.isEmpty())
        }

    @Test
    fun `image urls carry the profile's cache version`() {
        val url = "https://media.example/Users/$userId/Images/Primary"
        assertEquals(url, versionedUserImageUrl(url, userId, emptyMap()))
        assertEquals("$url?wpPicture=3", versionedUserImageUrl(url, userId, mapOf(userId to 3)))
        assertEquals("$url?tag=x&wpPicture=3", versionedUserImageUrl("$url?tag=x", userId, mapOf(userId to 3)))
        assertEquals(url, versionedUserImageUrl(url, userId, mapOf(UUID.randomUUID() to 3)))
        assertNull(versionedUserImageUrl(null, userId, mapOf(userId to 3)))
    }
}
