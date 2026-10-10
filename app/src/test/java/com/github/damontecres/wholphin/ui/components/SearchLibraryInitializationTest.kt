package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.NavDrawerItemState
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.search.SearchViewModel
import com.github.damontecres.wholphin.util.WholphinDispatchers
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SearchLibraryInitializationTest {
    @Test
    fun `search opened before navigation libraries load gains its movie and episode types`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val oldDefault = WholphinDispatchers.Default
            val oldIO = WholphinDispatchers.IO
            Dispatchers.setMain(dispatcher)
            WholphinDispatchers.Default = dispatcher
            WholphinDispatchers.IO = dispatcher
            val store = ViewModelStore()
            try {
                val libraries = MutableStateFlow(NavDrawerItemState())
                val nav = mockk<NavDrawerService>()
                every { nav.state } returns libraries
                val server = mockk<ServerRepository>(relaxed = true)
                every { server.currentUser } returns null
                every { server.currentUserDto } returns null
                val seerr = mockk<SeerrService>()
                every { seerr.active } returns MutableStateFlow(false)
                val model =
                    SearchViewModel(
                        context = mockk(relaxed = true),
                        api = mockk(relaxed = true),
                        navigationManager = mockk(relaxed = true),
                        appPreferences = mockk(relaxed = true),
                        seerrService = seerr,
                        voiceInputManager = mockk(relaxed = true),
                        userPreferencesService = mockk(relaxed = true),
                        serverRepository = server,
                        favoriteWatchManager = mockk(relaxed = true),
                        mediaManagementService = mockk(relaxed = true),
                        serverReportService = mockk(relaxed = true),
                        liveTvService = mockk(relaxed = true),
                        keyValueService = mockk(relaxed = true),
                        navDrawerService = nav,
                    )
                store.put("search", model)
                advanceUntilIdle()
                assertTrue(BaseItemKind.PERSON in model.state.value.possibleSearchableTypes)
                val movies = Library(UUID.randomUUID(), "Movies", BaseItemKind.COLLECTION_FOLDER, CollectionType.MOVIES, false)
                val shows = Library(UUID.randomUUID(), "TV Shows", BaseItemKind.COLLECTION_FOLDER, CollectionType.TVSHOWS, false)
                libraries.value = NavDrawerItemState(allLibraries = listOf(movies, shows))
                advanceUntilIdle()
                for (type in listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE)) {
                    assertTrue("Late loaded $type must be searchable", type in model.state.value.includedSearchableTypes)
                }
                model.onClickExcludeSearchableType(BaseItemKind.MOVIE)
                advanceUntilIdle()
                libraries.value = NavDrawerItemState(allLibraries = listOf(movies))
                advanceUntilIdle()
                assertFalse("Library refresh preserves excluded Movies", BaseItemKind.MOVIE in model.state.value.includedSearchableTypes)
                assertFalse(
                    "Removed TV library removes its episode type",
                    BaseItemKind.EPISODE in model.state.value.possibleSearchableTypes,
                )
            } finally {
                store.clear()
                WholphinDispatchers.Default = oldDefault
                WholphinDispatchers.IO = oldIO
                Dispatchers.resetMain()
            }
        }
}
