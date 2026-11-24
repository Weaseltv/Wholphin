package com.github.damontecres.wholphin.leanback.library

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.ControlColors
import com.github.damontecres.wholphin.leanback.theme.ControlScales
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonGlowSpec
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.text.FittedTitleView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView
import com.github.damontecres.wholphin.ui.components.TypeFilterOption
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import org.jellyfin.sdk.model.api.SortOrder
import kotlin.math.roundToInt

/*
 * Small shared pieces of the library pages (no-Compose UI, 2026-11-22): the expand / shrink frame used for headers and
 * tabs, the page heading (`GridTitle`), the header buttons (`ExpandableFaButton`, `ExpandableFilterButton`, the sort
 * `TextButton`), the ALL / MOVIES / SHOWS and quick-list buttons (`TypeFilterButton`, `QuickListButton`) and the
 * background loading spinner.
 */

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** `MinButtonSize`: the old icon buttons' 40dp */
internal const val MIN_BUTTON_DP = 40f

/** The old compact buttons (streaming collection rows): 36dp, 6dp side padding, 15sp labels */
internal const val COMPACT_BUTTON_DP = 36f
internal const val COMPACT_PADDING_DP = 6f
internal const val COMPACT_FONT_SP = 15f

/** `DialogListEdge`: room above and below a list's first and last rows for their focus glow */
internal const val DIALOG_LIST_EDGE_DP = 20f

/**
 * Shows or hides its single child by animating its height, like Compose `AnimatedVisibility(enter = expandVertically(),
 * exit = shrinkVertically())`: the child stays at its natural height anchored to the bottom while the frame's height
 * runs between 0 and it (clipped while animating). When fully hidden the frame is `GONE`, so focus search skips it.
 */
class CollapsibleFrame(
    context: Context,
) : FrameLayout(context) {
    private var fraction = 1f
    private var animator: ValueAnimator? = null
    private val clip = Rect()

    /** The target state (true = shown) */
    var expanded: Boolean = true
        private set

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Show ([value] true) or hide the content; animated unless [animate] is false or the frame is not laid out */
    fun setExpanded(
        value: Boolean,
        animate: Boolean = true,
    ) {
        if (value == expanded && animator == null) {
            // Already there; make sure the visibility matches (eg after a non-animated change)
            applyFraction(if (value) 1f else 0f)
            return
        }
        expanded = value
        animator?.cancel()
        animator = null
        val target = if (value) 1f else 0f
        if (!animate || !isLaidOut || !isShown) {
            applyFraction(target)
            return
        }
        visibility = VISIBLE
        animator =
            ValueAnimator.ofFloat(fraction, target).apply {
                duration = ANIM_MS
                interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
                addUpdateListener { applyFraction(it.animatedValue as Float, animating = true) }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (animator === animation) {
                                animator = null
                                applyFraction(target)
                            }
                        }
                    },
                )
                start()
            }
    }

    private fun applyFraction(
        value: Float,
        animating: Boolean = false,
    ) {
        fraction = value
        visibility = if (value <= 0f && !animating) GONE else VISIBLE
        // Clip only while animating, so focus glows of the content are not cut at rest
        clipBounds = null
        clipChildren = animating
        requestLayout()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val child = if (childCount > 0) getChildAt(0) else null
        if (child == null) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        val lp = child.layoutParams as LayoutParams
        child.measure(
            getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val natural = child.measuredHeight + lp.topMargin + lp.bottomMargin + paddingTop + paddingBottom
        val width = resolveSize(child.measuredWidth + paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, widthMeasureSpec)
        setMeasuredDimension(width, (natural * fraction).roundToInt())
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val child = if (childCount > 0) getChildAt(0) else return
        val lp = child.layoutParams as LayoutParams
        val h = bottom - top
        // Anchored at the bottom (expandFrom / shrinkTowards = Bottom)
        val childBottom = h - paddingBottom - lp.bottomMargin
        val childTop = childBottom - child.measuredHeight
        val childLeft = paddingLeft + lp.leftMargin
        child.layout(childLeft, childTop, childLeft + child.measuredWidth, childBottom)
        if (clipChildren) {
            clip.set(-width, 0, width * 2, h)
            clipBounds = clip
        }
    }

    companion object {
        /** About as long as the Compose expand / shrink spring takes */
        const val ANIM_MS = 300L
    }
}

/**
 * The page heading of library grids (`GridTitle`). WeaselTV: the eyebrow (LIBRARY by default), the uppercase
 * Condensed 40 title fitted to three lines (clear of the clock), and the section rule 6dp below, reaching into the page
 * gutters. With [collectionsOptions] (the Collections page) the heading sits 27dp lower, flush with the content edge, and
 * the rule takes those options' thickness and glow. Other themes: one centred displaySmall line.
 */
class LibraryGridTitleView(
    context: Context,
) : LinearLayout(context) {
    private val eyebrowView = AppCompatTextView(context)
    private val titleView = FittedTitleView(context, maxLines = 3)
    private val plainTitle = AppCompatTextView(context)
    private val rule = NeonRuleView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(eyebrowView, LayoutParams(MATCH, WRAP))
        addView(titleView, LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(2) })
        addView(
            rule,
            LayoutParams(MATCH, WRAP).apply {
                topMargin = context.dp(6)
                // InsetNeonSectionRules: the rule reaches the side menu and the screen edge
                marginStart = -context.dp(CardDimens.NEON_RULE_INSET_START_DP)
                marginEnd = -context.dp(CardDimens.NEON_RULE_INSET_END_DP)
            },
        )
        addView(plainTitle, LayoutParams(MATCH, WRAP))
    }

    /** @param accent the eyebrow and rule colour (the page accent, `LocalNeonAccent`) */
    fun bind(
        title: String,
        accent: Int,
        eyebrow: String? = null,
        collectionsOptions: HomeRowViewOptions? = null,
    ) {
        val palette = AppTheme.palette
        if (!AppTheme.isNeon) {
            eyebrowView.visibility = GONE
            titleView.visibility = GONE
            rule.visibility = GONE
            plainTitle.visibility = VISIBLE
            plainTitle.applyTextRole(TextRole.DISPLAY_SMALL)
            plainTitle.setTextColor(palette.onBackground)
            plainTitle.gravity = Gravity.CENTER_HORIZONTAL
            plainTitle.maxLines = 1
            plainTitle.ellipsize = TextUtils.TruncateAt.END
            plainTitle.text = title
            setPadding(0, 0, 0, 0)
            return
        }
        plainTitle.visibility = GONE
        eyebrowView.visibility = VISIBLE
        titleView.visibility = VISIBLE
        rule.visibility = VISIBLE
        val inset = if (collectionsOptions != null) 0 else context.dp(16)
        setPadding(0, if (collectionsOptions != null) context.dp(27) else 0, 0, 0)
        eyebrowView.applyNeonEyebrow(eyebrow ?: context.getString(R.string.library), accent)
        eyebrowView.setPaddingRelative(inset, 0, inset, 0)
        titleView.setTextColor(palette.onSurface)
        titleView.setStyledText(title.uppercase(), NeonType.pageTitle())
        titleView.setPaddingRelative(inset, 0, TimeDisplayView.headerEndPaddingPx(context), 0)
        rule.ruleAccent = accent
        rule.section = true
        rule.thicknessDp = collectionsOptions?.dividerThicknessDp?.toFloat() ?: NeonSizes.RULE
        rule.glowHeightDp = collectionsOptions?.dividerGlowDp?.toFloat() ?: NeonGlowSpec.RULE
        rule.glowStrength = collectionsOptions?.dividerGlowStrength?.div(100f) ?: .35f
        rule.invalidate()
    }
}

/** Font Awesome glyphs inside a label (the old `SpanStyle(fontFamily = FontAwesome)`) */
internal class FontAwesomeSpan(
    private val typeface: Typeface,
) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }
}

/** `SortAndDirection.toAnnotatedString`: the direction caret in Font Awesome, a space and the sort name */
internal fun SortAndDirection.label(
    context: Context,
    allCaps: Boolean,
): CharSequence {
    val caret = context.getString(if (direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down)
    val name = context.getString(getStringRes(sort)).let { if (allCaps) it.uppercase() else it }
    return SpannableStringBuilder().apply {
        append(caret)
        setSpan(FontAwesomeSpan(AppFonts.icons(context)), 0, caret.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" ")
        append(name)
    }
}

/**
 * `ExpandableFaButton`: a 40dp button with a Font Awesome glyph (16sp in a 32dp box). With [allCaps] (WeaselTV) the
 * uppercase label always shows (4dp side padding, label 2dp / 8dp); otherwise the titleSmall label appears only while
 * focused. [collapsed]: only the glyph until focused, then the uppercase label.
 */
fun expandableFaButton(
    context: Context,
    @StringRes title: Int,
    @StringRes glyph: Int,
    allCaps: Boolean = AppTheme.isNeon,
    collapsed: Boolean = false,
    titleOverride: String? = null,
): AppButton =
    AppButton(context).apply {
        contentHeightDp = MIN_BUTTON_DP
        exactContentHeight = true
        minimumWidth = context.dp(MIN_BUTTON_DP)
        val label = titleOverride ?: context.getString(title)
        when {
            collapsed -> {
                // Centres the 32dp glyph in the 40dp button while collapsed
                setContentPaddingDp(4f, 0f, 4f, 0f)
                setLabel(label, allCaps = true)
                setLabelPaddingDp(2f, 6f)
                labelOnlyWhenFocused = true
            }

            allCaps -> {
                setContentPaddingDp(4f, 0f, 4f, 0f)
                setLabel(label, allCaps = true)
                setLabelPaddingDp(2f, 8f)
            }

            else -> {
                setContentPaddingDp(4f, 0f, 4f, 0f)
                setLabel(label)
                labelView.applyTextRole(TextRole.TITLE_SMALL)
                setLabelPaddingDp(0f, 4f)
                // AnimatedVisibility(isWeaselTv() || isFocused)
                labelOnlyWhenFocused = !AppTheme.isNeon
            }
        }
        setGlyph(glyph, sizeSp = 16f, boxDp = 32f)
        contentDescription = label
    }

/**
 * `ExpandableFilterButton`: the filter glyph in a 40dp square (28dp wide while focused or filtering), the number of
 * active filters beside it, and FILTER while focused.
 */
class FilterButtonView(
    context: Context,
    private val allCaps: Boolean = AppTheme.isNeon,
    private val compact: Boolean = false,
) : AppButton(context) {
    private val countView = AppCompatTextView(context)
    private val size = if (compact) COMPACT_BUTTON_DP else MIN_BUTTON_DP

    /** Number of filters in effect */
    var filterCount: Int = 0
        set(value) {
            field = value
            countView.text = value.toString()
            countView.visibility = if (value > 0) VISIBLE else GONE
            updateGlyphBox()
        }

    init {
        contentHeightDp = size
        exactContentHeight = true
        minimumWidth = context.dp(size)
        setContentPaddingDp(0f, 0f, 0f, 0f)
        setGlyph(R.string.fa_filter, sizeSp = 16f, boxDp = size)
        countView.applyTextRole(TextRole.TITLE_SMALL)
        countView.setPaddingRelative(0, 0, context.dp(4), 0)
        countView.visibility = GONE
        addView(countView, 2, LayoutParams(WRAP, WRAP))
        onContentColor = { countView.setTextColor(it) }
        val label = context.getString(R.string.filter)
        if (allCaps) {
            setLabel(label, allCaps = true)
            setLabelPaddingDp(2f, 8f)
        } else {
            setLabel(label)
            labelView.applyTextRole(TextRole.TITLE_SMALL)
            setLabelPaddingDp(4f, 4f)
        }
        if (compact) labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_FONT_SP)
        labelOnlyWhenFocused = true
        contentDescription = label
        setOnFocusChangeListener { _, _ -> updateGlyphBox() }
    }

    private fun updateGlyphBox() {
        val widthDp = if (filterCount > 0 || isFocused) size - 12f else size
        val lp = glyphView.layoutParams as LayoutParams
        val px = context.dp(widthDp)
        if (lp.width != px) {
            lp.width = px
            lp.height = context.dp(size)
            glyphView.layoutParams = lp
        }
    }
}

/**
 * `ExpandableSortButton`: the sort direction glyph in a 40dp square, the uppercase sort name while focused. Long press
 * reverses the sort (handled by the caller).
 */
class SortGlyphButtonView(
    context: Context,
    private val compact: Boolean = false,
) : AppButton(context) {
    private val size = if (compact) COMPACT_BUTTON_DP else MIN_BUTTON_DP

    init {
        contentHeightDp = size
        exactContentHeight = true
        minimumWidth = context.dp(size)
        setContentPaddingDp(0f, 0f, 0f, 0f)
        labelOnlyWhenFocused = true
        setLabelPaddingDp(2f, if (compact) 6f else 8f)
        if (compact) labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_FONT_SP)
        setOnFocusChangeListener { _, focused ->
            val lp = glyphView.layoutParams as LayoutParams
            lp.width = context.dp(if (focused) size - 12f else size)
            glyphView.layoutParams = lp
        }
    }

    fun bind(current: SortAndDirection) {
        setGlyph(
            if (current.direction == SortOrder.ASCENDING) R.string.fa_arrow_up_wide_short else R.string.fa_arrow_down_wide_short,
            sizeSp = 16f,
            boxDp = size,
        )
        setLabel(context.getString(getStringRes(current.sort)), allCaps = true)
    }
}

/**
 * `TypeFilterButton`: an outlined ALL / MOVIES / SHOWS filter in its type colour (1dp outline at rest, the accent border,
 * fill and glow when focused), the uppercase label in the accent.
 */
fun typeFilterButton(
    context: Context,
    option: TypeFilterOption,
    compact: Boolean = false,
    onClick: () -> Unit,
): AppButton =
    AppButton(context).apply {
        val accent = typeAccent(option.accentKind)
        setSurfaceStyle { scope ->
            val base = ControlStyles.button(scope.copy(accent = accent))
            if (!AppTheme.isNeon) {
                base
            } else {
                base.copy(
                    border = ControlStyles.surfaceBorder(accent, restWidthDp = 1f, restColor = accent),
                    glow = ControlStyles.surfaceGlow(accent),
                )
            }
        }
        this.accent = accent
        setContentPaddingDp(if (compact) COMPACT_PADDING_DP else 12f, 4f, if (compact) COMPACT_PADDING_DP else 12f, 4f)
        contentHeightDp = if (compact) COMPACT_BUTTON_DP else MIN_BUTTON_DP
        setLabel(context.getString(option.title), allCaps = true)
        if (compact) labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, COMPACT_FONT_SP)
        // The label stays in the type colour (Text(color = accent))
        onContentColor = { labelView.setTextColor(accent) }
        setOnClickListener { onClick() }
    }

/**
 * `QuickListButton`: a quick-list style button in the page accent: filled with `chipOn` while its list shows
 * ([isChipSelected]) and when focused, a grey outline at rest ([restOutline], or `line2`), 2dp accent outline when
 * selected, white label turning the accent when focused, and an optional small spinner while loading.
 */
class QuickListButtonView(
    context: Context,
    heightDp: Float,
    horizontalPaddingDp: Float,
    fontSizeSp: Float?,
    private val restOutline: Int? = null,
) : AppButton(context) {
    private val spinner =
        CircularProgressView(context).apply {
            visibility = GONE
        }

    /** The list is showing */
    var selectedList: Boolean = false
        set(value) {
            field = value
            surface.rebuild()
        }

    var loading: Boolean = false
        set(value) {
            field = value
            spinner.visibility = if (value) VISIBLE else GONE
        }

    init {
        contentHeightDp = heightDp
        setContentPaddingDp(horizontalPaddingDp, 0f, horizontalPaddingDp, 0f)
        addView(spinner, 0, LayoutParams(context.dp(14), context.dp(14)).apply { marginEnd = context.dp(6) })
        fontSizeSp?.let { labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        setSurfaceStyle { scope ->
            val accent = scope.accentOrDefault
            if (!AppTheme.isNeon) {
                ControlStyles.button(scope)
            } else {
                val chipOn = NeonColors.chipOn(accent)
                ControlSurfaceStyle(
                    container = ControlColors(rest = if (selectedList) chipOn else 0, focused = chipOn, pressed = chipOn),
                    content = ControlColors(rest = NeonColors.TEXT, focused = accent, pressed = accent),
                    border =
                        ControlStyles.surfaceBorder(
                            accent,
                            restWidthDp = if (selectedList) 2f else 1f,
                            restColor = if (selectedList) accent else restOutline ?: NeonColors.LINE2,
                        ),
                    glow = ControlStyles.surfaceGlow(accent),
                    scale = ControlScales.BUTTON,
                )
            }
        }
    }
}

/** A thin rule between dropdown sections (Material3 `HorizontalDivider`: 1dp in outlineVariant) */
internal fun dropdownDivider(context: Context): View =
    View(context).apply {
        setBackgroundColor(withAlpha(AppTheme.palette.onSurfaceVariant, .2f))
        layoutParams = LinearLayout.LayoutParams(MATCH, context.dp(1))
    }

/**
 * The spinner shown over a library grid while it refreshes in the background: 64dp, a quarter-transparent background
 * circle, 4dp padding.
 */
internal class BackgroundSpinnerView(
    context: Context,
) : FrameLayout(context) {
    init {
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(AppTheme.palette.background, .25f))
            }
        val pad = context.dp(4)
        setPadding(pad, pad, pad, pad)
        addView(CircularProgressView(context), LayoutParams(MATCH, MATCH))
        visibility = GONE
    }

    fun setShown(shown: Boolean) {
        if (shown == (visibility == VISIBLE)) return
        visibility = VISIBLE
        animate().cancel()
        if (shown) {
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        } else {
            animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { visibility = GONE }
                .start()
        }
    }
}

/** A plain text line in a text role and colour */
internal fun textLine(
    context: Context,
    role: TextRole,
    color: Int,
    maxLines: Int = 1,
): TextView =
    AppCompatTextView(context).apply {
        applyTextRole(role)
        setTextColor(color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

/** A text line in a [ControlTextStyle] */
internal fun styledLine(
    context: Context,
    style: ControlTextStyle,
    color: Int,
    maxLines: Int = 1,
): TextView =
    AppCompatTextView(context).apply {
        applyTextStyle(style)
        setTextColor(color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
