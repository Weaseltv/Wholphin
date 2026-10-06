package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.successQueryResult
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NavDrawerLibrariesTest {
    @Test
    fun `UserViews stays member scoped and Collections remains first in libraries and after Requests in menu`() =
        runTest {
            assumeTrue(BuildConfig.FLAVOR == "weaselfin")
            WholphinDispatchers.configure(StandardTestDispatcher(testScheduler))
            try {
                val user = JellyfinUser(1, UUID.randomUUID(), "test member", UUID.randomUUID(), "test-token")
                val views =
                    listOf("Collections", "Movies", "Sports", "Stand Up Comedy", "TV Shows").map { name ->
                        BaseItemDto(
                            id = UUID.randomUUID(),
                            name = name,
                            type = BaseItemKind.COLLECTION_FOLDER,
                            collectionType =
                                when (name) {
                                    "Collections" -> CollectionType.BOXSETS
                                    "TV Shows" -> CollectionType.TVSHOWS
                                    else -> CollectionType.MOVIES
                                },
                        )
                    }
                val api = mockk<ApiClient>()
                val viewsApi = mockk<UserViewsApi>()
                every { api.userViewsApi } returns viewsApi
                coEvery { viewsApi.getUserViews(userId = user.id) } returns successQueryResult(views)
                val repository = mockk<ServerRepository>()
                every { repository.currentUserFlow } returns MutableStateFlow(null)
                every { repository.currentUserDtoFlow } returns MutableStateFlow(null)
                val preferences = mockk<ServerPreferencesDao>()
                coEvery { preferences.getNavDrawerPinnedItems(user) } returns emptyList()
                val seerr = mockk<SeerrServerRepository>()
                every { seerr.active } returns MutableStateFlow(true)
                val music = mockk<MusicService>()
                every { music.state } returns MutableStateFlow(MusicServiceState.EMPTY)
                val management = mockk<MediaManagementService>()
                every { management.deletedItemFlow } returns MutableSharedFlow()
                val service =
                    NavDrawerService(mockk(relaxed = true), backgroundScope, api, repository, preferences, seerr, music, management)
                val libraries = service.getAllUserLibraries(user.id, false)
                assertEquals(views.map { it.name }, libraries.map { it.name })
                service.updateNavDrawer(user, UserDto(id = user.id), true)
                val menu = service.state.value.items
                assertEquals(listOf("a_favorites", "a_discover"), menu.take(2).map { it.id })
                assertEquals(views.map { it.name }, menu.drop(2).map { (it as ServerNavDrawerItem).name })
                val collections = menu[2] as ServerNavDrawerItem
                assertEquals(CollectionType.BOXSETS, collections.type)
                assertEquals(
                    Destination.MediaItem(views.first().id, BaseItemKind.COLLECTION_FOLDER, CollectionType.BOXSETS),
                    collections.destination,
                )
                coVerify(exactly = 2) { viewsApi.getUserViews(userId = user.id) }
            } finally {
                WholphinDispatchers.reset()
            }
        }
}
