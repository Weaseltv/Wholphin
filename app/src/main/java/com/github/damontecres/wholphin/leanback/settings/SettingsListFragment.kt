package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.leanback.preference.BaseLeanbackPreferenceFragmentCompat
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.VerticalGridView
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.dp

/**
 * One settings panel level: a Leanback Preference list (`BaseLeanbackPreferenceFragmentCompat`, Leanback's
 * VerticalGridView and preference adapter) under the panel heading. Its rows are built by the page that hosts it
 * ([SettingsPageFragment.buildScreen]) for [screenKey], and refreshed from the page's state ([refresh]).
 *
 * Like the old preference lists, UP on the first row wraps to the last and DOWN on the last wraps to the first.
 */
class SettingsListFragment : BaseLeanbackPreferenceFragmentCompat() {
    private var themedContext: Context? = null
    private val refreshers = mutableListOf<() -> Unit>()
    private var header: SettingsPanelHeader? = null

    /** The key of the screen this level shows, given to [SettingsPageFragment.buildScreen] */
    val screenKey: String get() = requireArguments().getString(ARG_SCREEN).orEmpty()

    private val page: SettingsPageFragment get() = requireParentFragment() as SettingsPageFragment

    /** The panel title shown above the rows */
    var title: CharSequence = ""
        set(value) {
            field = value
            header?.title = value
        }

    /** The rows use the settings preference theme (lb_settings_PreferenceTheme) on top of the activity theme */
    override fun getContext(): Context? {
        themedContext?.let { return it }
        val activity = activity ?: return null
        return ContextThemeWrapper(activity, R.style.lb_settings_PreferenceTheme).also { themedContext = it }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        val manager = preferenceManager
        // Inserted and removed rows keep the focused row (stable preference identities)
        manager.preferenceComparisonCallback = PreferenceManager.SimplePreferenceComparisonCallback()
        val screen = manager.createPreferenceScreen(manager.context)
        preferenceScreen = screen
        page.buildScreen(this, screenKey, screen)
        title = page.screenTitle(screenKey)
        refresh()
    }

    /** Repaints this level in [accent] (the page's section accent changed) */
    fun applyAccent(accent: Int) {
        header?.accent = accent

        fun walk(group: androidx.preference.PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val pref = group.getPreference(i)
                (pref as? SettingsRow)?.accent = accent
                (pref as? SettingsSearchPreference)?.accent = accent
                if (pref is androidx.preference.PreferenceGroup) walk(pref)
            }
        }
        preferenceScreen?.let(::walk)
        @Suppress("NotifyDataSetChanged")
        listView?.adapter?.notifyDataSetChanged()
    }

    /** Registers a refresh of some rows from the page's state (values, summaries, visibility) */
    fun onRefresh(block: () -> Unit) {
        refreshers += block
    }

    /** Re-reads every row from the page's state */
    fun refresh() {
        refreshers.forEach { it() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = super.onCreateView(inflater, container, savedInstanceState)
        val ctx = requireContext()
        val root =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        header =
            SettingsPanelHeader(ctx, page.accent, plain = page.plainHeadings).also {
                it.title = title
                root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        (content as? ViewGroup)?.clipChildren = false
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val grid = listView as? VerticalGridView ?: return
        grid.clipChildren = false
        grid.clipToPadding = false
        val horizontal = view.context.dp(page.listHorizontalPaddingDp)
        val vertical = view.context.dp(SettingsLook.LIST_EDGE_DP)
        grid.setPadding(horizontal, vertical, horizontal, vertical)
        // Keep the focused row a little away from the edges, as the old lists brought it into view
        grid.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        grid.windowAlignmentOffsetPercent = 35f
        if (page.wrapAroundRows) grid.setOnKeyInterceptListener { event -> wrapAround(grid, event) }
        selectFirstRowIfNeeded(grid)
        page.onListViewCreated(this, grid)
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onDestroyView() {
        header = null
        super.onDestroyView()
    }

    private val adapterOrNull: PreferenceGroupAdapter? get() = listView?.adapter as? PreferenceGroupAdapter

    private fun selectableIndices(): List<Int> {
        val adapter = adapterOrNull ?: return emptyList()
        return (0 until adapter.itemCount).filter { i ->
            adapter.getItem(i)?.let { it.isSelectable && it.isVisible } == true
        }
    }

    /** The first time a level shows, focus its first row rather than a heading */
    private fun selectFirstRowIfNeeded(grid: VerticalGridView) {
        val adapter = adapterOrNull ?: return
        val selected = grid.selectedPosition
        val item = adapter.getItem(selected.coerceAtLeast(0))
        if (selected < 0 || item == null || !item.isSelectable) {
            selectableIndices().firstOrNull()?.let { grid.selectedPosition = it }
        }
    }

    /** Selects the row of [key], eg after an inserted row should take over (scrolls to it) */
    fun selectPreference(key: String) {
        val grid = listView as? VerticalGridView ?: return
        val adapter = adapterOrNull ?: return
        val pref = findPreference<androidx.preference.Preference>(key) ?: return
        val index = adapter.getPreferenceAdapterPosition(pref)
        if (index >= 0) grid.setSelectedPositionSmooth(index)
    }

    /** The key of the selected row, if any */
    val selectedKey: String?
        get() {
            val grid = listView as? VerticalGridView ?: return null
            return adapterOrNull?.getItem(grid.selectedPosition.coerceAtLeast(0))?.key
        }

    /** UP on the first row goes to the last, DOWN on the last to the first (the old `lazyListWrapScrolling`) */
    private fun wrapAround(
        grid: VerticalGridView,
        event: KeyEvent,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (!up && !down) return false
        if (!grid.hasFocus()) return false
        val rows = selectableIndices()
        if (rows.size < 2) return false
        val selected = grid.selectedPosition
        return when {
            up && selected == rows.first() -> {
                grid.setSelectedPositionSmooth(rows.last())
                true
            }

            down && selected == rows.last() -> {
                grid.setSelectedPositionSmooth(rows.first())
                true
            }

            else -> {
                false
            }
        }
    }

    companion object {
        private const val ARG_SCREEN = "lb_settings.screen"

        fun newInstance(screenKey: String): SettingsListFragment =
            SettingsListFragment().apply { arguments = bundleOf(ARG_SCREEN to screenKey) }
    }
}
