package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.NavDrawerPinnedItem
import com.github.damontecres.wholphin.data.model.NavPinType
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.ui.collectLatestIn
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.supportedCollectionTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * Gets the items to show in the nav drawer
 */
@Singleton
class NavDrawerService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:DefaultCoroutineScope private val coroutineScope: CoroutineScope,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val serverPreferencesDao: ServerPreferencesDao,
        private val seerrServerRepository: SeerrServerRepository,
        private val musicService: MusicService,
        private val mediaManagementService: MediaManagementService,
    ) {
        private val _state = MutableStateFlow(NavDrawerItemState())
        val state: StateFlow<NavDrawerItemState> = _state

        private val refreshRequests = MutableStateFlow(0)

        init {
            // Handle updating the nav drawer when the user changes
            combine(
                serverRepository.currentUserFlow,
                serverRepository.currentUserDtoFlow,
                seerrServerRepository.active,
                refreshRequests,
            ) { user, userDto, discoverActive, _ ->
                Triple(user, userDto, discoverActive)
            }.collectLatestIn(coroutineScope) { (user, userDto, discoverActive) ->
                Timber.d(
                    "User updated: user=%s, userDto=%s, discoverActive=%s",
                    user?.id,
                    userDto?.id,
                    discoverActive,
                )
                try {
                    if (user != null && userDto != null && user.id == userDto.id) {
                        updateNavDrawer(user, userDto, discoverActive)
                    } else {
                        _state.update { NavDrawerItemState() }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error updating nav drawer")
                    showToast(context, "Error fetching user's views")
                }
            }

            // The server only lists a Playlists library while the member has a playlist
            mediaManagementService.deletedItemFlow.collectLatestIn(coroutineScope) {
                if (it.item.type == BaseItemKind.PLAYLIST) refresh()
            }

            // Handle when music is actively playing or not
            coroutineScope.launchDefault {
                musicService.state.collectLatest { music ->
                    Timber.v("MusicService updated")
                    when (music.status) {
                        NowPlayingStatus.PLAYING -> {
                            _state.update {
                                it.copy(
                                    nowPlayingEnabled = true,
                                    nowPlayingTitle = music.currentItemTitle,
                                )
                            }
                        }

                        NowPlayingStatus.IDLE -> {
                            _state.update {
                                it.copy(
                                    nowPlayingEnabled = false,
                                    nowPlayingTitle = null,
                                )
                            }
                        }

                        NowPlayingStatus.PAUSED -> {
                            delay(2.hours)
                            _state.update {
                                it.copy(
                                    nowPlayingEnabled = false,
                                    nowPlayingTitle = null,
                                )
                            }
                        }
                    }
                }
            }
        }

        /**
         * Re-read the user's libraries, e.g. after their first playlist makes the server add a
         * Playlists library. Otherwise the nav drawer only changes when the user does.
         */
        fun refresh() {
            refreshRequests.update { it + 1 }
        }

        /**
         * Get all the libraries the user has access to
         */
        suspend fun getAllUserLibraries(
            userId: UUID,
            tvAccess: Boolean,
        ): List<Library> {
            val userViews =
                api.userViewsApi
                    .getUserViews(userId = userId)
                    .content.items
            val recordingFolders =
                if (tvAccess) {
                    try {
                        api.liveTvApi
                            .getRecordingFolders(userId = userId)
                            .content.items
                            .map { it.id }
                            .toSet()
                    } catch (ex: InvalidStatusException) {
                        if (ex.status == 401 || ex.status == 403) {
                            Timber.w("Got HTTP %s querying for recording folders", ex.status)
                            emptySet()
                        } else {
                            throw ex
                        }
                    }
                } else {
                    emptySet()
                }
            val libraries =
                userViews
                    .filter { it.collectionType in supportedCollectionTypes || it.id in recordingFolders }
                    .map {
                        Library(
                            itemId = it.id,
                            name = it.name ?: "",
                            type = it.type,
                            collectionType = it.collectionType ?: CollectionType.UNKNOWN,
                            isRecordingFolder = it.id in recordingFolders,
                        )
                    }
            // UserViews already follows the member's server-configured library order.
            return libraries
        }

        /**
         * Get the libraries that the user has not "pinned". These will show in the More section.
         */
        suspend fun getFilteredUserLibraries(
            user: JellyfinUser,
            tvAccess: Boolean,
        ): List<Library> {
            val pins =
                serverPreferencesDao
                    .getNavDrawerPinnedItems(user)
                    .associateBy { it.itemId }
            val libraries =
                getAllUserLibraries(user.id, tvAccess)
                    .filterNot { pins[ServerNavDrawerItem.getId(it.itemId)]?.type == NavPinType.UNPINNED }
            return libraries
        }

        /** Match Jellyfin's member library order once without replacing their other preferences. */
        private suspend fun restoreServerLibraryOrder(
            user: JellyfinUser,
            libraries: List<Library>,
        ): List<Library> {
            if (BuildConfig.FLAVOR != "weaselfin" || libraries.isEmpty()) return libraries
            val migrations = context.getSharedPreferences("sidebar_palette_migrations", Context.MODE_PRIVATE)
            val key = "palette_v5_server_order_${user.rowId}"
            if (migrations.getBoolean(key, false)) return libraries
            val ordered =
                libraries.sortedBy {
                    defaultNavOrder(
                        ServerNavDrawerItem(
                            itemId = it.itemId,
                            name = it.name,
                            destination = Destination.MediaItem(it.itemId, it.type, it.collectionType),
                            type = it.collectionType,
                        ),
                        context,
                    )
                }
            try {
                // Fetch immediately before writing so unrelated server preferences stay current.
                val configuration =
                    api.userApi
                        .getUserById(user.id)
                        .content.configuration ?: return ordered
                val ids = ordered.map { it.itemId }
                val orderedViews = ids + configuration.orderedViews.orEmpty().filterNot { it in ids }
                if (configuration.orderedViews != orderedViews) {
                    api.userApi.updateUserConfiguration(user.id, configuration.copy(orderedViews = orderedViews))
                }
                migrations.edit().putBoolean(key, true).apply()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Keep the sidebar usable and retry the server update on the next refresh.
                Timber.w(ex, "Unable to save the approved server library order")
                showToast(context, "Couldn't save server library order; will retry on the next load")
            }
            return ordered
        }

        /**
         * Update the current state of the nav drawer items
         */
        suspend fun updateNavDrawer(
            user: JellyfinUser,
            userDto: UserDto,
            discoverActive: Boolean,
        ) {
            val builtins =
                buildList {
                    add(NavDrawerItem.Favorites)
                    if (discoverActive) add(NavDrawerItem.Discover)
                }
            var allLibraries = getAllUserLibraries(user.id, userDto.tvAccess)
            allLibraries = restoreServerLibraryOrder(user, allLibraries)
            val libraries =
                allLibraries
                    .map {
                        val destination =
                            if (it.isRecordingFolder) {
                                Destination.Recordings(it.itemId)
                            } else {
                                Destination.MediaItem(
                                    it.itemId,
                                    it.type,
                                    it.collectionType,
                                )
                            }
                        ServerNavDrawerItem(
                            itemId = it.itemId,
                            name = it.name,
                            destination = destination,
                            type = it.collectionType,
                        )
                    }
            val allItems = builtins + libraries

            var navDrawerPins =
                withContext(WholphinDispatchers.IO) {
                    serverPreferencesDao.getNavDrawerPinnedItems(user).associateBy { it.itemId }
                }

            // Restore the owner's requested order once; preserve future explicit reordering.
            if (BuildConfig.FLAVOR == "weaselfin") {
                val migrations = context.getSharedPreferences("sidebar_palette_migrations", Context.MODE_PRIVATE)
                val key = "palette_v5_order_${user.rowId}"
                if (!migrations.getBoolean(key, false) && libraries.isNotEmpty()) {
                    val restored =
                        allItems.sortedBy { defaultNavOrder(it, context) }.mapIndexed { index, item ->
                            NavDrawerPinnedItem(user.rowId, item.id, navDrawerPins[item.id]?.type ?: NavPinType.PINNED, index)
                        }
                    withContext(WholphinDispatchers.IO) {
                        serverPreferencesDao.saveNavDrawerPinnedItems(*restored.toTypedArray())
                    }
                    navDrawerPins = restored.associateBy { it.itemId }
                    migrations.edit().putBoolean(key, true).apply()
                }
            }

            val items = mutableListOf<NavDrawerItem>()
            val moreItems = mutableListOf<NavDrawerItem>()
            allItems
                // Sort by order if non-default, existing items before customize will have -1 value
                // Otherwise fall back to the flavor's preferred order, then to Int.MAX_VALUE
                // Items the user doesn't have access to anymore will be skipped
                .sortedBy {
                    navDrawerPins[it.id]?.order?.takeIf { order -> order >= 0 }
                        ?: defaultNavOrder(it, context)
                }.forEach {
                    // Assume pinned if unknown
                    val pinned = navDrawerPins[it.id]?.type ?: NavPinType.PINNED
                    if (pinned == NavPinType.PINNED) {
                        items.add(it)
                    } else {
                        moreItems.add(it)
                    }
                }

            _state.update {
                it.copy(
                    items = items,
                    moreItems = moreItems,
                    allLibraries = allLibraries,
                )
            }
        }
    }

data class NavDrawerItemState(
    val items: List<NavDrawerItem> = emptyList(),
    val moreItems: List<NavDrawerItem> = emptyList(),
    val nowPlayingEnabled: Boolean = false,
    val nowPlayingTitle: String? = null,
    val allLibraries: List<Library> = emptyList(),
)

val UserDto.tvAccess: Boolean get() = policy?.enableLiveTvAccess == true

/**
 * Flavor builtin and named-library order, followed by other libraries, then Playlists.
 * Explicit per-user pins/reordering take precedence in updateNavDrawer.
 * A blank order preserves upstream behavior.
 */
@VisibleForTesting
internal fun defaultNavOrder(
    item: NavDrawerItem,
    context: Context,
    order: String = BuildConfig.DEFAULT_NAV_ORDER,
): Int {
    if (order.isBlank()) return Int.MAX_VALUE
    val unnamed = Int.MAX_VALUE - 1
    val wanted = order.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (item is ServerNavDrawerItem) {
        val byName = wanted.indexOfFirst { it.equals(item.name, ignoreCase = true) }
        if (byName >= 0) return byName
        val byType =
            when (item.type) {
                CollectionType.BOXSETS -> wanted.indexOf("Collections")
                CollectionType.MOVIES -> wanted.indexOf("Movies")
                CollectionType.TVSHOWS -> wanted.indexOf("TV Shows")
                else -> -1
            }
        return when {
            byType >= 0 -> byType
            item.type == CollectionType.PLAYLISTS -> Int.MAX_VALUE
            else -> unnamed
        }
    }
    val byId = wanted.indexOf(item.id)
    if (byId >= 0) return byId
    val name = runCatching { item.name(context) }.getOrNull() ?: return unnamed
    val byName = wanted.indexOfFirst { it.equals(name, ignoreCase = true) }
    return if (byName >= 0) byName else unnamed
}
