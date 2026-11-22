package com.github.damontecres.wholphin.leanback.playback

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.AppThemeColors
import kotlin.math.ln
import kotlin.math.min

/*
 * Small private widget kit of the playback area (no-Compose UI, 2026-11-22). The shared CONTROLS/CARDS widgets are
 * not in this checkout, so these reproduce exactly what the old player used from them: tv-material3 `Surface` /
 * `Button` / `ListItem` / `Card` focus recipes (Neon Board on the WeaselTV theme, the stock ones elsewhere).
 */

/** Translucent blacks of `ui/AppColors` */
internal object PlaybackColors {
    const val BLACK_25 = 0x40000000
    const val BLACK_50 = 0x80000000.toInt()
    const val BLACK_60 = 0x99000000.toInt()
    const val BLACK_75 = 0xBF000000.toInt()
    const val GOLDEN_YELLOW = 0xFFDAB440.toInt()

    /** tv-material3 `surfaceColorAtElevation`: primary tinted over surface by the elevation */
    fun surfaceAtElevation(elevationDp: Float): Int {
        val p = AppTheme.palette
        if (elevationDp == 0f) return p.surface
        val alpha = ((4.5f * ln(elevationDp + 1)) + 2f) / 100f
        return compositeOver(withAlpha(p.primary, alpha), p.surface)
    }
}

/** A border: width in px and colour */
internal data class Stroke(
    val width: Float,
    val color: Int,
)

/** A glow behind a focused surface: blur radius in px and colour */
internal data class Glow(
    val radius: Float,
    val color: Int,
)

/**
 * What a tv-material3 clickable surface looks like in each state (container colour, border, glow, shape).
 * [corner] < 0 draws a pill / circle (`CircleShape`).
 */
internal data class SurfaceStyle(
    val container: Int,
    val focusedContainer: Int = container,
    val disabledContainer: Int = container,
    val selectedContainer: Int = container,
    val focusedSelectedContainer: Int = focusedContainer,
    val corner: Float = 0f,
    val border: Stroke? = null,
    val focusedBorder: Stroke? = null,
    val disabledBorder: Stroke? = border,
    val focusedDisabledBorder: Stroke? = focusedBorder,
    val glow: Glow? = null,
) {
    companion object {
        const val PILL = -1f
    }
}

/**
 * Draws a [SurfaceStyle] for the view's state (focused / enabled / activated = selected). The glow is drawn outside
 * the bounds, so the parents must not clip children.
 */
internal class SurfaceDrawable(
    style: SurfaceStyle,
) : Drawable() {
    var style: SurfaceStyle = style
        set(value) {
            field = value
            invalidateSelf()
        }

    private var focused = false
    private var enabled = true
    private var selected = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        val e = state.contains(android.R.attr.state_enabled)
        val s = state.contains(android.R.attr.state_activated)
        if (f == focused && e == enabled && s == selected) return false
        focused = f
        enabled = e
        selected = s
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val st = style
        val radius = if (st.corner < 0) min(rect.width(), rect.height()) / 2f else st.corner
        val glow = st.glow
        if (focused && glow != null && Color.alpha(glow.color) > 0) {
            drawGlow(canvas, glow, radius)
        }
        val container =
            when {
                !enabled -> st.disabledContainer
                focused && selected -> st.focusedSelectedContainer
                focused -> st.focusedContainer
                selected -> st.selectedContainer
                else -> st.container
            }
        if (Color.alpha(container) > 0) {
            fill.color = container
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        val border =
            when {
                !enabled && focused -> st.focusedDisabledBorder
                !enabled -> st.disabledBorder
                focused -> st.focusedBorder
                else -> st.border
            }
        if (border != null && border.width > 0 && Color.alpha(border.color) > 0) {
            stroke.color = border.color
            stroke.strokeWidth = border.width
            val inset = border.width / 2f
            rect.inset(inset, inset)
            val r = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, stroke)
        }
    }

    private fun drawGlow(
        canvas: Canvas,
        glow: Glow,
        radius: Float,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // tv-material3 Glow: a transparent shape with a coloured shadow layer
            glowPaint.color = Color.TRANSPARENT
            glowPaint.setShadowLayer(glow.radius, 0f, 0f, glow.color)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
        } else {
            // Hardware canvases before API 28 ignore shadow layers on shapes: approximate with fading rings
            glowPaint.clearShadowLayer()
            val steps = 6
            for (i in steps downTo 1) {
                val spread = glow.radius * i / steps
                glowPaint.color = withAlpha(glow.color, Color.alpha(glow.color) / 255f * (1f - i / (steps + 1f)) / 2f)
                val r = RectF(rect).apply { inset(-spread, -spread) }
                canvas.drawRoundRect(r, radius + spread, radius + spread, glowPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Content colours by state (disabled, focused+selected, focused, selected, rest) */
internal fun stateColors(
    rest: Int,
    focused: Int = rest,
    disabled: Int = rest,
    selected: Int = rest,
    focusedSelected: Int = focused,
): ColorStateList =
    ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_focused, android.R.attr.state_activated),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_activated),
            intArrayOf(),
        ),
        intArrayOf(disabled, focusedSelected, focused, selected, rest),
    )

/** Drawable state without `state_enabled`, so a "disabled" surface keeps focus (tv-material3 disabled surfaces do) */
private fun disabledState(state: IntArray): IntArray = state.filter { it != android.R.attr.state_enabled }.toIntArray()

/** A focusable surface container whose [looksDisabled] shows the disabled colours but keeps it focusable */
internal open class SurfaceFrameLayout(
    context: Context,
) : FrameLayout(context) {
    var looksDisabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refreshDrawableState()
            }
        }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace)
        return if (looksDisabled) disabledState(state) else state
    }

    override fun performClick(): Boolean = if (looksDisabled) false else super.performClick()
}

/** A focusable text surface (buttons); [looksDisabled] as [SurfaceFrameLayout] */
internal open class SurfaceTextView(
    context: Context,
) : TextView(context) {
    var looksDisabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refreshDrawableState()
            }
        }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace)
        return if (looksDisabled) disabledState(state) else state
    }

    override fun performClick(): Boolean = if (looksDisabled) false else super.performClick()
}

/**
 * Scale on focus over [NeonColors.FOCUS_MS] (tv-material3 surfaces scale; 1 means no scale) and report focus changes.
 * Replaces any earlier focus listener.
 */
internal fun View.bindFocus(
    focusedScale: Float,
    onFocus: ((Boolean) -> Unit)? = null,
) {
    setOnFocusChangeListener { v, hasFocus ->
        // A focused disabled surface keeps its rest scale (tv-material3 focusedDisabledScale)
        val disabled =
            (v as? SurfaceFrameLayout)?.looksDisabled == true ||
                (v as? SurfaceTextView)?.looksDisabled == true ||
                (v as? PlaybackListItem)?.looksDisabled == true
        if (focusedScale != 1f) {
            val s = if (hasFocus && !disabled) focusedScale else 1f
            v
                .animate()
                .scaleX(s)
                .scaleY(s)
                .setDuration(NeonColors.FOCUS_MS)
                .start()
        }
        onFocus?.invoke(hasFocus)
    }
}

/** Let glows, thumbs and focus scaling draw outside a container */
internal fun ViewGroup.noClip(): ViewGroup =
    apply {
        clipChildren = false
        clipToPadding = false
    }

/** Text styles of the old player (tv-material3 typography roles and the Neon `NeonType` styles) */
internal object PlaybackText {
    fun role(
        tv: TextView,
        role: TextRole,
        sizeSp: Float? = null,
        weight: Int? = null,
    ) = tv.applyTextRole(role, sizeSp, weight)

    /** `NeonType` on the WeaselTV theme (Exo 2 or Audiowide with these metrics), otherwise [fallback] */
    fun neon(
        tv: TextView,
        sizeSp: Float,
        weight: Int,
        lineHeight: Float,
        trackingEm: Float,
        display: Boolean,
        fallback: TextRole,
        fallbackSizeSp: Float? = null,
        fallbackWeight: Int? = null,
    ) {
        if (!AppTheme.isNeon) {
            tv.applyTextRole(fallback, fallbackSizeSp, fallbackWeight)
            return
        }
        tv.typeface = if (display) AppFonts.display(tv.context) else AppFonts.body(tv.context, weight)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tv.letterSpacing = trackingEm
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * lineHeight, tv.resources.displayMetrics)
        val fontHeight = tv.paint.getFontMetricsInt(null)
        if (px.toInt() != fontHeight) tv.setLineSpacing(px - fontHeight, 1f)
    }

    /** `NeonType.sectionTitle()`: 22 Audiowide with the approved tracking, else titleLarge */
    fun sectionTitle(tv: TextView) {
        val size = ApprovedHomeLayout.TITLE_SIZE_SP.toFloat()
        neon(tv, size, 400, 1.2f, 0f, true, TextRole.TITLE_LARGE)
        if (AppTheme.isNeon) tv.letterSpacing = (ApprovedHomeLayout.TITLE_TRACKING_TENTHS_SP / 10f) / size
    }

    /** `NeonType.numeral(size)`: Exo 2 600, else titleMedium at [sizeSp] */
    fun numeral(
        tv: TextView,
        sizeSp: Float,
    ) = neon(tv, sizeSp, 600, 1.2f, 0f, false, TextRole.TITLE_MEDIUM, fallbackSizeSp = sizeSp)

    /** `NeonType.dialogTitle()`: 26 Audiowide, else headlineSmall */
    fun dialogTitle(tv: TextView) = neon(tv, 26f, 400, 1.2f, .06f, true, TextRole.HEADLINE_SMALL)

    /** `NeonType.condensedTitle(size)` */
    fun condensedTitle(
        tv: TextView,
        sizeSp: Float,
    ) = neon(tv, sizeSp, 400, 1.2f, .06f, true, TextRole.HEADLINE_SMALL)

    /** `NeonType.button()`: Exo 2 16 / 700 / tracking .06, else labelLarge */
    fun button(tv: TextView) = neon(tv, 16f, 700, 1f, .06f, false, TextRole.LABEL_LARGE)

    /** `NeonType.cardTitle()`: Exo 2 14 / 600, else bodyMedium semibold */
    fun cardTitle(tv: TextView) = neon(tv, 14f, 600, 1.3f, 0f, false, TextRole.BODY_MEDIUM, fallbackWeight = 600)

    /** `NeonType.cardSubtitle()`: Exo 2 13 / 400, else bodySmall */
    fun cardSubtitle(tv: TextView) = neon(tv, 13f, 400, 1.3f, 0f, false, TextRole.BODY_SMALL, fallbackWeight = 400)

    /** `NeonType.body()`: Exo 2 15 / 400 / 1.5, else bodyMedium */
    fun body(tv: TextView) = neon(tv, 15f, 400, 1.5f, 0f, false, TextRole.BODY_MEDIUM)

    /** `String.neonUpper()` */
    fun upper(text: String): String = if (AppTheme.isNeon) text.uppercase() else text
}

/** Make a plain TextView */
internal fun Context.textView(
    role: TextRole? = null,
    color: Int = AppTheme.palette.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    ellipsize: TextUtils.TruncateAt? = null,
): TextView =
    TextView(this).apply {
        if (role != null) applyTextRole(role)
        setTextColor(color)
        includeFontPadding = false
        if (maxLines != Int.MAX_VALUE) this.maxLines = maxLines
        if (maxLines == 1) isSingleLine = true
        if (ellipsize != null) this.ellipsize = ellipsize
    }

/**
 * The player's glass buttons (`PlaybackButton` / `PlaybackFaButton`): 48dp glass squares with the accent border and
 * glow on the WeaselTV theme, 36dp circles elsewhere. Every theme scales 1.1 when focused.
 */
internal object GlassButton {
    fun sizeDp(): Int = if (AppTheme.isNeon) 48 else 36

    private fun paddingDp(): Int = if (AppTheme.isNeon) 10 else 4

    fun style(
        context: Context,
        accent: Int,
    ): SurfaceStyle {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            val focused = Stroke(context.dpF(1f), accent)
            SurfaceStyle(
                container = NeonColors.GLASS,
                border = Stroke(context.dpF(1f), NeonColors.LINE2),
                focusedBorder = focused,
                disabledBorder = Stroke(context.dpF(1f), withAlpha(NeonColors.LINE2, NeonColors.DISABLED_ALPHA)),
                focusedDisabledBorder = focused,
                glow = Glow(context.dpF(10f), withAlpha(neonGlowAccent(accent), .85f)),
            )
        } else {
            SurfaceStyle(
                container = PlaybackColors.BLACK_25,
                focusedContainer = p.border,
                disabledContainer = withAlpha(p.surfaceVariant, .4f),
                corner = SurfaceStyle.PILL,
                focusedDisabledBorder = Stroke(context.dpF(1.5f), withAlpha(p.onSurfaceVariant, .2f)),
            )
        }
    }

    fun contentColors(accent: Int): ColorStateList {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            stateColors(
                rest = NeonColors.TEXT,
                focused = accent,
                disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
            )
        } else if (AppTheme.theme == AppThemeColors.OLED_BLACK) {
            stateColors(rest = p.onSurface, focused = p.onSurface, disabled = p.onSurface)
        } else {
            ColorStateList.valueOf(p.onSurface)
        }
    }

    /** An icon glass button */
    fun icon(
        context: Context,
        iconRes: Int,
        accent: Int,
    ): SurfaceFrameLayout = frame(context, accent).apply { addView(iconView(context, iconRes, accent)) }

    /** A Font Awesome glass button (18sp glyph) */
    fun fa(
        context: Context,
        glyph: String,
        accent: Int,
    ): SurfaceFrameLayout =
        frame(context, accent).apply {
            addView(
                TextView(context).apply {
                    text = glyph
                    typeface = AppFonts.icons(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(contentColors(accent))
                    isDuplicateParentStateEnabled = true
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

    private fun iconView(
        context: Context,
        iconRes: Int,
        accent: Int,
    ) = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = contentColors(accent)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isDuplicateParentStateEnabled = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun frame(
        context: Context,
        accent: Int,
    ) = SurfaceFrameLayout(context).apply {
        val size = context.dp(sizeDp())
        layoutParams = ViewGroup.MarginLayoutParams(size, size)
        val pad = context.dp(paddingDp())
        setPadding(pad, pad, pad, pad)
        background = SurfaceDrawable(style(context, accent))
        isFocusable = true
        isClickable = true
    }

    /** Swap the icon of a button made by [icon] */
    fun setIcon(
        button: FrameLayout,
        iconRes: Int,
    ) {
        (button.getChildAt(0) as? ImageView)?.setImageResource(iconRes)
    }
}

/**
 * The outline `Button` / `TextButton` of the old UI: transparent with a 1dp `line2` hairline that turns to the accent
 * with a glow on the WeaselTV theme; a surfaceVariant pill that inverts when focused elsewhere. Scales 1.1.
 */
internal object OutlineButton {
    fun style(
        context: Context,
        accent: Int,
        corner: Float = SurfaceStyle.PILL,
    ): SurfaceStyle {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            val focused = Stroke(context.dpF(1f), accent)
            SurfaceStyle(
                container = Color.TRANSPARENT,
                focusedContainer = NeonColors.chipOn(accent),
                border = Stroke(context.dpF(1f), NeonColors.LINE2),
                focusedBorder = focused,
                disabledBorder = Stroke(context.dpF(1f), withAlpha(NeonColors.LINE2, NeonColors.DISABLED_ALPHA)),
                focusedDisabledBorder = focused,
                glow = Glow(context.dpF(10f), withAlpha(neonGlowAccent(accent), .85f)),
            )
        } else {
            SurfaceStyle(
                container = withAlpha(p.surfaceVariant, .8f),
                focusedContainer = p.onSurface,
                disabledContainer = withAlpha(p.surfaceVariant, .4f),
                corner = corner,
                focusedDisabledBorder = Stroke(context.dpF(1.5f), withAlpha(p.onSurfaceVariant, .2f)),
            )
        }
    }

    fun contentColors(accent: Int): ColorStateList {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            stateColors(
                rest = NeonColors.TEXT,
                focused = accent,
                disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
            )
        } else {
            stateColors(
                rest = withAlpha(p.onSurface, .8f),
                focused = p.inverseOnSurface,
                disabled = withAlpha(p.onSurface, .4f),
            )
        }
    }

    /**
     * A text button. [paddingDp] is (start, top, end, bottom); [minHeightDp] is the content height (a minimum on the
     * WeaselTV theme, exact elsewhere, as `Button` did).
     */
    fun text(
        context: Context,
        label: CharSequence,
        accent: Int,
        paddingDp: IntArray = intArrayOf(8, 4, 8, 4),
        minHeightDp: Int = 32,
        corner: Float = SurfaceStyle.PILL,
        textSizeSp: Float? = null,
    ): SurfaceTextView =
        SurfaceTextView(context).apply {
            text = label
            PlaybackText.button(this)
            if (textSizeSp != null) setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            setTextColor(contentColors(accent))
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            setPadding(
                context.dp(paddingDp[0]),
                context.dp(paddingDp[1]),
                context.dp(paddingDp[2]),
                context.dp(paddingDp[3]),
            )
            val h = context.dp(minHeightDp) + context.dp(paddingDp[1]) + context.dp(paddingDp[3])
            minHeight = h
            if (!AppTheme.isNeon) maxHeight = h
            background = SurfaceDrawable(style(context, accent, corner))
            isFocusable = true
            isClickable = true
            bindFocus(1.1f)
        }
}

/** tv-material3 `ListItem` with the Neon list item recipe; focus scale 1.05 */
internal class PlaybackListItem(
    context: Context,
    accent: Int,
    showLeading: Boolean = true,
) : LinearLayout(context) {
    val leading = FrameLayout(context)
    private val dot = View(context)
    val overline: TextView
    val headline: TextView
    val supporting: TextView
    val trailing = LinearLayout(context)
    private val texts = LinearLayout(context)

    // Null while the View constructor runs (drawableStateChanged is called from it)
    private var contentColorList: ColorStateList? = null

    /** Disabled colours, still focusable (tv-material3 list items) */
    var looksDisabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refreshDrawableState()
            }
        }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace)
        return if (looksDisabled) disabledState(state) else state
    }

    override fun performClick(): Boolean = if (looksDisabled) false else super.performClick()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        isFocusable = true
        isClickable = true
        val p = AppTheme.palette
        val neon = AppTheme.isNeon
        background =
            SurfaceDrawable(
                if (neon) {
                    val fill = NeonColors.chipOn(accent)
                    val focused = Stroke(context.dpF(1f), accent)
                    SurfaceStyle(
                        container = Color.TRANSPARENT,
                        focusedContainer = fill,
                        selectedContainer = Color.TRANSPARENT,
                        focusedSelectedContainer = fill,
                        focusedBorder = focused,
                        focusedDisabledBorder = null,
                        glow = Glow(context.dpF(10f), withAlpha(neonGlowAccent(accent), .85f)),
                    )
                } else {
                    SurfaceStyle(
                        container = Color.TRANSPARENT,
                        focusedContainer = p.inverseSurface,
                        selectedContainer = withAlpha(p.secondaryContainer, .4f),
                        focusedSelectedContainer = p.inverseSurface,
                        corner = context.dpF(8f),
                    )
                },
            )
        val content =
            if (neon) {
                stateColors(
                    rest = NeonColors.TEXT,
                    focused = NeonColors.TEXT,
                    disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
                    selected = accent,
                    focusedSelected = accent,
                )
            } else {
                stateColors(
                    rest = p.onSurface,
                    focused = p.inverseOnSurface,
                    disabled = withAlpha(p.onSurface, .38f),
                    selected = p.onSecondaryContainer,
                    focusedSelected = p.inverseOnSurface,
                )
            }
        if (showLeading) {
            // The 32dp leading box with the 8dp "selected" dot (SelectedLeadingContent), then 8dp to the text
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            dot.isVisible = false
            leading.addView(dot, FrameLayout.LayoutParams(context.dp(8), context.dp(8), Gravity.CENTER))
            addView(
                leading,
                LayoutParams(context.dp(32), context.dp(32)).apply { marginEnd = context.dp(8) },
            )
        }
        texts.orientation = VERTICAL
        overline = context.textView(TextRole.LABEL_SMALL).apply { isVisible = false }
        headline = context.textView(TextRole.TITLE_MEDIUM)
        supporting = context.textView(TextRole.BODY_SMALL).apply { isVisible = false }
        listOf(overline, headline, supporting).forEach {
            it.setTextColor(content)
            it.isDuplicateParentStateEnabled = true
            texts.addView(it)
        }
        texts.isDuplicateParentStateEnabled = true
        addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing.orientation = HORIZONTAL
        trailing.gravity = Gravity.CENTER_VERTICAL
        trailing.isDuplicateParentStateEnabled = true
        addView(
            trailing,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    context.dp(8)
            },
        )
        contentColorList = content
        bindFocus(1.05f)
        updateMinHeight()
    }

    /** Bind the texts and the selected dot */
    fun bind(
        headlineText: CharSequence,
        supportingText: CharSequence? = null,
        selected: Boolean = false,
        enabled: Boolean = true,
        overlineText: CharSequence? = null,
    ) {
        headline.text = headlineText
        supporting.text = supportingText
        supporting.isVisible = !supportingText.isNullOrEmpty()
        overline.text = overlineText
        overline.isVisible = !overlineText.isNullOrEmpty()
        isActivated = selected
        looksDisabled = !enabled
        dot.isVisible = selected
        tintDot()
        updateMinHeight()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        tintDot()
    }

    /** The dot takes the content colour (leading content is drawn at 80 %) */
    private fun tintDot() {
        val list = contentColorList ?: return
        (dot.background as? GradientDrawable)?.color =
            ColorStateList.valueOf(withAlpha(list.getColorForState(drawableState, list.defaultColor), .8f))
    }

    private fun updateMinHeight() {
        val lines = 1 + (if (supporting.isVisible) 1 else 0) + (if (overline.isVisible) 1 else 0)
        minimumHeight =
            context.dp(
                when {
                    lines >= 3 -> 80
                    lines == 2 -> 64
                    leading.parent != null -> 56
                    else -> 48
                },
            )
    }
}

/** The card focus recipe (`neonCardBorder/Glow/Scale/Shape`, tv `CardDefaults` elsewhere) */
internal object CardFocus {
    fun scale(appearance: HomeCardAppearance): Float = if (AppTheme.isNeon) appearance.focusScalePercent / 100f else 1.1f

    fun cornerPx(
        context: Context,
        appearance: HomeCardAppearance,
    ): Float = if (AppTheme.isNeon) context.dpF(appearance.cornerRadiusDp.toFloat()) else context.dpF(8f)

    /** `homeCardAccent(accent)` with this appearance */
    fun accent(
        appearance: HomeCardAppearance,
        accent: Int,
        rowAccent: Int? = null,
    ): Int =
        when (appearance.accentIndex) {
            1 -> NeonColors.VOLT
            2 -> NeonColors.ORANGE
            3 -> NeonColors.ICE_WHITE
            4 -> NeonColors.CYAN
            5 -> NeonColors.FUCHSIA
            else -> rowAccent ?: accent
        }

    fun style(
        context: Context,
        appearance: HomeCardAppearance,
        accent: Int,
        container: Int,
        borderAccent: Int? = null,
    ): SurfaceStyle {
        val corner = cornerPx(context, appearance)
        return if (AppTheme.isNeon) {
            val a = accent(appearance, accent)
            val focused =
                if (appearance.borderWidthDp > 0 && appearance.borderOpacityPercent > 0) {
                    Stroke(
                        context.dpF(appearance.borderWidthDp.toFloat()),
                        withAlpha(borderAccent ?: a, appearance.borderOpacityPercent / 100f),
                    )
                } else {
                    null
                }
            SurfaceStyle(
                container = container,
                corner = corner,
                focusedBorder = focused,
                glow =
                    Glow(
                        context.dpF(appearance.glowSpreadDp.toFloat()),
                        withAlpha(
                            neonGlowAccent(a),
                            appearance.glowOpacityPercent / 100f,
                        ),
                    ),
            )
        } else {
            SurfaceStyle(
                container = container,
                corner = corner,
                focusedBorder = Stroke(context.dpF(3f), AppTheme.palette.border),
            )
        }
    }
}

/** Shared animation helpers */
internal object PlaybackAnim {
    /** Fade a view in/out; GONE at the end of a fade out so focus search ignores it */
    fun fade(
        view: View,
        show: Boolean,
        durationMs: Long = 220L,
        goneWhenHidden: Boolean = true,
    ) {
        view.animate().cancel()
        if (show) {
            if (!view.isVisible) {
                view.alpha = 0f
                view.isVisible = true
            }
            view
                .animate()
                .alpha(1f)
                .setDuration(durationMs)
                .setListener(null)
                .start()
        } else if (view.isVisible) {
            view
                .animate()
                .alpha(0f)
                .setDuration(durationMs)
                .withEndAction { if (goneWhenHidden) view.visibility = View.GONE else view.visibility = View.INVISIBLE }
                .start()
        }
    }

    /** Animate a float from [from] to [to] with a spring-like deceleration */
    fun value(
        from: Float,
        to: Float,
        durationMs: Long = 300L,
        update: (Float) -> Unit,
    ): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { update(it.animatedValue as Float) }
            start()
        }
}
