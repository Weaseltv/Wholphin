package com.github.damontecres.wholphin.ui.components

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import java.util.UUID

/**
 * What a page supplies to its items' context menu (moved out of ContextMenuUtils.kt so the View
 * `leanback.widget.dialog.ContextMenuHelper` can use it without Compose)
 */
interface ContextMenuProvider {
    fun isAdministrator(): Boolean

    fun navigateTo(destination: Destination)

    fun canDelete(
        item: BaseItem,
        appPreferences: AppPreferences,
    ): Boolean

    fun deleteItem(
        index: Int,
        item: BaseItem,
    )

    fun setWatched(
        position: Int,
        itemId: UUID,
        played: Boolean,
    )

    fun setFavorite(
        position: Int,
        itemId: UUID,
        favorite: Boolean,
    )

    fun sendReportFor(itemId: UUID)
}
