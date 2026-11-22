package com.github.damontecres.wholphin.leanback.home

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultForFavoritesFilterOptions
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppTabRow
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.defaultViewOptions
import com.github.damontecres.wholphin.ui.data.sortOptionsFor
import com.github.damontecres.wholphin.ui.detail.FavoritesLoadingState
import com.github.damontecres.wholphin.ui.detail.FavoritesPageState
import com.github.damontecres.wholphin.ui.detail.FavoritesViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Favorites in the flavors without the Watchlist (no-Compose rewrite of ui/detail/FavoritesPage.kt): one tab per kind
 * of favourite that has any (Movies, TV Shows, Episodes, Collections, People…), each the usual sort / filter / view
 * options / random header and grid, remembering the chosen tab when the setting says so. The tabs hide once the grid
 * scrolls past its first row. "No favorites found" when there are none; the error page when loading failed.
 */
@AndroidEntryPoint
class FavoritesFragment : DestinationFragment() {
    private val viewModel: FavoritesViewModel by viewModels()
    private val errorViewModel: ErrorViewModel by viewModels()

    private lateinit var root: FrameLayout
    private var initialized = false
    private var stateKind = 0
    private var tabRow: AppTabRow? = null
    private var tabContent: FrameLayout? = null
    private var tabKeys: List<BaseItemKind> = emptyList()
    private var shownTab: BaseItemKind? = null
    private val folders = HashMap<BaseItemKind, FolderContentView>()

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
        if (!initialized) {
            initialized = true
            viewModel.init()
        }
        viewModel.state.collectWhileStarted { render(it) }
    }

    override fun onDestroyView() {
        folders.clear()
        tabRow = null
        tabContent = null
        shownTab = null
        stateKind = 0
        super.onDestroyView()
    }

    override fun onPageStyleChanged(style: PageStyle) {
        folders.values.forEach { it.pageStyle = style }
    }

    override fun onBackPressed(): Boolean = shownTab?.let { folders[it]?.onBackPressed() } ?: false

    private fun render(state: FavoritesPageState) {
        when (val loading = state.loadingState) {
            is FavoritesLoadingState.Error -> {
                show(KIND_ERROR) { ErrorView(requireContext(), loading.message, loading.exception, onSendLogs = errorViewModel::sendLogs) }
            }

            FavoritesLoadingState.Loading, FavoritesLoadingState.Pending -> {
                show(KIND_LOADING) { LoadingPageView(requireContext()) }
            }

            FavoritesLoadingState.NoFavorites -> {
                show(KIND_EMPTY) {
                    FrameLayout(requireContext()).apply {
                        isFocusable = true
                        addView(
                            AppCompatTextView(context).apply {
                                applyTextRole(TextRole.TITLE_LARGE)
                                setTextColor(AppTheme.palette.onSurface)
                                setText(R.string.no_favorites_found)
                            },
                            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                        )
                    }
                }
            }

            FavoritesLoadingState.Success -> {
                showTabs(state)
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
        folders.clear()
        tabRow = null
        tabContent = null
        shownTab = null
        val v = create()
        root.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (kind == KIND_ERROR || kind == KIND_EMPTY) requestInitialFocus(v)
    }

    /** `KeyedTabbedPage`: the tab row (16dp above and below, clear of the clock) over the selected tab's grid */
    private fun showTabs(state: FavoritesPageState) {
        val context = requireContext()
        if (stateKind != KIND_TABS) {
            show(KIND_TABS) {
                val column =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                    }
                val tabs = AppTabRow(context, keyed = true)
                tabs.onTabClick = { index -> tabKeys.getOrNull(index)?.let(viewModel::updateSelectedTabKey) }
                column.addView(tabs, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val content =
                    FrameLayout(context).apply {
                        clipChildren = false
                        clipToPadding = false
                    }
                column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                tabRow = tabs
                tabContent = content
                column
            }
        }
        val tabs = tabRow ?: return
        val endPadding = if (state.isShowClock) context.dp(184) else 0
        (tabs.layoutParams as LinearLayout.LayoutParams).setMargins(0, context.dp(16), endPadding, context.dp(16))
        val keys = state.tabs.keys.toList()
        if (keys != tabKeys) {
            tabKeys = keys
            tabs.setTabs(state.tabs.values.map { it.getString(context) }, keys.indexOf(state.tabKey))
        } else {
            tabs.selectedIndex = keys.indexOf(state.tabKey)
        }
        val content = tabContent ?: return
        val type = state.tabKey
        val collectionState = state.favorites[type]
        if (shownTab != type) {
            shownTab = type
            content.removeAllViews()
            if (collectionState == null || state.tabs[type] == null) {
                content.addView(
                    LoadingPageView(context, focusEnabled = false, delayMs = 300L),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
                shownTab = null
            } else {
                val folder = folders.getOrPut(type) { folderFor(type) }
                content.addView(folder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // The new tab fades in after 100ms over 300ms, as AnimatedContent did
                folder.alpha = 0f
                folder
                    .animate()
                    .alpha(1f)
                    .setStartDelay(100)
                    .setDuration(300)
                    .start()
            }
        }
        if (collectionState != null) folders[type]?.bind(collectionState)
    }

    private fun folderFor(type: BaseItemKind): FolderContentView {
        val provider = viewModel.createTypedProvider(type)
        val contextMenu = ContextMenuHelper(this, provider) { preferences }
        return FolderContentView(this, provider, contextMenu).apply {
            sortOptions = sortOptionsFor(type)
            filterOptions = DefaultForFavoritesFilterOptions
            defaultViewOptions = type.defaultViewOptions
            pageStyle = this@FavoritesFragment.pageStyle
            onSendLogs = errorViewModel::sendLogs
            onClickItem = { _, item -> viewModel.navigationManager.navigateTo(item.destination()) }
            onPosition = { columns, index -> tabRow?.showIf(index < columns) }
        }
    }

    private companion object {
        const val KIND_LOADING = 1
        const val KIND_ERROR = 2
        const val KIND_EMPTY = 3
        const val KIND_TABS = 4
    }
}
