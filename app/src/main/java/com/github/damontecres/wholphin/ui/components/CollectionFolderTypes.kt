package com.github.damontecres.wholphin.ui.components

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.main.settings.Library
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy

/*
 * Plain library types moved out of CollectionFolderGrid.kt for the no-Compose UI (2026-11-22). Unchanged.
 */

data class PositionItem(
    val position: Int,
    val item: BaseItem,
)

val CollectionType.baseItemKinds: List<BaseItemKind>
    get() =
        when (this) {
            CollectionType.MOVIES -> {
                listOf(BaseItemKind.MOVIE)
            }

            CollectionType.TVSHOWS -> {
                listOf(BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
            }

            CollectionType.HOMEVIDEOS -> {
                listOf(BaseItemKind.VIDEO)
            }

            CollectionType.MUSICVIDEOS -> {
                listOf(BaseItemKind.MUSIC_VIDEO)
            }

            CollectionType.BOXSETS -> {
                listOf(BaseItemKind.BOX_SET)
            }

            CollectionType.PLAYLISTS -> {
                listOf(BaseItemKind.PLAYLIST)
            }

            CollectionType.PHOTOS -> {
                listOf(BaseItemKind.PHOTO, BaseItemKind.PHOTO_ALBUM)
            }

            CollectionType.TRAILERS -> {
                listOf(BaseItemKind.TRAILER)
            }

            CollectionType.BOOKS -> {
                listOf(BaseItemKind.BOOK)
            }

            CollectionType.FOLDERS -> {
                listOf(BaseItemKind.FOLDER)
            }

            CollectionType.LIVETV,
            CollectionType.MUSIC,
            CollectionType.UNKNOWN,
            -> {
                listOf()
            }
        }

data class GridClickActions(
    val onClickItem: (Int, BaseItem) -> Unit,
    val onLongClickItem: ((Int, BaseItem) -> Unit)? = null,
    val onClickPlayAll: ((shuffle: Boolean) -> Unit)? = null,
    val onClickPlayRemoteButton: ((Int, BaseItem) -> Unit)? = null,
)
