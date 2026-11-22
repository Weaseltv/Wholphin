package com.github.damontecres.wholphin.leanback.widget.text

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.NavTiming
import kotlin.math.ceil
import kotlin.math.max

/**
 * Header dimensions of detail and home pages (`HeaderUtils` as plain values, in dp). The `*Padding` functions
 * depend on the theme, as the old composables did.
 */
object HeaderDimens {
    const val TOP_PADDING = 48f
    const val BOTTOM_PADDING = 32f
    const val START_PADDING = 8f
    const val HEIGHT = 180f
    const val LOGO_HEIGHT = 60f

    /** `detailTopPadding()`: 16dp on the WeaselTV theme, 48dp elsewhere */
    fun detailTopPadding(): Float = if (AppTheme.isNeon) 16f else TOP_PADDING

    /** `detailInfoTextScale()`: details under a detail page's title are 20 % larger on the WeaselTV theme */
    fun detailInfoTextScale(): Float = if (AppTheme.isNeon) 1.2f else 1f

    /** Padding (start, top, end, bottom) and fixed height (null for natural) of a Home / Recommended header */
    data class HeaderBox(
        val start: Float,
        val top: Float,
        val end: Float,
        val bottom: Float,
        val height: Float?,
    )

    /** `HeaderUtils.modifier`: 48 top, 32 bottom, 8 start, 180dp tall */
    val standard = HeaderBox(START_PADDING, TOP_PADDING, 0f, BOTTOM_PADDING, HEIGHT)

    /** `homeModifier()`: WeaselTV 27dp overscan top, 12dp below, natural height; otherwise [standard] */
    fun home(): HeaderBox = if (AppTheme.isNeon) HeaderBox(START_PADDING, 27f, 0f, 12f, null) else standard

    /** `recommendedModifier()`: WeaselTV 4dp top (the tabs supply the inset), 12dp below; otherwise [home] */
    fun recommended(): HeaderBox = if (AppTheme.isNeon) HeaderBox(START_PADDING, 4f, 0f, 12f, null) else home()

    /** Apply a [HeaderBox] to [view] (padding, and the height when fixed) */
    fun apply(
        view: View,
        box: HeaderBox,
    ) {
        val c = view.context
        view.setPaddingRelative(c.dp(box.start), c.dp(box.top), c.dp(box.end), c.dp(box.bottom))
        box.height?.let { h -> view.layoutParams?.let { it.height = c.dp(h) + c.dp(box.top) + c.dp(box.bottom) } }
    }
}

/**
 * The measured budget of a detail page's header (`DetailViewportState`): the page's size, the first row's height and
 * the action bar's height, reported by those views. Once the page is revealed ([lock]) the header keeps the sizes it
 * was first shown with, so rows loading below never resize it. Listeners are told about every change.
 */
class DetailViewport(
    heightPx: Int,
    widthPx: Int,
    private val density: Float,
) {
    var heightPx: Int = heightPx
        internal set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }
    var widthPx: Int = widthPx
        internal set

    var firstRowHeightPx: Int = (280 * density).toInt()
        private set
    private var firstRow: Any? = null
    private var firstRowPriority = Int.MAX_VALUE
    var actionBarHeightPx: Int = (72 * density).toInt()
        private set
    var rowReported = false
        private set
    var actionBarReported = false
        private set
    var lockedRowHeightPx: Int? = null
        private set
    var lockedActionBarHeightPx: Int? = null
        private set
    var needsRow = false
        private set
    var needsActionBar = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }

    /** A row ([key]) measured [heightPx]; the highest priority (lowest number) row is the first row */
    fun reportRow(
        key: Any,
        heightPx: Int,
        priority: Int = Int.MAX_VALUE,
    ) {
        if (firstRow == null || priority < firstRowPriority) {
            firstRow = key
            firstRowPriority = priority
        }
        if (firstRow === key) firstRowHeightPx = heightPx
        rowReported = true
        notifyChanged()
    }

    fun reportActionBar(heightPx: Int) {
        actionBarHeightPx = heightPx
        actionBarReported = true
        notifyChanged()
    }

    val isLocked: Boolean get() = lockedRowHeightPx != null

    fun requireMeasurements(
        row: Boolean,
        actionBar: Boolean,
    ) {
        if (row) needsRow = true
        if (actionBar) needsActionBar = true
    }

    val measurementsReady: Boolean get() = (!needsRow || rowReported) && (!needsActionBar || actionBarReported)

    fun lock() {
        if (lockedRowHeightPx == null) {
            lockedRowHeightPx = firstRowHeightPx
            lockedActionBarHeightPx = actionBarHeightPx
            notifyChanged()
        }
    }
}

/** The [DetailViewport] of the detail page this view is on, if any (the old `LocalDetailViewport`) */
fun View.detailViewport(): DetailViewport? {
    var v: View? = this
    while (v != null) {
        (v.getTag(R.id.lb_widget_ctl_detail_viewport) as? DetailViewport)?.let { return it }
        v = v.parent as? View
    }
    return null
}

/** Report this view's height as a detail row (`reportDetailRowHeight`), whenever it changes */
fun View.reportDetailRowHeight(priority: Int = Int.MAX_VALUE) {
    val key = Any()
    addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) v.detailViewport()?.reportRow(key, bottom - top, priority)
    }
}

/** Report this view's height as the detail action bar (`reportDetailActionHeight`) */
fun View.reportDetailActionHeight() {
    addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) v.detailViewport()?.reportActionBar(bottom - top)
    }
}

/**
 * A detail page's root (`DetailPageViewport`): on the WeaselTV theme it provides a [DetailViewport] to the views
 * inside and keeps the page invisible until the header knows the first row and action bar sizes (or 300 ms pass),
 * then fixes the header's size and fades the page in over 100 ms, so nothing shifts as it appears. Other themes show
 * the page at once and provide no viewport.
 */
class DetailPageLayout(
    context: Context,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private var viewport: DetailViewport? = null
    private val revealCheck: () -> Unit = { if (viewport?.measurementsReady == true) reveal() }
    private val timeout = Runnable { reveal() }
    private var revealed = false

    init {
        clipChildren = false
        if (neon) alpha = 0f
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!neon) return
        val vp = viewport
        if (vp == null) {
            DetailViewport(h, w, resources.displayMetrics.density).also {
                viewport = it
                setTag(R.id.lb_widget_ctl_detail_viewport, it)
                it.addListener(revealCheck)
            }
            postDelayed(timeout, HEADER_MEASURE_TIMEOUT_MS)
        } else {
            vp.heightPx = h
            vp.widthPx = w
        }
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        removeCallbacks(timeout)
        // Let the reported sizes settle for a frame before fixing the header's size
        postOnAnimation {
            viewport?.lock()
            NavTiming.mark("page revealed", once = true)
            ValueAnimator.ofFloat(alpha, 1f).apply {
                duration = 100
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(timeout)
        super.onDetachedFromWindow()
    }

    companion object {
        /** How long a page waits for its first row and action bar to be measured before showing */
        const val HEADER_MEASURE_TIMEOUT_MS = 300L
    }
}

/**
 * Fits a detail header above the first row and action bar (`FittedDetailHeader` + `FitHeaderToHeight`): its budget is
 * the page height minus the first row ([firstRowHeightDp], or the measured / locked one) minus the action bar and a
 * 32dp gutter ([reservedHeightDp] instead when given), at least 1dp. The child is measured at its natural height and,
 * when taller, scaled down from the top-left corner to fit (never clipped); cards and buttons outside keep their
 * approved sizes. [onCompactChanged] reports `budget < 230dp` (the old `LocalCompactDetailHeader`), which the title,
 * overview and genre views use.
 *
 * Without a [DetailViewport] (other themes) the child is laid out as is.
 */
class FittedDetailHeaderLayout(
    context: Context,
    private val reservedHeightDp: Float? = null,
    private val firstRowHeightDp: Float? = null,
) : ViewGroup(context) {
    private var viewport: DetailViewport? = null
    private val onViewportChanged: () -> Unit = { requestLayout() }

    /** Called when the header becomes compact or not */
    var onCompactChanged: ((Boolean) -> Unit)? = null

    var compact: Boolean = false
        private set

    /** With a fixed height budget instead of a detail viewport ([FitHeaderToHeight] standalone), in px */
    var maxHeightPx: Int? = null
        set(value) {
            field = value
            requestLayout()
        }

    /** Take the whole budget even when the content is shorter (`fillHeight`) */
    var fillHeight: Boolean = false

    private var scale = 1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewport =
            detailViewport()?.also {
                it.requireMeasurements(row = firstRowHeightDp == null, actionBar = reservedHeightDp == null)
                it.addListener(onViewportChanged)
            }
    }

    override fun onDetachedFromWindow() {
        viewport?.removeListener(onViewportChanged)
        viewport = null
        super.onDetachedFromWindow()
    }

    private fun budgetPx(): Int? {
        maxHeightPx?.let { return it }
        val vp = viewport ?: return null
        val rowHeight = firstRowHeightDp?.let { context.dp(it) } ?: vp.lockedRowHeightPx ?: vp.firstRowHeightPx
        val actionBar = vp.lockedActionBarHeightPx ?: vp.actionBarHeightPx
        val reserved = reservedHeightDp?.let { context.dp(it) } ?: (actionBar + context.dp(32))
        return max(vp.heightPx - rowHeight - reserved, context.dp(1))
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val child = if (childCount > 0) getChildAt(0) else null
        val budget = budgetPx()
        if (viewport != null && maxHeightPx == null && budget != null) {
            val nowCompact = budget < context.dp(230)
            if (nowCompact != compact) {
                compact = nowCompact
                onCompactChanged?.invoke(nowCompact)
            }
        }
        if (child == null) {
            setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), 0)
            return
        }
        if (budget == null) {
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            scale = 1f
            setMeasuredDimension(resolveSize(child.measuredWidth, widthMeasureSpec), resolveSize(child.measuredHeight, heightMeasureSpec))
            return
        }
        child.measure(
            getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.MATCH_PARENT),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val natural = child.measuredHeight
        val limit = budget.coerceAtLeast(1)
        scale = if (natural > limit) limit.toFloat() / natural else 1f
        val height = if (fillHeight) limit else ceil(natural * scale).toInt()
        setMeasuredDimension(resolveSize(child.measuredWidth, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val child = if (childCount > 0) getChildAt(0) else return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
    }
}

/**
 * The room a row of cards keeps above, below and (on detail pages) at the end for the focused card's enlargement,
 * outline and glow (`FocusSafeLazyRow` / `keepFocusedItemVisible`), in px: on the WeaselTV theme
 * `contentHeight * (focusScale - 100) / 200 + glowSpread + borderWidth`, at least 8dp; 0 elsewhere.
 */
fun focusClearancePx(
    context: Context,
    appearance: HomeCardAppearance,
    contentHeightPx: Int,
): Int {
    if (!AppTheme.isNeon) return 0
    val scaleRoom = contentHeightPx * ((appearance.focusScalePercent - 100).coerceAtLeast(0) / 200f)
    val decoration = context.dp(appearance.glowSpreadDp.toFloat() + appearance.borderWidthDp.toFloat())
    return max((scaleRoom + decoration).toInt(), context.dp(8))
}
