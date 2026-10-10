package com.github.damontecres.wholphin.ui.detail

import androidx.lifecycle.ViewModelStore
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.ui.components.WatchlistTypeFilters
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.successQueryResult
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<ApiClient>()
    private val repository = mockk<ServerRepository>()
    private val displayInfo = mockk<LibraryDisplayInfoDao>()
    private val favoriteWatchManager = mockk<FavoriteWatchManager>()
    private val requests = mutableListOf<GetItemsRequest>()
    private var saved: LibraryDisplayInfo? = null
    private val store = ViewModelStore()
    private val user =
        JellyfinUser(rowId = 1, id = UUID.randomUUID(), name = "test member", serverId = UUID.randomUUID(), accessToken = "test-token")

    @Before
    fun setup() {
        WholphinDispatchers.configure(dispatcher)
        every { repository.currentUser } returns user
        every { repository.currentUserDto } returns null
        every { displayInfo.getItem(user, WatchlistViewModel.DISPLAY_ID) } answers { saved }
        every { displayInfo.saveItem(any()) } answers {
            saved = firstArg()
            1L
        }
        coEvery { favoriteWatchManager.setFavorite(any(), any()) } returns mockk()
        coEvery { favoriteWatchManager.setWatched(any(), any()) } returns mockk()
        mockkObject(GetItemsRequestHandler)
        coEvery { GetItemsRequestHandler.execute(api, any()) } answers {
            requests.add(secondArg())
            successQueryResult(totalRecordCount = 0)
        }
    }

    @After
    fun cleanup() {
        store.clear()
        WholphinDispatchers.reset()
        unmockkObject(GetItemsRequestHandler)
    }

    private fun create(): WatchlistViewModel =
        WatchlistViewModel(
            context = mockk(relaxed = true),
            api = api,
            navigationManager = mockk(relaxed = true),
            serverRepository = repository,
            libraryDisplayInfoDao = displayInfo,
            favoriteWatchManager = favoriteWatchManager,
            backdropService = mockk(relaxed = true),
            mediaManagementService = mockk(relaxed = true),
            serverReportService = mockk(relaxed = true),
            filterOptionCache = mockk(relaxed = true),
            homeSettingsService = mockk(relaxed = true),
            navDrawerService = mockk(relaxed = true),
        ).also { store.put(UUID.randomUUID().toString(), it) }

    @Test
    fun `ALL MOVIES and SHOWS ask only for watchlisted titles of those types`() =
        runTest(dispatcher) {
            val model = create()
            model.refresh()
            advanceUntilIdle()
            assertEquals(WatchlistViewModel.WatchlistTypes, requests.last().includeItemTypes)

            WatchlistTypeFilters.forEach { option ->
                model.onFilterChange(
                    model.state.value.filter
                        .copy(includeItemTypes = option.types),
                    true,
                )
                advanceUntilIdle()
                assertEquals(option.types ?: WatchlistViewModel.WatchlistTypes, requests.last().includeItemTypes)
            }
            requests.forEach {
                assertEquals(true, it.isFavorite)
                assertEquals(true, it.recursive)
            }
        }

    @Test
    fun `Seasons and episodes added from a long press show up under ALL and SHOWS`() {
        val shows = WatchlistTypeFilters.first { it.types?.contains(BaseItemKind.SERIES) == true }
        listOf(BaseItemKind.SEASON, BaseItemKind.EPISODE).forEach { kind ->
            assertTrue(kind in shows.types!!)
            assertTrue(kind in WatchlistViewModel.WatchlistTypes)
        }
        WatchlistTypeFilters.forEach { option ->
            option.types?.forEach { assertTrue(it in WatchlistViewModel.WatchlistTypes) }
        }
    }

    @Test
    fun `A saved filter can't turn off the watchlist restriction or widen the types`() {
        val request =
            WatchlistViewModel.createRequest(
                GetItemsFilter(favorite = false, includeItemTypes = listOf(BaseItemKind.AUDIO)),
                SortAndDirection.DEFAULT,
            )
        assertEquals(true, request.isFavorite)
        assertEquals(WatchlistViewModel.WatchlistTypes, request.includeItemTypes)
    }

    @Test
    fun `Chosen button and sort are remembered for the next visit`() =
        runTest(dispatcher) {
            val first = create()
            first.refresh()
            advanceUntilIdle()
            first.onFilterChange(GetItemsFilter(includeItemTypes = listOf(BaseItemKind.MOVIE)), true)
            advanceUntilIdle()
            first.onSortChange(
                SortAndDirection(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
                true,
                first.state.value.filter,
            )
            advanceUntilIdle()

            val second = create()
            second.refresh()
            advanceUntilIdle()
            assertEquals(listOf(BaseItemKind.MOVIE), second.state.value.filter.includeItemTypes)
            assertEquals(listOf(BaseItemKind.MOVIE), requests.last().includeItemTypes)
            assertEquals(listOf(ItemSortBy.DATE_CREATED), requests.last().sortBy)
        }

    @Test
    fun `Coming back to the page reloads the Watchlist`() =
        runTest(dispatcher) {
            val model = create()
            model.refresh()
            advanceUntilIdle()
            val before = requests.size
            model.refresh()
            advanceUntilIdle()
            assertTrue(requests.size > before)
        }

    @Test
    fun `Remove from Watchlist in the long press menu removes it instead of marking it unwatched`() =
        runTest(dispatcher) {
            val model = create()
            model.refresh()
            advanceUntilIdle()
            val itemId = UUID.randomUUID()
            model.setFavorite(0, itemId, false)
            advanceUntilIdle()
            coVerify(exactly = 1) { favoriteWatchManager.setFavorite(itemId, false) }
            coVerify(exactly = 0) { favoriteWatchManager.setWatched(any(), any()) }
        }
}
