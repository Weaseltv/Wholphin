package com.github.damontecres.wholphin.ui.discover

import androidx.lifecycle.ViewModelStore
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.SeerrSearchResult
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val originalIO = WholphinDispatchers.IO
    private val store = ViewModelStore()
    private val service = mockk<SeerrService>()
    private lateinit var model: DiscoverSearchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        WholphinDispatchers.IO = dispatcher
        coEvery { service.search(any(), any()) } returns emptyList()
        model = DiscoverSearchViewModel(mockk(), service, mockk(), mockk())
        store.put("search", model)
    }

    @After
    fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
        WholphinDispatchers.IO = originalIO
    }

    @Test
    fun `typing sends only the final trimmed title after 400ms`() =
        runTest(dispatcher) {
            model.search("d")
            advanceUntilIdle()
            coVerify(exactly = 0) { service.search(any(), any()) }
            model.search("dra")
            advanceTimeBy(300)
            model.search("  dragon  ")
            advanceTimeBy(399)
            runCurrent()
            coVerify(exactly = 0) { service.search(any(), any()) }
            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 1) { service.search("dragon", 1) }
            assertTrue(model.seerrResults.value is SearchResult.SuccessSeerr)
        }

    @Test
    fun `keyboard or voice submission bypasses debounce without a duplicate request`() =
        runTest(dispatcher) {
            model.search("dragon")
            advanceTimeBy(100)
            model.search("dragon", immediate = true)
            runCurrent()
            coVerify(exactly = 1) { service.search("dragon", 1) }
            // Compose observes the same query after voice input or keyboard submission.
            model.search("dragon")
            advanceUntilIdle()
            coVerify(exactly = 1) { service.search(any(), any()) }
        }

    @Test
    fun `one letter titles require explicit submission and blank titles never search`() =
        runTest(dispatcher) {
            model.search("V")
            advanceUntilIdle()
            assertEquals(SearchResult.NoQuery, model.seerrResults.value)
            model.search("V", immediate = true)
            runCurrent()
            coVerify(exactly = 1) { service.search("V", 1) }
            model.search("  ", immediate = true)
            advanceUntilIdle()
            assertEquals(SearchResult.NoQuery, model.seerrResults.value)
            coVerify(exactly = 1) { service.search(any(), any()) }
        }

    @Test
    fun `editing cancels an active request before the next typing delay`() =
        runTest(dispatcher) {
            var cancelled = false
            coEvery { service.search("old", 1) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.search("old", immediate = true)
            runCurrent()
            model.search("new")
            runCurrent()
            assertTrue(cancelled)
            coVerify(exactly = 0) { service.search("new", 1) }
            advanceUntilIdle()
            coVerify(exactly = 1) { service.search("new", 1) }
        }

    @Test
    fun `late results cannot replace a cleared query`() =
        runTest(dispatcher) {
            coEvery { service.search("old", 1) } coAnswers {
                withContext(NonCancellable) { delay(1_000) }
                emptyList()
            }
            model.search("old", immediate = true)
            runCurrent()
            model.search("")
            advanceUntilIdle()
            assertEquals(SearchResult.NoQuery, model.seerrResults.value)
        }

    @Test
    fun `late failures cannot replace the latest results`() =
        runTest(dispatcher) {
            coEvery { service.search("old", 1) } coAnswers {
                withContext(NonCancellable) { delay(1_000) }
                error("Old request failed")
            }
            model.search("old", immediate = true)
            runCurrent()
            model.search("new", immediate = true)
            advanceUntilIdle()
            assertTrue(model.seerrResults.value is SearchResult.SuccessSeerr)
            assertEquals("new", model.currentQuery)
        }

    @Test
    fun `failed query can be retried explicitly without automatic retries`() =
        runTest(dispatcher) {
            coEvery { service.search("dragon", 1) } throws IllegalStateException("Unavailable")
            model.search("dragon", immediate = true)
            advanceUntilIdle()
            assertTrue(model.seerrResults.value is SearchResult.Error)
            coVerify(exactly = 1) { service.search("dragon", 1) }
            coEvery { service.search("dragon", 1) } returns emptyList()
            model.search("dragon", immediate = true)
            advanceUntilIdle()
            assertTrue(model.seerrResults.value is SearchResult.SuccessSeerr)
            coVerify(exactly = 2) { service.search("dragon", 1) }
        }

    @Test
    fun `filters before building cards and retains movie and TV with the same ID`() =
        runTest(dispatcher) {
            val movie = SeerrSearchResult(id = 1, mediaType = "movie", title = "Movie")
            val tv = SeerrSearchResult(id = 1, mediaType = "tv", name = "Series")
            val person = SeerrSearchResult(id = 2, mediaType = "person")
            val movieCard = mockk<DiscoverItem>()
            val tvCard = mockk<DiscoverItem>()
            coEvery { service.search(any(), any()) } returns listOf(movie, person, tv, movie)
            coEvery { service.createDiscoverItem(movie) } returns movieCard
            coEvery { service.createDiscoverItem(tv) } returns tvCard
            model.search("dragon", immediate = true)
            advanceUntilIdle()
            assertEquals(
                listOf(movieCard, tvCard),
                (model.seerrResults.value as SearchResult.SuccessSeerr).items,
            )
            coVerify(exactly = 0) { service.createDiscoverItem(person) }
            coVerify(exactly = 1) { service.createDiscoverItem(movie) }
        }
}
