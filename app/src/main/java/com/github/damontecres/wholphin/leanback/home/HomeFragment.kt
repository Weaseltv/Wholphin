package com.github.damontecres.wholphin.leanback.home

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.ItemDetailsDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.PlaylistPickerDialog
import com.github.damontecres.wholphin.leanback.widget.text.enabledDisplayToggles
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.components.ContextMenu
import com.github.damontecres.wholphin.ui.components.ContextMenuActions
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.main.HomeState
import com.github.damontecres.wholphin.ui.main.HomeViewModel
import com.github.damontecres.wholphin.ui.main.kidHomeRowKeys
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Home (no-Compose rewrite of ui/main/HomePage.kt): the loading page, the error page with Try again, a kids Home that
 * loaded empty, or [HomeRowsView] with Home's header and rows. Behaviour as before:
 *
 * - Entering Home (first time and every return) asks [HomeViewModel.init], which leaves a fresh Home as it is.
 * - Home starts at its top row whenever it is opened from the side menu or by backing out of another side menu page
 *   (`NavigationManager.homeVisit` changed since Home was last shown), and keeps its row and card only when coming
 *   back from a title opened from one of its rows (owner, 2026-11-11).
 * - OK opens the title (Continue Watching / Next Up play it with "click to play"), the play key plays, OK held opens
 *   the context menu (remove from Continue Watching / Next Up, watched, favourite, playlist, delete, information, and
 *   on Streaming Services reorder / reset order), View more opens the row's grid (MoreHomeRow).
 * - Reordering Streaming Services: LEFT / RIGHT move the card, OK saves the order, BACK puts it back.
 */
@AndroidEntryPoint
class HomeFragment : DestinationFragment() {
    private val viewModel: HomeViewModel by viewModels()
    private val playlistViewModel: AddPlaylistViewModel by viewModels()
    private val errorViewModel: ErrorViewModel by viewModels()

    @Inject
    lateinit var coachMarkTour: CoachMarkTour

    private lateinit var root: FrameLayout
    private var rowsView: HomeRowsView? = null
    private var stateView: View? = null
    private var stateKind: Int = STATE_NONE

    /** The `homeVisit` Home was last shown under; a different one on return means "start at the top" */
    private var shownVisit = -1
    private var resetToTop = false

    /** Reordering the Streaming Services row: the row, the moving card and the order to restore on BACK */
    private var reorderState: ReorderState? = null
    private var homeState: HomeState = HomeState.EMPTY
    private var contextMenu: Dialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                clipChildren = false
                clipToPadding = false
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // Read once per opening: it must not change while Home is on screen or on its way out
        shownVisit = viewModel.navigationManager.homeVisit
        viewModel.state.collectWhileStarted { render(it) }
        preferencesFlow.collectWhileStarted { applyPreferences(it) }
        coachMarkTour.pendingFlow.collectWhileStarted { if (it) rowsView?.checkTour() }
        coachMarkTour.homeRowReadyFlow.collectWhileStarted { rowsView?.checkTour() }
        coachMarkTour.homeToTopFlow.collectWhileStarted { if (it) rowsView?.onTourHomeToTop() }
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) viewModel.init()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        viewModel.init()
        val visit = viewModel.navigationManager.homeVisit
        if (visit != shownVisit) {
            shownVisit = visit
            resetToTop = true
        }
    }

    override fun onScreenFocus(restoring: Boolean) {
        val rows = rowsView
        if (resetToTop) {
            resetToTop = false
            if (rows != null && rows.parent != null) {
                rows.resetToTop(focusNow = true)
                return
            }
        }
        if (!restoring && rows != null && rows.parent != null && rows.focusCurrentRow()) return
        super.onScreenFocus(restoring)
    }

    override fun onBackPressed(): Boolean {
        val reordering = reorderState ?: return false
        viewModel.setRowItems(reordering.row, reordering.original)
        setReorder(null)
        return true
    }

    override fun onPageStyleChanged(style: PageStyle) {
        rowsView?.pageStyle = style
    }

    override fun onDestroyView() {
        contextMenu?.dismiss()
        contextMenu = null
        rowsView = null
        stateView = null
        stateKind = STATE_NONE
        super.onDestroyView()
    }

    private fun applyPreferences(preferences: UserPreferences) {
        val rows = rowsView ?: return
        val ui = preferences.appPreferences.interfacePreferences
        rows.showClock = ui.showClock
        (rows.headerView as? HomeHeaderView)?.let { header ->
            val toggles = enabledDisplayToggles(preferences.appPreferences)
            if (header.showLogo != ui.showLogos || header.displayToggles != toggles) {
                header.showLogo = ui.showLogos
                header.displayToggles = toggles
                rows.refreshHeader()
            }
        }
    }

    private fun render(state: HomeState) {
        homeState = state
        when (val loading = state.loadingState) {
            is LoadingState.Error -> {
                show(STATE_ERROR) {
                    ErrorView(requireContext(), loading, onSendLogs = errorViewModel::sendLogs, onRetry = viewModel::retry)
                }
            }

            LoadingState.Loading, LoadingState.Pending -> {
                show(STATE_LOADING) { LoadingPageView(requireContext()) }
            }

            LoadingState.Success -> {
                val keys = kidHomeRowKeys(state.settings)
                // A kids Home that loaded with nothing to show says so, rather than leaving the page empty
                if (keys != null && state.refreshState == LoadingState.Success &&
                    state.homeRows.none { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                ) {
                    show(STATE_KID_EMPTY) { kidHomeEmpty() }
                } else {
                    val rows = showRows()
                    rows.libraries = state.libraries
                    rows.submit(state.homeRows, keys, state.refreshState)
                }
            }
        }
    }

    private fun show(
        kind: Int,
        create: () -> View,
    ) {
        if (stateKind == kind) return
        stateKind = kind
        root.removeAllViews()
        rowsView?.let { carriedPosition = it.position }
        rowsView = null
        val v = create()
        stateView = v
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (kind == STATE_ERROR) requestInitialFocus(v)
    }

    /** The focused card while the rows were replaced by a loading or error page */
    private var carriedPosition: RowColumn? = null

    private fun showRows(): HomeRowsView {
        rowsView?.let { if (stateKind == STATE_ROWS) return it }
        stateKind = STATE_ROWS
        root.removeAllViews()
        stateView = null
        val context = requireContext()
        val prefs = preferences.appPreferences
        val rows = HomeRowsView(context)
        rows.pageStyle = pageStyle
        rows.splitHomeTitles = true
        rows.showViewMore = true
        rows.showClock = prefs.interfacePreferences.showClock
        rows.coachMarkTour = coachMarkTour
        rows.callbacks = Callbacks()
        rows.setHeader(
            HomeHeaderView(context, showLogo = prefs.interfacePreferences.showLogos).apply {
                displayToggles = enabledDisplayToggles(prefs)
            },
        )
        carriedPosition?.let { rows.restorePosition(it) }
        carriedPosition = null
        root.addView(rows, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rowsView = rows
        reorderState?.let { setReorder(it) }
        return rows
    }

    /** A kids Home with no rows to show yet */
    private fun kidHomeEmpty(): View =
        FrameLayout(requireContext()).apply {
            addView(
                AppCompatTextView(context).apply {
                    applyTextRole(TextRole.TITLE_LARGE)
                    setTextColor(AppTheme.palette.onSurface)
                    setText(R.string.kid_home_empty)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }

    private fun setReorder(state: ReorderState?) {
        reorderState = state
        rowsView?.reorder =
            state?.let { reordering ->
                HomeRowReorder(
                    row = reordering.row,
                    index = reordering.index,
                    onMove = { delta ->
                        val current = reorderState ?: return@HomeRowReorder
                        val size = (homeState.homeRows.getOrNull(current.row) as? HomeRowLoadingState.Success)?.items?.size ?: 0
                        val to = current.index + delta
                        if (to in 0 until size) {
                            viewModel.moveRowItem(current.row, current.index, to)
                            setReorder(current.copy(index = to))
                        }
                    },
                    onDone = {
                        viewModel.saveStreamingOrder(reordering.row)
                        setReorder(null)
                    },
                )
            }
    }

    private inner class Callbacks : HomeRowsCallbacks {
        override fun onClickItem(
            position: RowColumn,
            item: BaseItem,
        ) {
            val prefs = preferences.appPreferences.homePagePreferences
            if (prefs.clickToPlay && homeState.homeRows.getOrNull(position.row).isWatchingRow) {
                viewModel.navigationManager.navigateTo(Destination.Playback(item))
            } else {
                viewModel.navigationManager.navigateTo(item.destination())
            }
        }

        override fun onLongClickItem(
            position: RowColumn,
            item: BaseItem,
        ) = showContextMenu(position, item)

        override fun onClickPlay(
            position: RowColumn,
            item: BaseItem,
        ) {
            viewModel.navigationManager.navigateTo(Destination.Playback(item))
        }

        override fun onClickViewMore(
            position: RowColumn,
            row: HomeRowLoadingState.Success,
        ) {
            val type = row.rowType ?: return
            viewModel.navigationManager.navigateTo(Destination.MoreHomeRow(row.title, type, row.items.size))
        }

        override fun onUpdateBackdrop(item: BaseItem) = viewModel.updateBackdrop(item)

        override fun requestInitialFocus(view: View): Boolean = this@HomeFragment.requestInitialFocus(view)
    }

    private fun showContextMenu(
        position: RowColumn,
        item: BaseItem,
    ) {
        val context = requireContext()
        val row = homeState.homeRows.getOrNull(position.row) as? HomeRowLoadingState.Success
        val canRemoveContinueWatching =
            row?.rowType is HomeRowConfig.ContinueWatching || row?.rowType is HomeRowConfig.ContinueWatchingCombined
        val canRemoveNextUp = row?.rowType is HomeRowConfig.NextUp || row?.rowType is HomeRowConfig.ContinueWatchingCombined
        val streaming = isStreamingRow(row?.rowType)
        val accent = pageStyle.sectionAccent
        val menu =
            ContextMenu.ForBaseItem(
                fromLongClick = true,
                item = item,
                chosenStreams = null,
                showGoTo = true,
                showStreamChoices = false,
                canDelete = viewModel.canDelete(item, preferences.appPreferences),
                canRemoveContinueWatching = canRemoveContinueWatching,
                canRemoveNextUp = canRemoveNextUp,
                showWatchedAndFavorite = !streaming,
                actions =
                    ContextMenuActions(
                        navigateTo = viewModel.navigationManager::navigateTo,
                        onClickWatch = { id, watched -> viewModel.setWatched(id, watched) },
                        onClickFavorite = { id, favorite -> viewModel.setFavorite(id, favorite) },
                        onClickAddPlaylist = { itemId ->
                            playlistViewModel.loadPlaylists()
                            PlaylistPickerDialog(
                                context = context,
                                title = getString(R.string.add_to_playlist),
                                state = playlistViewModel.playlistState,
                                onClick = { playlistViewModel.addToPlaylist(it.id, itemId) },
                                onSearch = playlistViewModel::loadPlaylists,
                                createEnabled = true,
                                onCreatePlaylist = { playlistViewModel.createPlaylistAndAddItem(it, itemId) },
                                elevationDp = 3f,
                                accent = accent,
                            ).show()
                        },
                        onSendMediaInfo = viewModel.serverReportService::sendMediaReportFor,
                        onDeleteItem = { viewModel.deleteItem(position, it) },
                        // Not supported on this page
                        onChooseVersion = { _, _ -> },
                        onChooseTracks = {},
                        onShowOverview = {
                            ItemDetailsDialog(context, ItemDetailsDialogInfo(it), showFilePath = false, accent = accent).show()
                        },
                        onClearChosenStreams = {},
                        onClickRemoveFromNextUp = viewModel::removeFromNextUp,
                        onReorder =
                            if (streaming) {
                                {
                                    val items =
                                        (homeState.homeRows.getOrNull(position.row) as? HomeRowLoadingState.Success)?.items.orEmpty()
                                    setReorder(ReorderState(position.row, position.column, items))
                                }
                            } else {
                                null
                            },
                        onResetOrder = if (streaming) ({ viewModel.resetStreamingOrder(position.row) }) else null,
                    ),
            )
        contextMenu =
            ContextMenuDialog.show(
                context = context,
                contextMenu = menu,
                getMediaSource = null,
                preferredSubtitleLanguage = null,
                onDismissRequest = { contextMenu = null },
                accent = accent,
                opener = rowsView,
            )
    }

    private data class ReorderState(
        val row: Int,
        val index: Int,
        val original: List<BaseItem?>,
    )

    private companion object {
        const val STATE_NONE = 0
        const val STATE_LOADING = 1
        const val STATE_ERROR = 2
        const val STATE_KID_EMPTY = 3
        const val STATE_ROWS = 4
    }
}
