package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.JellyfinUser
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
import org.jellyfin.sdk.api.client.extensions.userViewsApi
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
    ) {
        private val _state = MutableStateFlow(NavDrawerItemState())
        val state: StateFlow<NavDrawerItemState> = _state

        init {
            // Handle updating the nav drawer when the user changes
            combine(
                serverRepository.currentUserFlow,
                serverRepository.currentUserDtoFlow,
                seerrServerRepository.active,
            ) { user, userDto, discoverActive ->
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
            // Sort here rather than only in updateNavDrawer: the home page builds its default
            // rows from getFilteredUserLibraries, so ordering the rail alone left the home page
            // in raw server order. Sorting at the source means both follow the same list.
            // sortedBy is stable, so any library the flavor does not name keeps its server
            // position, and upstream flavors (blank DEFAULT_NAV_ORDER) are unaffected.
            return libraries.sortedBy { weaselLibraryOrder(it.name) }
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
            val allLibraries = getAllUserLibraries(user.id, userDto.tvAccess)
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

            val navDrawerPins =
                withContext(WholphinDispatchers.IO) {
                    serverPreferencesDao.getNavDrawerPinnedItems(user).associateBy { it.itemId }
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
                }
                .forEach {
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
                )
            }
        }
    }

data class NavDrawerItemState(
    val items: List<NavDrawerItem> = emptyList(),
    val moreItems: List<NavDrawerItem> = emptyList(),
    val nowPlayingEnabled: Boolean = false,
    val nowPlayingTitle: String? = null,
)

val UserDto.tvAccess: Boolean get() = policy?.enableLiveTvAccess == true


/**
 * The flavor's preferred nav rail order.
 *
 * Matches builtins by their stable [NavDrawerItem.id] (e.g. `a_favorites`) and server libraries
 * by NAME, because a library's id differs per server and cannot be baked into a build.
 *
 * 🛑 Only consulted when the user has NOT pinned or reordered that item themselves - a real user
 * preference always wins. Blank for every upstream flavor, where this returns [Int.MAX_VALUE] for
 * everything and the original "builtins first, then server order" behaviour is preserved exactly.
 */
private fun defaultNavOrder(
    item: NavDrawerItem,
    context: Context,
): Int {
    val order = BuildConfig.DEFAULT_NAV_ORDER
    if (order.isBlank()) return Int.MAX_VALUE
    val wanted = order.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val byId = wanted.indexOf(item.id)
    if (byId >= 0) return byId
    val name = runCatching { item.name(context) }.getOrNull() ?: return Int.MAX_VALUE
    val byName = wanted.indexOfFirst { it.equals(name, ignoreCase = true) }
    return if (byName >= 0) byName else Int.MAX_VALUE
}


/**
 * Position of a library in the flavor's preferred order, matched on NAME.
 *
 * [Int.MAX_VALUE] for anything the flavor does not name, and for every upstream flavor, where
 * [BuildConfig.DEFAULT_NAV_ORDER] is blank - a stable sort then leaves the list exactly as the
 * server returned it.
 */
private fun weaselLibraryOrder(name: String): Int {
    val order = BuildConfig.DEFAULT_NAV_ORDER
    if (order.isBlank()) return Int.MAX_VALUE
    val idx =
        order
            .split(',')
            .map { it.trim() }
            .indexOfFirst { it.equals(name.trim(), ignoreCase = true) }
    return if (idx >= 0) idx else Int.MAX_VALUE
}
