package com.github.damontecres.wholphin.ui.preferences

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.NavDrawerPinnedItem
import com.github.damontecres.wholphin.data.model.NavPinType
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject

/*
 * The side menu (nav drawer) pins and order editor's state, Compose-free (no-Compose rewrite, 2026-11-22). Moved
 * unchanged from NavDrawerPreference.kt.
 */

/** One side-menu item in the pins editor */
data class NavDrawerPin(
    val id: String,
    val title: String,
    val pinned: Boolean,
    val item: NavDrawerItem,
) {
    companion object {
        fun create(
            context: Context,
            items: Map<NavDrawerItem, Boolean>,
        ) {
            items.map { (item, pinned) ->
                NavDrawerPin(item.id, item.name(context), pinned, item)
            }
        }
    }
}

internal fun <T> List<T>.move(
    direction: MoveDirection,
    index: Int,
): List<T> =
    toMutableList().apply {
        if (direction == MoveDirection.DOWN) {
            val down = this[index]
            val up = this[index + 1]
            set(index, up)
            set(index + 1, down)
        } else {
            val up = this[index]
            val down = this[index - 1]
            set(index - 1, up)
            set(index, down)
        }
    }

@HiltViewModel
class NavDrawerPreferencesViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val navigationManager: NavigationManager,
        val backdropService: BackdropService,
        private val serverRepository: ServerRepository,
        private val navDrawerService: NavDrawerService,
        private val serverPreferencesDao: ServerPreferencesDao,
        private val seerrServerRepository: SeerrServerRepository,
    ) : ViewModel() {
        val state = MutableStateFlow<List<NavDrawerPin>>(listOf())

        init {
            viewModelScope.launchDefault {
                val state = navDrawerService.state.value
                val user = serverRepository.currentUser
                val seerr = seerrServerRepository.active.firstOrNull()
                if (user == null || seerr == null) {
                    return@launchDefault
                }
                val navDrawerPins =
                    withContext(WholphinDispatchers.IO) {
                        serverPreferencesDao
                            .getNavDrawerPinnedItems(user)
                            .associateBy { it.itemId }
                    }
                val allItems = state.let { it.items + it.moreItems }
                val pins =
                    allItems
                        .sortedBy {
                            navDrawerPins[it.id]?.order?.takeIf { it >= 0 } ?: Int.MAX_VALUE
                        }.mapNotNull {
                            if (!seerr && it is NavDrawerItem.Discover) {
                                null
                            } else {
                                // Assume pinned if unknown
                                val pinned = navDrawerPins[it.id]?.type ?: NavPinType.PINNED
                                NavDrawerPin(
                                    it.id,
                                    it.name(context),
                                    pinned == NavPinType.PINNED,
                                    it,
                                )
                            }
                        }
                this@NavDrawerPreferencesViewModel.state.value = pins
            }
        }

        fun update(items: List<NavDrawerPin>) {
            state.update { items }
        }

        fun save() {
            viewModelScope.launchIO(ExceptionHandler(true)) {
                serverRepository.currentUser?.let { user ->
                    serverRepository.currentUserDto?.let { userDto ->
                        if (user.id == userDto.id) {
                            val toSave =
                                state.value.mapIndexed { index, item ->
                                    NavDrawerPinnedItem(
                                        user.rowId,
                                        item.id,
                                        if (item.pinned) NavPinType.PINNED else NavPinType.UNPINNED,
                                        index,
                                    )
                                }
                            serverPreferencesDao.saveNavDrawerPinnedItems(*toSave.toTypedArray())
                            val discoverActive = seerrServerRepository.active.firstOrNull() == true
                            navDrawerService.updateNavDrawer(user, userDto, discoverActive)
                        } else {
                            throw IllegalStateException("User IDs do not match")
                        }
                    }
                }
            }
        }
    }
