package com.github.damontecres.wholphin.leanback.widget.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha

/**
 * The tab row (TabRow.kt `TabRow` / `KeyedTabRow`): a horizontally scrolling row of tabs. OK on a tab calls
 * [onTabClick] with its index; the page then sets [selectedIndex] (focus alone does not change the selection, as
 * before).
 *
 * Neon look: outline buttons 16dp apart (uppercase label, 12dp side padding, 40dp tall) whose rest outline and label
 * are the accent when selected; the row reaches 20dp past its slot on both sides (`bleedHorizontal`) with 20dp
 * content padding, [topContentPaddingDp] / [bottomContentPaddingDp] above and below. Stock look: 16sp labels (half
 * transparent unless the row has focus or the tab is selected) over a 2dp indicator that is a quarter of the tab wide,
 * or the whole tab in the border colour while focused, and the onSurface colour when selected.
 *
 * Focus: entering the row with LEFT focuses the last tab, with RIGHT the first, otherwise the selected tab. Inside,
 * LEFT / RIGHT move between tabs and leave at the ends (no wrap). When [selectedIndex] changes the row scrolls the
 * selected tab to a third of the way in ([keyed] rows only bring it into view).
 */
class AppTabRow
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        /** `KeyedTabRow`: 20dp content padding on all sides and minimal scrolling to the selected tab */
        val keyed: Boolean = false,
    ) : HorizontalScrollView(context, attrs) {
        private val neon = AppTheme.isNeon
        private val strip =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }
        private val tabs = mutableListOf<View>()
        private var bleedApplied = false

        /** Called with the index of the clicked tab */
        var onTabClick: ((Int) -> Unit)? = null

        var topContentPaddingDp: Float = 20f
            set(value) {
                field = value
                applyPadding()
            }

        var bottomContentPaddingDp: Float = 20f
            set(value) {
                field = value
                applyPadding()
            }

        var selectedIndex: Int = -1
            set(value) {
                val changed = field != value
                field = value
                updateTabs()
                if (changed) scrollToSelected(animate = isLaidOut)
            }

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = false
            clipChildren = false
            clipToPadding = false
            isSmoothScrollingEnabled = true
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
            addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            applyPadding()
        }

        private fun applyPadding() {
            if (!neon) {
                strip.setPadding(0, 0, 0, 0)
                return
            }
            val side = context.dp(20)
            if (keyed) {
                strip.setPadding(side, side, side, side)
            } else {
                strip.setPaddingRelative(side, context.dp(topContentPaddingDp), side, context.dp(bottomContentPaddingDp))
            }
        }

        /** Replace the tabs; [selected] is the selected index (-1 for none) */
        fun setTabs(
            titles: List<String>,
            selected: Int,
        ) {
            strip.removeAllViews()
            tabs.clear()
            titles.forEachIndexed { index, title ->
                val tab: View =
                    if (neon) {
                        AppButton(context).apply {
                            setContentPaddingDp(12f, 0f, 12f, 0f)
                            setLabel(title, allCaps = true)
                        }
                    } else {
                        StockTabView(context).apply { this.title.text = title }
                    }
                tab.id = View.generateViewId()
                tab.setOnClickListener { onTabClick?.invoke(index) }
                tab.onFocusChangeListener = OnFocusChangeListener { _, _ -> updateTabs() }
                tabs.add(tab)
                strip.addView(
                    tab,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0 && neon) marginStart = context.dp(16)
                    },
                )
            }
            selectedIndex = selected
            updateTabs()
        }

        /** The view of tab [index] */
        fun tabAt(index: Int): View? = tabs.getOrNull(index)

        private fun updateTabs() {
            val rowActive = hasFocus()
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                when (tab) {
                    is AppButton -> tab.setSurfaceStyle { scope -> ControlStyles.tab(scope, selected) }
                    is StockTabView -> tab.update(selected = selected, rowActive = rowActive)
                }
            }
        }

        private fun scrollToSelected(animate: Boolean) {
            val tab = tabs.getOrNull(selectedIndex) ?: return
            if (!isLaidOut) {
                post { scrollToSelected(animate = false) }
                return
            }
            val target =
                if (keyed) {
                    val rect = Rect()
                    tab.getDrawingRect(rect)
                    offsetDescendantRectToMyCoords(tab, rect)
                    scrollX + computeScrollDeltaToGetChildRectOnScreen(rect)
                } else {
                    tab.left - strip.paddingStart - (width / 3.5f).toInt()
                }
            val max = (strip.width - width).coerceAtLeast(0)
            val x = target.coerceIn(0, max)
            if (animate) smoothScrollTo(x, 0) else scrollTo(x, 0)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (neon && !bleedApplied) {
                (layoutParams as? MarginLayoutParams)?.let {
                    val bleed = context.dp(20)
                    it.marginStart -= bleed
                    it.marginEnd -= bleed
                    bleedApplied = true
                    layoutParams = it
                }
            }
        }

        override fun addFocusables(
            views: ArrayList<View>,
            direction: Int,
            focusableMode: Int,
        ) {
            if (hasFocus() || tabs.isEmpty() || descendantFocusability == FOCUS_BLOCK_DESCENDANTS) {
                super.addFocusables(views, direction, focusableMode)
                return
            }
            // Entering the row: LEFT lands on the last tab, RIGHT on the first, anything else on the selected one
            val target =
                when (direction) {
                    View.FOCUS_LEFT -> tabs.last()
                    View.FOCUS_RIGHT -> tabs.first()
                    else -> tabs.getOrNull(selectedIndex) ?: tabs.first()
                }
            if (target.isShown) views.add(target) else super.addFocusables(views, direction, focusableMode)
        }

        override fun onRequestFocusInDescendants(
            direction: Int,
            previouslyFocusedRect: Rect?,
        ): Boolean {
            val target =
                when (direction) {
                    View.FOCUS_LEFT -> tabs.lastOrNull()
                    View.FOCUS_RIGHT -> tabs.firstOrNull()
                    else -> tabs.getOrNull(selectedIndex) ?: tabs.firstOrNull()
                }
            return target?.requestFocus(direction, previouslyFocusedRect)
                ?: super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        }
    }

/** The stock (non-Neon) tab: a 16sp label over the 2dp indicator, no focus surface */
internal class StockTabView(
    context: Context,
) : LinearLayout(context) {
    val title: TextView =
        TextView(context).apply {
            applyTextRole(TextRole.BODY_LARGE, sizeSp = 16f)
            val pad = context.dp(16)
            setPaddingRelative(pad, 0, pad, 0)
        }
    private val indicator = Indicator(context)
    private var selectedTab = false
    private var rowActive = false
    private var widthFraction = .25f
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        isSoundEffectsEnabled = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            indicator,
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(2)).apply {
                topMargin = context.dp(4)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
    }

    fun update(
        selected: Boolean,
        rowActive: Boolean,
    ) {
        selectedTab = selected
        this.rowActive = rowActive
        refresh()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
    }

    private fun refresh() {
        val p = AppTheme.palette
        title.setTextColor(if (rowActive || selectedTab) p.onSurface else withAlpha(p.onSurface, .5f))
        val active = rowActive && isFocused
        indicator.color =
            when {
                active -> p.border
                selectedTab -> p.onSurface
                else -> 0
            }
        val target = if (active) 1f else .25f
        if (target != widthFraction) {
            animator?.cancel()
            animator =
                ValueAnimator.ofFloat(widthFraction, target).apply {
                    duration = 300
                    addUpdateListener {
                        widthFraction = it.animatedValue as Float
                        indicator.fraction = widthFraction
                    }
                    start()
                }
        }
        indicator.invalidate()
    }

    /** The indicator bar, [fraction] of the tab wide, centred */
    private class Indicator(
        context: Context,
    ) : View(context) {
        private val paint = Paint()
        var color = 0
        var fraction = .25f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            if ((color ushr 24) == 0) return
            paint.color = color
            val w = width * fraction
            val left = (width - w) / 2f
            canvas.drawRect(left, 0f, left + w, height.toFloat(), paint)
        }
    }
}
