package com.github.damontecres.wholphin.leanback.widget.dialog

import android.app.Dialog
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.pageSectionAccent
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo

/**
 * The basic context menu of grids and rows (`ContextMenuUtils` / `rememberContextMenu` as plain code). Create one per
 * screen and call [showContextMenu] from a card's long press:
 *
 * ```
 * private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }
 * card.setOnLongClickListener { contextMenu.showContextMenu(position, item, it); true }
 * ```
 *
 * The menu is the same as before: Go to, play / resume / restart, shuffle, remove from playlist, add to playlist,
 * delete (when [ContextMenuProvider.canDelete]), mark watched, add / remove favorite, go to series, media
 * information, play with, send media info; no stream choices. "Add to playlist" opens the playlist picker (with the
 * screen's [AddPlaylistViewModel]), "Media information" the [ItemDetailsDialog] (file paths for administrators).
 *
 * @param fragment the screen (a Hilt `@AndroidEntryPoint` fragment); its accent colours the dialogs
 * @param preferences the current user preferences
 */
class ContextMenuHelper(
    private val fragment: Fragment,
    private val provider: ContextMenuProvider,
    private val preferences: () -> UserPreferences,
) {
    private val playlistViewModel: AddPlaylistViewModel by lazy {
        ViewModelProvider(fragment)[AddPlaylistViewModel::class.java]
    }
    private var menu: Dialog? = null

    /** A menu is showing */
    val isShowing: Boolean get() = menu?.isShowing == true

    /**
     * Show the context menu for [item] at [position] (typically from a long click). [opener] is the long-pressed
     * view; focus returns to it when the menu closes.
     */
    fun showContextMenu(
        position: Int,
        item: BaseItem,
        opener: View? = null,
    ) {
        val context = fragment.requireContext()
        val accent = fragment.pageSectionAccent()
        val actions =
            ContextMenuActions(
                navigateTo = provider::navigateTo,
                onClickWatch = { itemId, watched -> provider.setWatched(position, itemId, watched) },
                onClickFavorite = { itemId, favorite -> provider.setFavorite(position, itemId, favorite) },
                onClickAddPlaylist = { itemId ->
                    playlistViewModel.loadPlaylists()
                    PlaylistPickerDialog(
                        context = context,
                        title = context.getString(R.string.add_to_playlist),
                        state = playlistViewModel.playlistState,
                        onClick = { playlistViewModel.addToPlaylist(it.id, itemId) },
                        onSearch = playlistViewModel::loadPlaylists,
                        createEnabled = true,
                        onCreatePlaylist = { playlistViewModel.createPlaylistAndAddItem(it, itemId) },
                        elevationDp = 3f,
                        accent = accent,
                    ).show()
                },
                onSendMediaInfo = provider::sendReportFor,
                onDeleteItem = { provider.deleteItem(position, it) },
                onShowOverview = {
                    ItemDetailsDialog(
                        context = context,
                        info = ItemDetailsDialogInfo(it),
                        showFilePath = provider.isAdministrator(),
                        accent = accent,
                    ).show()
                },
                // Not supported on these pages
                onChooseVersion = { _, _ -> },
                onChooseTracks = { },
                onClearChosenStreams = { },
            )
        val contextMenu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = provider.canDelete(item, preferences().appPreferences),
                canRemoveContinueWatching = false,
                canRemoveNextUp = false,
                actions = actions,
            )
        menu =
            ContextMenuDialog.show(
                context = context,
                contextMenu = contextMenu,
                getMediaSource = null,
                preferredSubtitleLanguage = null,
                onDismissRequest = { menu = null },
                accent = accent,
                opener = opener,
            )
    }
}
