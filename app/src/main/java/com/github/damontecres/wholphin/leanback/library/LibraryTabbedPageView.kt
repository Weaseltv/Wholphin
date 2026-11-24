package com.github.damontecres.wholphin.leanback.library

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppTabRow
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView

/** One tab's content in a [LibraryTabbedPageView] */
interface LibraryTabContent {
    /** The content's root view (created once, kept while the page lives) */
    val view: View

    /** The page is shown again (BACK from a later page) with this tab selected */
    fun onPageShownAgain() {}

    /** The tab was selected and its content shown ([firstTime] when the view was just created) */
    fun onShown(firstTime: Boolean)

    /** BACK while this tab shows; true when consumed (eg the grid jumped to its first card) */
    fun onBackPressed(): Boolean = false

    /** The page's style or theme changed: rebind the cards */
    fun onPageStyleChanged() {}
}

/**
 * `TabbedPage`: the tab row (collapsing like the old `AnimatedVisibility(showTabs)` when the content scrolls past its
 * first row) over the selected tab's content. OK on a tab selects it ([onTabClick]; the page then calls [select]).
 * Before the starting tab is known a delayed, non-focusable loading picture shows.
 *
 * Tab contents are created when first selected and then kept (hidden) while another tab shows, so their scroll
 * position and loaded data stay; the old page rebuilt them from their ViewModels, which kept the same data.
 *
 * The row keeps [clockEndPaddingDp] free at its end for the clock, and the given paddings above / below it (outside)
 * and above / below its tabs (inside).
 */
class LibraryTabbedPageView(
    context: Context,
    titles: List<String>,
    private val createContent: (index: Int) -> LibraryTabContent,
    tabTopPaddingDp: Float = 16f,
    tabContentTopPaddingDp: Float = 20f,
    tabBottomPaddingDp: Float = 16f,
    tabContentBottomPaddingDp: Float = 20f,
    clockEndPaddingDp: Float = 0f,
) : LinearLayout(context) {
    val tabRow = AppTabRow(context)
    private val tabFrame = CollapsibleFrame(context)
    private val contentFrame = FrameLayout(context)
    private val contents = HashMap<Int, LibraryTabContent>()
    private var loading: View? = null

    /** OK on tab [index] */
    var onTabClick: ((Int) -> Unit)? = null

    /** The selected tab, or -1 before it is known */
    var selectedIndex: Int = -1
        private set

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        tabRow.topContentPaddingDp = tabContentTopPaddingDp
        tabRow.bottomContentPaddingDp = tabContentBottomPaddingDp
        tabRow.setTabs(titles, -1)
        tabRow.onTabClick = { index -> onTabClick?.invoke(index) }
        tabFrame.setPaddingRelative(0, context.dp(tabTopPaddingDp), context.dp(clockEndPaddingDp), context.dp(tabBottomPaddingDp))
        tabFrame.addView(tabRow, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(tabFrame, LayoutParams(MATCH, WRAP))
        contentFrame.clipChildren = false
        contentFrame.clipToPadding = false
        addView(contentFrame, LayoutParams(MATCH, 0, 1f))
        // DelayedLoadingPage(focusEnabled = false) until the remembered tab is read
        loading =
            LoadingPageView(
                context,
                focusEnabled = false,
                delayMs = 300,
            ).also { contentFrame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }

    /** Show tab [index] (the ViewModel's selection) */
    fun select(index: Int) {
        if (index == selectedIndex) return
        loading?.let { contentFrame.removeView(it) }
        loading = null
        selectedIndex = index
        tabRow.selectedIndex = index
        contents.forEach { (i, content) -> content.view.visibility = if (i == index) VISIBLE else GONE }
        var firstTime = false
        val content =
            contents.getOrPut(index) {
                firstTime = true
                createContent(index).also { contentFrame.addView(it.view, FrameLayout.LayoutParams(MATCH, MATCH)) }
            }
        content.view.visibility = VISIBLE
        content.onShown(firstTime)
    }

    /** Room kept free at the row's end for the clock (184dp while it shows) */
    fun setClockEndPaddingDp(dp: Float) {
        tabFrame.setPaddingRelative(tabFrame.paddingStart, tabFrame.paddingTop, context.dp(dp), tabFrame.paddingBottom)
    }

    /** `showTabs`: the tab row shows (true) or collapses */
    fun setShowTabs(show: Boolean) {
        tabFrame.setExpanded(show)
    }

    /** The content of the selected tab */
    val selectedContent: LibraryTabContent? get() = contents[selectedIndex]

    /** Focus the selected tab (`tabFocusRequester`) */
    fun focusSelectedTab(): Boolean {
        if (!tabFrame.expanded) tabFrame.setExpanded(true, animate = false)
        return tabRow.tabAt(selectedIndex)?.requestFocus() ?: tabRow.requestFocus()
    }

    fun onBackPressed(): Boolean = selectedContent?.onBackPressed() ?: false

    fun onPageStyleChanged() = contents.values.forEach { it.onPageStyleChanged() }
}
