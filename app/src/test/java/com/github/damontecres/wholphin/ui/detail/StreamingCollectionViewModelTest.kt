package com.github.damontecres.wholphin.ui.detail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.LibraryDisplayInfoDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.LibraryDisplayInfo
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewModel
import com.github.damontecres.wholphin.ui.successQueryResult
import com.github.damontecres.wholphin.ui.successResponse
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingCollectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<ApiClient>()
    private val userApi = mockk<UserLibraryApi>()
    private val repository = mockk<ServerRepository>()
    private val displayInfo = mockk<LibraryDisplayInfoDao>()
    private val saved = MutableStateFlow<LibraryDisplayInfo?>(null)
    private val requests = mutableListOf<GetItemsRequest>()
    private val store = ViewModelStore()
    private val collectionId = UUID.randomUUID()
    private val user =
        JellyfinUser(rowId = 1, id = UUID.randomUUID(), name = "test member", serverId = UUID.randomUUID(), accessToken = "test-token")

    @Before
    fun setup() {
        assumeTrue(BuildConfig.FLAVOR == "weaselfin")
        WholphinDispatchers.configure(dispatcher)
        every { api.userLibraryApi } returns userApi
        every { repository.currentUser } returns user
        every { repository.currentUserFlow } returns MutableStateFlow(user)
        every { displayInfo.getItemAsFlow(any(), any()) } returns saved
        every { displayInfo.saveItem(any()) } answers {
            saved.value = firstArg()
            1L
        }
        coEvery { userApi.getItem(itemId = collectionId, userId = user.id) } returns
            successResponse(
                BaseItemDto(id = collectionId, name = "Netflix", type = BaseItemKind.BOX_SET, tags = listOf(StreamingCollections.TAG)),
            )
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

    private fun create(): CollectionViewModel {
        val dataStore = mockk<DataStore<Preferences>>()
        every { dataStore.data } returns MutableStateFlow(emptyPreferences())
        return CollectionViewModel(
            context = mockk(relaxed = true),
            api = api,
            serverRepository = repository,
            navigationManager = mockk(relaxed = true),
            preferencesService = mockk(relaxed = true),
            themeSongPlayer = mockk(relaxed = true),
            mediaManagementService = mockk(relaxed = true),
            favoriteWatchManager = mockk(relaxed = true),
            backdropService = mockk(relaxed = true),
            keyValueService = KeyValueService(dataStore),
            libraryDisplayInfoDao = displayInfo,
            imageUrlService = mockk(relaxed = true),
            musicService = mockk(relaxed = true),
            serverReportService = mockk(relaxed = true),
            filterOptionCache = mockk(relaxed = true),
            itemId = collectionId,
        ).also { store.put("collection", it) }
    }

    @Test
    fun `Streaming opens as posters and sends member scoped Movie Series queries`() =
        runTest(dispatcher) {
            val model = create()
            advanceUntilIdle()
            assertFalse(model.state.value.viewOptions.separateTypes)
            assertEquals(AspectRatio.TALL, model.state.value.viewOptions.cardViewOptions.aspectRatio)
            assertEquals(ViewOptionImageType.PRIMARY, model.state.value.viewOptions.cardViewOptions.imageType)
            assertEquals(listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), requests.last().includeItemTypes)
            assertEquals(listOf(ItemSortBy.PREMIERE_DATE), requests.last().sortBy)
            assertEquals(listOf(SortOrder.DESCENDING), requests.last().sortOrder)
            listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, null).forEach { type ->
                model.changeFilter(GetItemsFilter(includeItemTypes = type?.let { listOf(it) }))
                advanceUntilIdle()
                assertEquals(type?.let { listOf(it) } ?: StreamingCollections.types, requests.last().includeItemTypes)
            }
            listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED, ItemSortBy.PREMIERE_DATE).forEach { sort ->
                model.changeSort(SortAndDirection(sort, SortOrder.DESCENDING))
                advanceUntilIdle()
                assertEquals(listOf(sort), requests.last().sortBy)
                assertEquals(listOf(SortOrder.DESCENDING), requests.last().sortOrder)
            }
            requests.forEach {
                assertEquals(user.id, it.userId)
                assertEquals(collectionId, it.parentId)
                assertEquals(false, it.recursive)
                assertNull(it.tags)
            }
        }

    @Test
    fun `Reopening a collection fetches fresh contents with saved filter and sort`() =
        runTest(dispatcher) {
            val first = create()
            advanceUntilIdle()
            first.changeFilter(GetItemsFilter(includeItemTypes = listOf(BaseItemKind.SERIES)))
            advanceUntilIdle()
            first.changeSort(SortAndDirection(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING))
            advanceUntilIdle()
            val before = requests.size
            store.clear()
            create()
            advanceUntilIdle()
            assertTrue(requests.size > before)
            assertEquals(listOf(BaseItemKind.SERIES), requests.last().includeItemTypes)
            assertEquals(listOf(ItemSortBy.DATE_CREATED), requests.last().sortBy)
        }
}
