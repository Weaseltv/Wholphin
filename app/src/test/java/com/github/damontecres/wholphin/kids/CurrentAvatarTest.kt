package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.AvatarMatching
import com.github.damontecres.wholphin.services.CurrentAvatarFinder
import com.github.damontecres.wholphin.services.CurrentPicture
import com.github.damontecres.wholphin.services.ProfileImageFetcher
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureViewModel
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/** The picker ticks the profile's current server picture, found by its pixels (owner bug report 2026-10-23) */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentAvatarTest {
    private val dispatcher = StandardTestDispatcher()
    private val size = AvatarMatching.SIZE * AvatarMatching.SIZE * 3

    /** A distinct picture: random pixels, like two different avatars (mean difference about 85) */
    private fun picture(seed: Int) = Random(seed).let { r -> IntArray(size) { r.nextInt(256) } }

    /** [base] re-encoded: every value off by up to [by] */
    private fun reEncoded(
        base: IntArray,
        by: Int,
        seed: Int = 7,
    ) = Random(seed).let { r -> IntArray(size) { (base[it] + r.nextInt(-by, by + 1)).coerceIn(0, 255) } }

    private val alien = picture(1)
    private val wolf = picture(2)

    // --- Matching (pure) ---

    @Test
    fun `an exact copy and a slightly re-encoded copy match their avatar`() {
        val avatars = mapOf("alien-black" to alien, "wolf-crimson" to wolf)
        assertEquals("wolf-crimson", AvatarMatching.bestMatch(wolf, avatars))
        assertEquals("alien-black", AvatarMatching.bestMatch(reEncoded(alien, 4), avatars))
    }

    @Test
    fun `a custom picture, a heavily changed one or a near tie matches nothing`() {
        val avatars = mapOf("alien-black" to alien, "wolf-crimson" to wolf)
        assertNull(AvatarMatching.bestMatch(picture(3), avatars))
        assertNull(AvatarMatching.bestMatch(reEncoded(alien, 20), avatars))
        // Two bundled avatars almost alike: never a guess between them
        assertNull(AvatarMatching.bestMatch(alien, mapOf("a" to alien, "b" to reEncoded(alien, 6))))
        assertNull(AvatarMatching.bestMatch(alien, emptyMap()))
    }

    // --- Finding a profile's current avatar ---

    private val userId = UUID.randomUUID()
    private val target = ProfilePictureTarget("https://media.example", userId, "own-token", "Justin")
    private val cache = FakeProfileImageCache()
    private val catalog = FakeAvatarCatalog()

    /** What `GET /Users/Me` says */
    private var status: ProfileStatus = ProfileStatus.Active("tag-1")

    /** The server picture (its thumbnail key), null for 404; [fetchFailure] when it can't be reached */
    private var serverPicture: String? = "server"
    private var fetchFailure: Exception? = null
    private var fetchGate: CompletableDeferred<Unit>? = null
    private var fetches = 0
    private val thumbs = mutableMapOf("asset:alien-black" to alien, "asset:wolf-crimson" to wolf, "server" to wolf)

    private val finder =
        CurrentAvatarFinder(
            catalog = catalog,
            status =
                object : ProfileStatusRemote {
                    override suspend fun status(
                        serverUrl: String,
                        userId: UUID,
                        accessToken: String,
                    ) = status
                },
            fetcher =
                object : ProfileImageFetcher {
                    override suspend fun fetch(
                        serverUrl: String,
                        userId: UUID,
                        imageTag: String,
                    ): ByteArray? {
                        fetches++
                        fetchGate?.await()
                        fetchFailure?.let { throw it }
                        return serverPicture?.encodeToByteArray()
                    }
                },
            thumbnails = { thumbs[it.decodeToString()] },
            cache = cache,
            dispatcher = dispatcher,
        )

    private val service = ProfilePictureService(catalog, FakeUserImageUploader(), cache)
    private val adults get() = service.avatars(isKid = false)
    private lateinit var viewModel: ProfilePictureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        WholphinDispatchers.configure(dispatcher)
        viewModel = ProfilePictureViewModel(service, mockk<ServerRepository>(relaxed = true), finder)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        WholphinDispatchers.reset()
    }

    @Test
    fun `the server picture is matched, and cached per user and tag`() =
        runTest(dispatcher) {
            thumbs["server"] = reEncoded(wolf, 3)
            assertEquals(CurrentPicture.Bundled("wolf-crimson"), finder.find(target, adults))
            assertEquals(CurrentPicture.Bundled("wolf-crimson"), finder.find(target, adults))
            assertEquals(1, fetches)
            // A new picture (new tag) is fetched again
            status = ProfileStatus.Active("tag-2")
            thumbs["server"] = alien
            assertEquals(CurrentPicture.Bundled("alien-black"), finder.find(target, adults))
            assertEquals(2, fetches)
        }

    @Test
    fun `no picture or a custom one ticks nothing`() =
        runTest(dispatcher) {
            status = ProfileStatus.Active(null)
            assertEquals(CurrentPicture.NotBundled, finder.find(target, adults))
            assertEquals(0, fetches)
            status = ProfileStatus.Active("tag-404")
            serverPicture = null
            assertEquals(CurrentPicture.NotBundled, finder.find(target, adults))
            status = ProfileStatus.Active("tag-custom")
            serverPicture = "custom"
            thumbs["custom"] = picture(9)
            assertEquals(CurrentPicture.NotBundled, finder.find(target, adults))
        }

    @Test
    fun `a server that can't be reached is unknown`() =
        runTest(dispatcher) {
            fetchFailure = IOException("offline")
            assertEquals(CurrentPicture.Unknown, finder.find(target, adults))
            status = ProfileStatus.Unknown
            assertEquals(CurrentPicture.Unknown, finder.find(target, adults))
            // Bounded: a server that never answers gives up
            status = ProfileStatus.Active("tag-slow")
            fetchFailure = null
            fetchGate = CompletableDeferred()
            assertEquals(CurrentPicture.Unknown, finder.find(target, adults))
        }

    // --- The picker ---

    @Test
    fun `the picker ticks the website's picture once it is found`() =
        runTest(dispatcher) {
            cache.chosen[userId] = "alien-black"
            viewModel.start(target)
            // Shown straight away with this TV's hint, then the server's picture decides
            assertEquals("alien-black", viewModel.state.value.current)
            advanceUntilIdle()
            assertEquals("wolf-crimson", viewModel.state.value.current)
            // Reopened: the last result shows straight away
            viewModel.start(target)
            assertEquals("wolf-crimson", viewModel.state.value.current)
        }

    @Test
    fun `a picture that isn't an avatar unticks the hint`() =
        runTest(dispatcher) {
            cache.chosen[userId] = "alien-black"
            serverPicture = "custom"
            thumbs["custom"] = picture(9)
            viewModel.start(target)
            advanceUntilIdle()
            assertNull(viewModel.state.value.current)
        }

    @Test
    fun `a network failure keeps this TV's remembered avatar`() =
        runTest(dispatcher) {
            cache.chosen[userId] = "alien-black"
            fetchFailure = IOException("offline")
            viewModel.start(target)
            advanceUntilIdle()
            assertEquals("alien-black", viewModel.state.value.current)
        }

    @Test
    fun `a result arriving after a pick never replaces it`() =
        runTest(dispatcher) {
            fetchGate = CompletableDeferred()
            viewModel.start(target)
            runCurrent()
            viewModel.pick(adults.first { it.id == "alien-black" })
            fetchGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals("alien-black", viewModel.state.value.current)
        }

    @Test
    fun `closing the picker stops looking`() =
        runTest(dispatcher) {
            fetchGate = CompletableDeferred()
            cache.chosen[userId] = "alien-black"
            viewModel.start(target)
            runCurrent()
            viewModel.stop()
            fetchGate?.complete(Unit)
            advanceTimeBy(10_000)
            assertEquals("alien-black", viewModel.state.value.current)
            assertNull(finder.lastKnown(userId))
        }
}
