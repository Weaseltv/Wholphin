package com.github.damontecres.wholphin.leanback.widget.card

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.HorizontalGridView
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ExtrasItem
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonRulePainter
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.util.RowLoadingState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val WEASELFIN = BuildConfig.FLAVOR == "weaselfin"

/**
 * How a row scrolls to its focused card (the old `LocalBringIntoViewSpec` of the page):
 * [PIVOT] keeps the focused card's leading edge at 30 % of the row (the TV default, Home and most pages);
 * [KEEP_VISIBLE] scrolls only enough to show the card inside the row's padding (WeaselTV detail pages,
 * `KeepVisibleBringIntoViewSpec`). Use [forDetailPage] on detail pages.
 */
enum class RowScrollMode {
    PIVOT,
    KEEP_VISIBLE,
    ;

    companion object {
        /** Detail pages kept items visible on the WeaselTV theme and pivoted on the others */
        fun forDetailPage(): RowScrollMode = if (AppTheme.isNeon) KEEP_VISIBLE else PIVOT
    }
}

/** Padding around a row's cards in dp (`cardContentPadding`) */
data class CardPadding(
    val startDp: Float,
    val endDp: Float,
    val topDp: Float,
    val bottomDp: Float,
) {
    companion object {
        fun all(dp: Float) = CardPadding(dp, dp, dp, dp)

        fun symmetric(
            horizontalDp: Float,
            verticalDp: Float,
        ) = CardPadding(horizontalDp, horizontalDp, verticalDp, verticalDp)
    }
}

/**
 * Look of a row title (`ItemRowTitle`); defaults are the old ones (owner-approved values in the weaselfin flavor).
 * [titleStartPaddingDp] null means 8dp for a lone title; a [CardRowView] uses its cards' start padding instead.
 */
data class RowTitleStyle(
    /** Gap between the title text and its rule (`titleDividerGap` / `dividerGap` of ItemRowTitle) */
    val dividerGapDp: Float = if (WEASELFIN) ApprovedHomeLayout.TITLE_DIVIDER_GAP_DP.toFloat() else 4f,
    val dividerThicknessDp: Float = if (WEASELFIN) ApprovedHomeLayout.DIVIDER_THICKNESS_DP.toFloat() else 1f,
    val dividerGlowDp: Float = if (WEASELFIN) ApprovedHomeLayout.DIVIDER_GLOW_DP.toFloat() else 12f,
    val dividerGlowStrength: Float = if (WEASELFIN) ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f else .35f,
    val titleSizeSp: Float = if (WEASELFIN) ApprovedHomeLayout.TITLE_SIZE_SP.toFloat() else 22f,
    /** Letter spacing in sp; null = 0.06 em */
    val titleLetterSpacingSp: Float? = if (WEASELFIN) ApprovedHomeLayout.TITLE_TRACKING_TENTHS_SP / 10f else null,
    val countSizeSp: Float = if (WEASELFIN) ApprovedHomeLayout.COUNT_SIZE_SP.toFloat() else 12f,
    val countOpacity: Float = 1f,
    val countEndPaddingDp: Float = if (WEASELFIN) ApprovedHomeLayout.COUNT_END_PADDING_DP.toFloat() else 8f,
    val titleStartPaddingDp: Float? = null,
) {
    companion object {
        /** A Home row's saved title options (HomePage passed these to ItemRow) */
        fun forHomeRow(viewOptions: HomeRowViewOptions): RowTitleStyle =
            RowTitleStyle(
                dividerGapDp = if (AppTheme.isNeon) viewOptions.titleDividerGapDp.toFloat() else 4f,
                dividerThicknessDp = viewOptions.dividerThicknessDp.toFloat(),
                dividerGlowDp = viewOptions.dividerGlowDp.toFloat(),
                dividerGlowStrength = viewOptions.dividerGlowStrength / 100f,
                titleSizeSp = viewOptions.titleSizeSp.toFloat(),
                titleLetterSpacingSp = viewOptions.titleLetterSpacingTenthsSp / 10f,
                countSizeSp = viewOptions.countSizeSp.toFloat(),
                countOpacity = viewOptions.countOpacityPercent / 100f,
                countEndPaddingDp = viewOptions.countEndPaddingDp.toFloat(),
            )
    }
}

/**
 * Geometry of a [CardRowView] (`ItemRow` parameters). Defaults are ItemRow's.
 *
 * [contentInsetStartPx] / [contentInsetEndPx]: the page gutters the row spans (WeaselTV: section rules reach the side
 * menu and the screen edge, `LocalNeonRuleInsets`). Lay the row out across the gutters and pass them here: the title
 * and cards keep their place, the rule runs the full width, and with [stripBleedStart] (ItemRow on WeaselTV) the cards
 * scroll out under the start gutter. Use [CardDimens.NEON_RULE_INSET_START_DP] / `_END_DP` on WeaselTV pages, 0
 * elsewhere.
 */
data class CardRowStyle(
    /** Space between cards (`horizontalPadding`) */
    val spacingDp: Float = CardDimens.ROW_SPACING_DP.toFloat(),
    /** `cardContentPadding`; null = ItemRow's default (WeaselTV: 4 / 16 / 8 / 8dp; others: spacing / spacing / 8 / 8dp) */
    val cardPadding: CardPadding? = null,
    /** Gap between the title block and the cards (`dividerGap`) */
    val dividerGapDp: Float = if (WEASELFIN) ApprovedHomeLayout.DIVIDER_GAP_DP.toFloat() else 8f,
    val title: RowTitleStyle = RowTitleStyle(),
    /** WeaselTV shows the item count right of the title */
    val showCount: Boolean = true,
    val scrollMode: RowScrollMode = RowScrollMode.PIVOT,
    val contentInsetStartPx: Int = 0,
    val contentInsetEndPx: Int = 0,
    /** The cards' clipping edge moves into the start gutter (ItemRow on WeaselTV); other rows clip at the content edge */
    val stripBleedStart: Boolean = true,
) {
    /** The card padding in effect */
    fun resolvedPadding(): CardPadding =
        cardPadding ?: if (AppTheme.isNeon) {
            CardPadding(
                ApprovedHomeLayout.EDGE_PADDING_DP.toFloat(),
                ApprovedHomeLayout.END_PADDING_DP.toFloat(),
                (ApprovedHomeLayout.VERTICAL_PADDING_DP + ApprovedHomeLayout.EXTRA_VERTICAL_PADDING_DP).toFloat(),
                (ApprovedHomeLayout.VERTICAL_PADDING_DP + ApprovedHomeLayout.EXTRA_VERTICAL_PADDING_DP).toFloat(),
            )
        } else {
            CardPadding(spacingDp, spacingDp, 8f, 8f)
        }

    companion object {
        /** A Home row (HomePage's ItemRow call): the row's saved spacing, gaps, title and card padding */
        fun forHomeRow(
            viewOptions: HomeRowViewOptions,
            contentInsetStartPx: Int = 0,
            contentInsetEndPx: Int = 0,
        ): CardRowStyle {
            val neon = AppTheme.isNeon
            val vertical = (viewOptions.verticalPaddingDp + (viewOptions.extraVerticalPaddingDp ?: 0)).toFloat()
            return CardRowStyle(
                spacingDp = viewOptions.spacing.toFloat(),
                dividerGapDp = if (neon) viewOptions.dividerGapDp.toFloat() else 8f,
                title = RowTitleStyle.forHomeRow(viewOptions),
                cardPadding =
                    if (neon) {
                        CardPadding(
                            (viewOptions.edgePaddingDp ?: viewOptions.spacing).toFloat(),
                            (viewOptions.endPaddingDp ?: viewOptions.edgePaddingDp ?: viewOptions.spacing).toFloat(),
                            vertical,
                            vertical,
                        )
                    } else {
                        null
                    },
                contentInsetStartPx = contentInsetStartPx,
                contentInsetEndPx = contentInsetEndPx,
            )
        }

        /** `PersonRow` / `DiscoverPersonRow`: 8dp to the cards, 8dp padding all round, no count */
        fun personRow(
            scrollMode: RowScrollMode = RowScrollMode.forDetailPage(),
            contentInsetStartPx: Int = 0,
            contentInsetEndPx: Int = 0,
        ) = CardRowStyle(
            cardPadding = CardPadding.all(8f),
            dividerGapDp = 8f,
            showCount = false,
            scrollMode = scrollMode,
            contentInsetStartPx = contentInsetStartPx,
            contentInsetEndPx = contentInsetEndPx,
            stripBleedStart = false,
        )

        /** `ChapterRow`: title 16dp in (WeaselTV), 8dp to the cards, 16 / 8dp card padding, no count */
        fun chapterRow(
            scrollMode: RowScrollMode = RowScrollMode.forDetailPage(),
            contentInsetStartPx: Int = 0,
            contentInsetEndPx: Int = 0,
        ) = CardRowStyle(
            cardPadding = CardPadding.symmetric(16f, 8f),
            dividerGapDp = 8f,
            title = RowTitleStyle(titleStartPaddingDp = if (AppTheme.isNeon) 16f else 8f),
            showCount = false,
            scrollMode = scrollMode,
            contentInsetStartPx = contentInsetStartPx,
            contentInsetEndPx = contentInsetEndPx,
            stripBleedStart = false,
        )
    }
}

/**
 * `NeonSectionRule`: the WeaselTV section rule (a line in the accent with its glow fading up from it). Hidden on other
 * themes. Its height is max(thickness, glow); the line sits along the bottom edge.
 */
class SectionRuleView(
    context: Context,
) : View(context) {
    private val painter = NeonRulePainter()
    private var accent = NeonColors.VOLT
    private var thicknessPx = 0f
    private var glowPx = 0f
    private var strength = 0f

    fun bind(
        accent: Int,
        thicknessDp: Float,
        glowDp: Float,
        glowStrength: Float,
    ) {
        // The Home section green would be too dark as a rule; it takes its glow colour
        this.accent = if ((accent or 0xFF000000.toInt()) == NeonSections.Home.border) neonGlowAccent(accent) else accent
        thicknessPx = context.dpF(thicknessDp)
        glowPx = context.dpF(glowDp)
        strength = glowStrength
        visibility = if (AppTheme.isNeon) VISIBLE else GONE
        requestLayout()
        invalidate()
    }

    /** px from the view's top to the top of the rule line's own box (the glow reaches above it) */
    val glowAbovePx: Int get() = max(0f, glowPx - thicknessPx).roundToInt()

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), max(thicknessPx, glowPx).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, width.toFloat(), height.toFloat(), accent, thicknessPx, glowPx, strength)
    }
}

/**
 * `ItemRowTitle`. WeaselTV: an optional kicker, the uppercase Audiowide title in white, the count at the right and the
 * section rule under it in the row accent. Other themes: the titleLarge title only, 8dp in.
 *
 * The rule spans the view's full width; [bind]'s insets push the text in (lay the title across the page gutters and
 * pass them, see [CardRowStyle]).
 */
class RowTitleView(
    context: Context,
) : LinearLayout(context) {
    private val textRow = LinearLayout(context)
    private val textColumn = LinearLayout(context)
    val kickerView = singleLine(context)
    val titleView = singleLine(context)
    val countView = singleLine(context)
    val rule = SectionRuleView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        textRow.orientation = HORIZONTAL
        textRow.gravity = Gravity.BOTTOM
        textColumn.orientation = VERTICAL
        textColumn.addView(kickerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textColumn.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textRow.addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        textRow.addView(countView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(textRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param count the WeaselTV item count (shown when above 0)
     * @param titleStartPaddingDp overrides [RowTitleStyle.titleStartPaddingDp] (a row passes its cards' start padding)
     */
    fun bind(
        title: String,
        accent: Int,
        style: RowTitleStyle = RowTitleStyle(),
        count: Int? = null,
        kicker: String? = null,
        insetStartPx: Int = 0,
        insetEndPx: Int = 0,
        titleStartPaddingDp: Float? = null,
    ) {
        val palette = AppTheme.palette
        if (!AppTheme.isNeon) {
            kickerView.visibility = GONE
            countView.visibility = GONE
            rule.visibility = GONE
            textColumn.translationX = 0f
            textRow.setPaddingRelative(insetStartPx + context.dp(8), 0, insetEndPx, 0)
            // A plain Text: wraps, no ellipsis
            titleView.isSingleLine = false
            titleView.maxLines = Int.MAX_VALUE
            titleView.ellipsize = null
            titleView.applySpec(roleSpec(TextRole.TITLE_LARGE))
            titleView.setTextColor(palette.onBackground)
            titleView.text = title
            return
        }
        titleView.isSingleLine = true
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        val start = titleStartPaddingDp ?: style.titleStartPaddingDp ?: 8f
        textRow.setPaddingRelative(
            insetStartPx + context.dp(max(start, 0f)),
            0,
            insetEndPx + context.dp(style.countEndPaddingDp),
            0,
        )
        textColumn.translationX = context.dpF(min(start, 0f))

        val size = style.titleSizeSp
        kickerView.visibility = if (kicker.isNullOrBlank()) GONE else VISIBLE
        if (!kicker.isNullOrBlank()) {
            kickerView.applySpec(neonSpec(size * .5f, 600, 1.2f, .18f))
            kickerView.setTextColor(neonGlowAccent(accent))
            kickerView.text = kicker.uppercase()
        }
        val tracking = style.titleLetterSpacingSp?.let { if (size > 0) it / size else 0f } ?: .06f
        titleView.applySpec(TextSpec(size, 400, size * 1.2f, tracking, display = true))
        titleView.setTextColor(0xFFFFFFFF.toInt())
        titleView.text = title.uppercase()

        val showCount = count != null && count > 0
        countView.visibility = if (showCount) VISIBLE else GONE
        if (showCount) {
            countView.applySpec(neonSpec(style.countSizeSp, 600, 1.2f))
            countView.setPadding(0, countView.paddingTop, 0, countView.paddingBottom + context.dp(3))
            countView.setTextColor(withAlpha(0xFF787E8C.toInt(), style.countOpacity.coerceIn(0f, 1f)))
            countView.text = count.toString()
        }

        rule.bind(accent, style.dividerThicknessDp, style.dividerGlowDp, style.dividerGlowStrength)
        // The rule's box starts `dividerGap` under the text; its glow reaches above that box
        (rule.layoutParams as LayoutParams).topMargin = context.dp(style.dividerGapDp) - rule.glowAbovePx
        rule.requestLayout()
    }

    private companion object {
        fun singleLine(context: Context) =
            AppCompatTextView(context).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
    }
}

/**
 * The horizontal strip of cards (`FocusSafeLazyRow` on a Leanback `HorizontalGridView`): items aligned at the top,
 * focus enters at the last focused card, LEFT leaves the first card (so the side menu is reachable), RIGHT stops at the
 * last. On WeaselTV the top and bottom padding grow to fit the focused card's scale, border and glow
 * ([focusAppearance], the page's card appearance), and on detail pages ([RowScrollMode.KEEP_VISIBLE]) the end padding
 * too. Cards are clipped at the strip's start and end, not above or below.
 */
class CardStripView(
    context: Context,
) : HorizontalGridView(context) {
    /** The card appearance whose focus effects need room (`LocalHomeCardAppearance` at the row) */
    var focusAppearance: HomeCardAppearance = PageStyle().homeCardAppearance

    var scrollMode: RowScrollMode = RowScrollMode.PIVOT
        set(value) {
            field = value
            applyScrollMode()
        }

    private var baseStart = 0
    private var baseTop = 0
    private var baseEnd = 0
    private var baseBottom = 0
    private val clip = Rect()

    init {
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
        setHasFixedSize(false)
        // The row is as tall as its cards (a page list measures rows with an unspecified height)
        setRowHeight(LayoutParams.WRAP_CONTENT)
        (layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
        setSmoothScrollByBehavior(
            object : SmoothScrollByBehavior {
                override fun configSmoothScrollByDuration(
                    dx: Int,
                    dy: Int,
                ): Int = if (scrollMode == RowScrollMode.PIVOT) PIVOT_SCROLL_MS else UNDEFINED_DURATION

                override fun configSmoothScrollByInterpolator(
                    dx: Int,
                    dy: Int,
                ): Interpolator? = if (scrollMode == RowScrollMode.PIVOT) PIVOT_EASING else null
            },
        )
        applyScrollMode()
    }

    /**
     * `requestFocus()` on the row lands on its selected card (the old rows' focus restorer, and a details page opening on
     * the given episode). Leanback's keep-visible strategy otherwise focuses the first fully visible card and ignores
     * the selected position; the pivot strategy already uses it.
     */
    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?,
    ): Boolean {
        if (focusScrollStrategy != BaseGridView.FOCUS_SCROLL_ALIGNED && requestSelectedFocus(direction, previouslyFocusedRect)) {
            return true
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    /**
     * UP/DOWN into the row from outside lands on its selected card, as the Compose rows' focus restorer did (eg UP from a
     * series page's Play button returns to the episode it shows, not the card that happens to sit above the button)
     */
    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) {
            val selected = layoutManager?.findViewByPosition(selectedPosition)
            if (selected != null && selected.isShown && selected.hasFocusable()) {
                selected.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    private fun applyScrollMode() {
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        itemAlignmentOffset = 0
        itemAlignmentOffsetPercent = 0f
        isItemAlignmentOffsetWithPadding = false
        when (scrollMode) {
            RowScrollMode.PIVOT -> {
                // Compose TV pivot: the focused card's leading edge at 30 % of the row
                focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
                windowAlignmentOffset = 0
                windowAlignmentOffsetPercent = 30f
            }

            RowScrollMode.KEEP_VISIBLE -> {
                focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
            }
        }
    }

    /** Padding before focus clearance, in px */
    fun setBasePadding(
        start: Int,
        top: Int,
        end: Int,
        bottom: Int,
    ) {
        baseStart = start
        baseTop = top
        baseEnd = end
        baseBottom = bottom
        setPaddingRelative(start, top, end, bottom)
        requestLayout()
    }

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        super.onMeasure(widthSpec, heightSpec)
        if (!AppTheme.isNeon) return
        val content = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val a = focusAppearance
        val clearance =
            max(
                content * ((a.focusScalePercent - 100).coerceAtLeast(0) / 200f) + context.dpF((a.glowSpreadDp + a.borderWidthDp).toFloat()),
                context.dpF(8f),
            ).roundToInt()
        val top = max(baseTop, clearance)
        val bottom = max(baseBottom, clearance)
        val end = if (scrollMode == RowScrollMode.KEEP_VISIBLE) max(baseEnd, clearance) else baseEnd
        if (top != paddingTop || bottom != paddingBottom || end != paddingEnd) {
            setPaddingRelative(baseStart, top, end, bottom)
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        // Clip the scrolling axis only (Compose clipScrollableContainer allows 30dp beyond the cross axis)
        val extra = context.dp(30)
        clip.set(0, -extra, r - l, b - t + extra)
        clipBounds = clip
    }

    private companion object {
        const val PIVOT_SCROLL_MS = 125
        val PIVOT_EASING = PathInterpolator(.25f, .1f, .25f, 1f)
        const val UNDEFINED_DURATION = Int.MIN_VALUE
    }
}

/**
 * `ItemRow`: a titled row of cards ([RowTitleView] over a [CardStripView]), [CardRowStyle.dividerGapDp] apart.
 *
 * ```
 * row.setup(title, accent, pageStyle, CardRowStyle.forHomeRow(viewOptions, insetStart, insetEnd))
 * row.setCards(adapter, items, viewMore)
 * ```
 */
class CardRowView(
    context: Context,
) : LinearLayout(context) {
    val titleView = RowTitleView(context)
    val strip = CardStripView(context)

    private var title = ""
    private var accent = NeonColors.VOLT
    private var kicker: String? = null
    private var style = CardRowStyle()
    private var count = 0

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param titleAccent `titleAccent` (default the page accent): colours the rule and kicker
     * @param pageStyle its [PageStyle.homeCardAppearance] sizes the focus clearance
     */
    fun setup(
        title: String,
        titleAccent: Int,
        pageStyle: PageStyle,
        style: CardRowStyle = CardRowStyle(),
        kicker: String? = null,
    ) {
        this.title = title
        this.accent = titleAccent
        this.kicker = kicker
        this.style = style
        val padding = style.resolvedPadding()
        val neon = AppTheme.isNeon
        val insetStart = style.contentInsetStartPx
        val insetEnd = style.contentInsetEndPx
        strip.focusAppearance = pageStyle.homeCardAppearance
        strip.scrollMode = style.scrollMode
        strip.setHorizontalSpacing(context.dp(style.spacingDp))
        val bleed = style.stripBleedStart && neon
        strip.setBasePadding(
            (if (bleed) insetStart else 0) + context.dp(padding.startDp),
            context.dp(padding.topDp),
            context.dp(padding.endDp),
            context.dp(padding.bottomDp),
        )
        (strip.layoutParams as LayoutParams).apply {
            marginStart = if (bleed) 0 else insetStart
            marginEnd = insetEnd
            topMargin = context.dp(style.dividerGapDp)
        }
        strip.requestLayout()
        bindTitle()
    }

    private fun bindTitle() {
        val padding = style.resolvedPadding()
        titleView.bind(
            title = title,
            accent = accent,
            style = style.title,
            count = count.takeIf { AppTheme.isNeon && style.showCount },
            kicker = kicker,
            insetStartPx = style.contentInsetStartPx,
            insetEndPx = style.contentInsetEndPx,
            titleStartPaddingDp = style.title.titleStartPaddingDp ?: if (AppTheme.isNeon) padding.startDp else 8f,
        )
    }

    /** Show [items] with [adapter] (keep one adapter per row); updates the count */
    fun <T : Any> setCards(
        adapter: CardRowAdapter<T>,
        items: List<T?>,
        viewMore: ViewMoreSpec? = null,
    ) {
        if (strip.adapter !== adapter) strip.adapter = adapter
        adapter.submit(items, viewMore)
        if (count != items.size) {
            count = items.size
            bindTitle()
        }
    }

    /** `focusIndex`: select card [index] (scrolling to it) and focus it when the row has focus */
    fun focusCard(index: Int) {
        strip.setSelectedPositionSmooth(index)
    }

    /** The index of the selected (last focused) card */
    val selectedIndex: Int get() = strip.selectedPosition

    // Presets ------------------------------------------------------------------------------------------------------

    /** `PersonRow`: people cards (108dp) under [title] (default "People") */
    fun showPeople(
        people: List<Person>,
        pageStyle: PageStyle,
        onClick: (Person) -> Unit,
        onLongClick: ((Int, Person) -> Unit)? = null,
        title: String = context.getString(R.string.people_title),
        titleAccent: Int = pageStyle.sectionAccent,
        style: CardRowStyle = CardRowStyle.personRow(),
        accent: Int = pageStyle.sectionAccent,
    ) {
        setup(title, titleAccent, pageStyle, style)
        @Suppress("UNCHECKED_CAST")
        val adapter =
            (strip.adapter as? CardRowAdapter<Person>)?.takeIf { it.presenter is PersonPresenter }
                ?: CardRowAdapter(PersonPresenter())
        (adapter.presenter as PersonPresenter).set(pageStyle, accent)
        adapter.callbacks = CardCallbacks(onClick = { _, p -> onClick(p) }, onLongClick = onLongClick)
        setCards(adapter, people)
    }

    /** `DiscoverPersonRow`: Requests people cards (name, role, poster) under [title] */
    fun showDiscoverPeople(
        people: List<DiscoverItem>,
        pageStyle: PageStyle,
        onClick: (DiscoverItem) -> Unit,
        onLongClick: ((Int, DiscoverItem) -> Unit)? = null,
        title: String = context.getString(R.string.people_title),
        titleAccent: Int = pageStyle.sectionAccent,
        style: CardRowStyle = CardRowStyle.personRow(),
        accent: Int = pageStyle.sectionAccent,
    ) {
        setup(title, titleAccent, pageStyle, style)
        val presenter =
            cardPresenter<DiscoverItem, PersonCardView>(::PersonCardView) { card, item, _, _ ->
                card.bind(item?.title, item?.subtitle, item?.posterUrl, false, pageStyle, accent = accent)
            }
        val adapter = CardRowAdapter(presenter, CardCallbacks(onClick = { _, p -> onClick(p) }, onLongClick = onLongClick))
        setCards(adapter, people)
    }

    /** `ChapterRow`: chapter cards (120dp) under "Chapters" */
    fun showChapters(
        chapters: List<Chapter>,
        pageStyle: PageStyle,
        onClick: (Chapter) -> Unit,
        onLongClick: ((Chapter) -> Unit)? = null,
        titleAccent: Int = pageStyle.sectionAccent,
        style: CardRowStyle = CardRowStyle.chapterRow(),
        accent: Int = pageStyle.sectionAccent,
    ) {
        setup(context.getString(R.string.chapters), titleAccent, pageStyle, style)
        val presenter =
            cardPresenter<Chapter, ChapterCardView>(::ChapterCardView) { card, item, _, _ ->
                if (item != null) card.bind(item, pageStyle, accent = accent)
            }
        val adapter =
            CardRowAdapter(
                presenter,
                CardCallbacks(onClick = { _, c -> onClick(c) }, onLongClick = onLongClick?.let { l -> { _, c -> l(c) } }),
            )
        setCards(adapter, chapters)
    }

    /** `ExtrasRow`: extras as episode-height season cards with the overlay, under "Extras" (ItemRow defaults) */
    fun showExtras(
        extras: List<ExtrasItem>,
        pageStyle: PageStyle,
        onClickItem: (Int, ExtrasItem) -> Unit,
        onLongClickItem: (Int, ExtrasItem) -> Unit,
        titleAccent: Int = pageStyle.sectionAccent,
        style: CardRowStyle = CardRowStyle(scrollMode = RowScrollMode.forDetailPage()),
        accent: Int = pageStyle.sectionAccent,
    ) {
        setup(context.getString(R.string.extras_title), titleAccent, pageStyle, style)
        val presenter =
            cardPresenter<ExtrasItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
                card.bind(
                    title = item?.title,
                    subtitle = item?.subtitle,
                    name = null,
                    imageUrl = item?.imageUrl,
                    isFavorite = false,
                    isPlayed = item?.isPlayed == true,
                    unplayedItemCount = -1,
                    playedPercentage = item?.playedPercentage ?: 0.0,
                    numberOfVersions = -1,
                    style = pageStyle,
                    imageHeightDp = CardDimens.HEIGHT_EPISODE_DP.toFloat(),
                    accent = accent,
                    showImageOverlay = true,
                    aspectRatio = item?.aspectRatio ?: AspectRatios.WIDE,
                )
            }
        val adapter = CardRowAdapter(presenter, CardCallbacks(onClick = onClickItem, onLongClick = onLongClickItem))
        setCards(adapter, extras)
    }

    /** Binds [PersonCardView]s for [showPeople] */
    private class PersonPresenter : CardPresenter<Person>() {
        private var style = PageStyle()
        private var accent = 0

        fun set(
            style: PageStyle,
            accent: Int,
        ) {
            this.style = style
            this.accent = accent
        }

        override fun create(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): BaseCardView = PersonCardView(parent.context)

        override fun bind(
            card: BaseCardView,
            item: Person?,
            position: Int,
            cellWidthPx: Int,
        ) {
            if (item != null) (card as PersonCardView).bind(item, style, accent = accent)
        }
    }
}

/**
 * `FocusableItemRow` (Home rows still loading, failed or empty: focusable so the list can scroll to them) and
 * `LoadingRowPlaceholder` (not focusable, 32dp below): the row title with its rule and a message under it.
 *
 * Focused (focusable variant): the content area fills with the accent at 14 % (WeaselTV) or the theme's border colour
 * at 25 % on 8dp corners. Insets work as in [CardRowStyle]: the rule spans the gutters, the fill does not.
 */
class PlaceholderRowView(
    context: Context,
) : FrameLayout(context) {
    private val fill = FillView(context)
    private val column = LinearLayout(context)
    val titleView = RowTitleView(context)
    val messageView = AppCompatTextView(context)
    private var accent = NeonColors.VOLT
    private var animator: ValueAnimator? = null
    private var focusableRow = true

    init {
        clipChildren = false
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(
            messageView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            },
        )
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param accent the page accent (`LocalNeonAccent`); errors use red
     * @param focusableRow true: `FocusableItemRow`; false: `LoadingRowPlaceholder`
     */
    fun bind(
        title: String,
        message: String,
        accent: Int,
        isError: Boolean = false,
        focusableRow: Boolean = true,
        insetStartPx: Int = 0,
        insetEndPx: Int = 0,
    ) {
        this.focusableRow = focusableRow
        this.accent = accent
        val neon = AppTheme.isNeon
        val palette = AppTheme.palette
        isFocusable = focusableRow
        titleView.bind(
            title,
            if (isError) NeonColors.RED else accent,
            insetStartPx = if (focusableRow && !neon) 0 else insetStartPx,
            insetEndPx = insetEndPx,
        )
        messageView.applySpec(CardTextStyles.rowSubtitle())
        messageView.setTextColor(
            when {
                isError -> palette.error
                neon -> NeonColors.MID
                else -> palette.onBackground
            },
        )
        messageView.text = message
        val messageStart = if (focusableRow) context.dp(8) else 0
        (messageView.layoutParams as LinearLayout.LayoutParams).marginStart =
            insetStartPx.takeIf { neon || !focusableRow } ?: 0
        messageView.setPaddingRelative(messageStart, messageView.paddingTop, 0, messageView.paddingBottom)

        val lp = fill.layoutParams as LayoutParams
        val clp = column.layoutParams as LayoutParams
        if (focusableRow) {
            if (neon) {
                // fillMaxWidth (the content width, not the gutters), 8dp above and below
                lp.setMargins(insetStartPx, 0, insetEndPx, 0)
                column.setPadding(0, context.dp(8), 0, context.dp(8))
                clp.setMargins(0, 0, 0, 0)
                fill.cornerPx = 0f
            } else {
                // padding(start = 8), then 8dp all round inside the rounded fill
                lp.setMargins(insetStartPx + context.dp(8), 0, insetEndPx, 0)
                column.setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                clp.setMargins(insetStartPx + context.dp(8), 0, insetEndPx, 0)
                fill.cornerPx = context.dpF(8f)
            }
            setPadding(0, 0, 0, 0)
        } else {
            lp.setMargins(0, 0, 0, 0)
            column.setPadding(0, 0, 0, 0)
            clp.setMargins(0, 0, 0, 0)
            setPadding(0, 0, 0, context.dp(32))
        }
        fill.visibility = if (focusableRow) VISIBLE else GONE
        fill.color = if (isFocused) focusedFill() else 0
        requestLayout()
    }

    private fun focusedFill(): Int = if (AppTheme.isNeon) NeonColors.chipOn(accent) else withAlpha(AppTheme.palette.border, .25f)

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!focusableRow) return
        animator?.cancel()
        animator =
            ValueAnimator.ofObject(ArgbEvaluator(), fill.color, if (gainFocus) focusedFill() else 0).apply {
                duration = 150L
                addUpdateListener { fill.color = it.animatedValue as Int }
                start()
            }
    }

    private class FillView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var cornerPx = 0f
        var color = 0
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            if (color ushr 24 == 0) return
            paint.color = color
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
        }
    }
}

/**
 * `LoadingRow`: a row title over its loading state: "Loading…", the error, "No results" (when [showIfEmpty]) or the
 * cards (default: 2:3 season cards at `Cards.height2x3`). Not focusable until it has cards.
 */
class LoadingRowView(
    context: Context,
) : FrameLayout(context) {
    val placeholder = PlaceholderRowView(context)
    val row = CardRowView(context)
    private var adapter: CardRowAdapter<BaseItem>? = null

    init {
        clipChildren = false
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param presenter the cards; default season cards [CardDimens.HEIGHT_2X3_DP] tall
     * @param selectedIndex the card to select first (the old `position` focus restore)
     */
    fun bind(
        title: String,
        state: RowLoadingState,
        pageStyle: PageStyle,
        onClickItem: (Int, BaseItem) -> Unit,
        showIfEmpty: Boolean = true,
        style: CardRowStyle = CardRowStyle(),
        presenter: CardPresenter<BaseItem>? = null,
        selectedIndex: Int? = null,
        insetStartPx: Int = style.contentInsetStartPx,
        insetEndPx: Int = style.contentInsetEndPx,
    ) {
        val accent = pageStyle.sectionAccent

        fun showPlaceholder(
            message: String,
            error: Boolean,
        ) {
            row.visibility = GONE
            placeholder.visibility = VISIBLE
            placeholder.bind(
                title,
                message,
                accent,
                isError = error,
                focusableRow = false,
                insetStartPx = insetStartPx,
                insetEndPx = insetEndPx,
            )
        }
        when (state) {
            is RowLoadingState.Error -> {
                showPlaceholder(state.localizedMessage, true)
            }

            RowLoadingState.Pending, RowLoadingState.Loading -> {
                showPlaceholder(context.getString(R.string.loading), false)
            }

            is RowLoadingState.Success -> {
                if (state.items.isNotEmpty()) {
                    placeholder.visibility = GONE
                    row.visibility = VISIBLE
                    row.setup(title, accent, pageStyle, style)
                    val a =
                        adapter ?: CardRowAdapter(
                            presenter ?: cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ ->
                                card.bind(item, pageStyle, imageHeightDp = CardDimens.HEIGHT_2X3_DP.toFloat())
                            },
                        ).also { adapter = it }
                    a.callbacks = CardCallbacks(onClick = onClickItem, onLongClick = { _, _ -> })
                    row.setCards(a, state.items)
                    selectedIndex?.let { row.strip.selectedPosition = it }
                } else if (showIfEmpty) {
                    showPlaceholder(context.getString(R.string.no_results), false)
                } else {
                    row.visibility = GONE
                    placeholder.visibility = GONE
                }
            }
        }
    }
}

/**
 * `HiddenFocusBox`: an invisible full-width view that can take focus, calling [onFocus] when it does (used to bring
 * hidden content back, eg the player's chapter and queue rows). It is 1px tall and draws nothing: on API 28+ a View of
 * no size cannot take focus, where the Compose box of 0dp could.
 */
class HiddenFocusView(
    context: Context,
    private val onFocus: () -> Unit,
) : View(context) {
    init {
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onFocus()
    }
}
