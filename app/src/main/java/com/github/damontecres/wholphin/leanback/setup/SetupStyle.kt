package com.github.damontecres.wholphin.leanback.setup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import java.util.UUID
import kotlin.math.abs

/*
 * The SETUP area's look as Views (no-Compose UI, 2026-11-22): the kid-profile panel colours and text styles
 * (`ui/kids/KidPanel.kt`), the Neon page title, eyebrow and rule (`ui/theme/Neon*.kt`) and a focusable surface that
 * replaces the tv-material3 `Surface` (fill, border, glow, focus scale) the old screens were built from. Sizes are the
 * old dp/sp values.
 */

/** Colours of the kid-profile panels (`KidColors`). These screens always use them, whatever the app theme. */
internal object KidPalette {
    const val LIME = NeonColors.VOLT
    const val PANEL = 0xFF0C0F14.toInt()
    const val PANEL_EDGE = 0xFF1F242C.toInt()
    const val TILE = 0xFF12151C.toInt()
    const val TILE_EDGE = 0xFF363C46.toInt()
    const val LIME_FILL = 0xFF161E08.toInt()
    const val TEXT = NeonColors.TEXT
    const val MUTED = 0xFFAEB5C0.toInt()
    const val FAINT = 0xFF6B7380.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()
    const val TRACK = 0xFF22262E.toInt()
}

/** Weight 800 (ExtraBold) maps to the bold Exo 2 face the app ships */
internal const val WEIGHT_BOLD = 700

/** `KidText.body`: Exo 2 (the default face on flavors without it), line height 1.3 */
internal fun TextView.kidBody(
    sizeSp: Float,
    weight: Int = 400,
    trackingEm: Float = 0f,
) {
    typeface = AppFonts.body(context, weight)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    letterSpacing = trackingEm
    lineHeightSp(sizeSp * 1.3f)
}

/** `KidText.display`: Audiowide, line height 1.2 */
internal fun TextView.kidDisplay(
    sizeSp: Float,
    trackingEm: Float = .02f,
) {
    typeface = AppFonts.display(context)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    letterSpacing = trackingEm
    lineHeightSp(sizeSp * 1.2f)
}

/** A line height in sp, before API 28's `setLineHeight` */
internal fun TextView.lineHeightSp(sp: Float) {
    val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    val fontHeight = paint.getFontMetricsInt(null)
    if (px.toInt() != fontHeight) setLineSpacing(px - fontHeight, 1f)
}

/** Font Awesome glyph text */
internal fun TextView.faGlyph(sizeSp: Float) {
    typeface = AppFonts.icons(context)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    includeFontPadding = false
}

/**
 * `NeonType.pageTitle()` (Audiowide 40, tracking .06em, uppercase in code) on the WeaselTV theme, else the stock
 * [fallback] role.
 */
internal fun TextView.neonHeader(
    sizeSp: Float,
    fallback: TextRole,
) {
    if (AppTheme.isNeon) {
        typeface = AppFonts.display(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = .06f
        lineHeightSp(sizeSp * 1.2f)
    } else {
        applyTextRole(fallback)
    }
}

/** A text view with the given colour, gravity and line limit */
internal fun Context.setupText(
    text: CharSequence? = null,
    color: Int = AppTheme.palette.onSurface,
    gravity: Int = Gravity.START,
    maxLines: Int = Int.MAX_VALUE,
    style: TextView.() -> Unit = { applyTextRole(TextRole.BODY_LARGE) },
): TextView =
    TextView(this).apply {
        style()
        this.text = text
        setTextColor(color)
        this.gravity = gravity
        includeFontPadding = false
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

/**
 * [text] with the first [part] in it emphasised: bold, and in [color] when set (`emphasise` in `ui/kids/KidPanel.kt`).
 */
internal fun Context.emphasise(
    text: String,
    part: String,
    color: Int? = null,
): CharSequence {
    val start = if (part.isEmpty()) -1 else text.indexOf(part)
    if (start < 0) return text
    val end = start + part.length
    return SpannableString(text).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setSpan(TypefaceSpan(AppFonts.body(this@emphasise, WEIGHT_BOLD)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (color != null) setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** A vertical gap of [heightDp] (`KidGap`, `Spacer`) */
internal fun Context.gap(heightDp: Float): View =
    View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

/** A horizontal gap of [widthDp] */
internal fun Context.hGap(widthDp: Float): View =
    View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1) }

internal fun vertical(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }

internal fun horizontal(context: Context): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

internal fun wrap(
    gravity: Int = Gravity.NO_GRAVITY,
    width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.gravity = gravity }

internal fun matchWidth(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal fun frame(
    width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    height: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    gravity: Int = Gravity.NO_GRAVITY,
): FrameLayout.LayoutParams = FrameLayout.LayoutParams(width, height, gravity)

/** Lets a child's glow and focus scale draw past this view's edges */
internal fun ViewGroup.noClip(): ViewGroup =
    apply {
        clipChildren = false
        clipToPadding = false
    }

/** Whether [event] is OK on a remote (`isEnterKey`) */
internal fun isEnterKeyCode(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_SELECT ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A

/**
 * A consistent colour for a UUID (`rememberIdColor` in `ui/setup/ServerList.kt`): the same hue, saturation and value
 * from the id's hash, as ARGB.
 */
internal fun idColor(
    id: UUID?,
    alpha: Float = 1f,
    nullColor: Int = AppTheme.palette.surfaceVariant,
): Int {
    if (id == null) return nullColor
    val hash = id.hashCode()
    val hue = (hash % 360).toFloat()
    val saturation = 0.6f + ((hash / 360) % 40).toFloat() / 100f
    val brightness = 0.4f + ((hash / 14400) % 30).toFloat() / 100f
    val c = brightness * saturation
    val x = c * (1 - abs((hue / 60f) % 2f - 1))
    val m = brightness - c
    val (r, g, b) =
        when {
            hue < 60 -> Triple(c, x, 0f)
            hue < 120 -> Triple(x, c, 0f)
            hue < 180 -> Triple(0f, c, x)
            hue < 240 -> Triple(0f, x, c)
            hue < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

    fun ch(v: Float): Int = ((v + m).coerceIn(0f, 1f) * 255).toInt()
    return ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/**
 * A soft glow of [color] around a rect (tv-material3 `Glow`, `kidGlow`): a shadow layer of [radiusPx], drawn before the
 * element's own fill so only the part outside it shows. Hardware canvases draw rect shadows from API 28.
 */
internal object GlowPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun rect(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
        radiusPx: Float,
    ) {
        if (radiusPx <= 0f || (color ushr 24) == 0) return
        paint.color = color
        paint.setShadowLayer(radiusPx, 0f, 0f, color)
        canvas.drawRect(left, top, right, bottom, paint)
        paint.clearShadowLayer()
    }
}

/** How a [SetupSurface] looks in each state */
internal data class SurfaceStyle(
    val fill: Int,
    val focusedFill: Int = fill,
    val disabledFill: Int = fill,
    val selectedFill: Int? = null,
    val borderWidthDp: Float = 0f,
    val borderColor: Int = 0,
    val focusedBorderWidthDp: Float = 0f,
    val focusedBorderColor: Int = 0,
    val disabledBorderWidthDp: Float = borderWidthDp,
    val disabledBorderColor: Int = borderColor,
    val selectedBorderWidthDp: Float? = null,
    val selectedBorderColor: Int? = null,
    /** A dashed rest edge (the "add" tiles); no dashes while focused */
    val dashedRestColor: Int? = null,
    val glowColor: Int = 0,
    val glowRadiusDp: Float = 0f,
    val focusedScale: Float = 1f,
    val circle: Boolean = false,
    /** Fully rounded ends (the stock app's pill buttons) */
    val pill: Boolean = false,
    val cornerDp: Float = 0f,
)

/**
 * A focusable, clickable surface: the tv-material3 `Surface(onClick, onLongClick, colors, border, glow, scale)` as a
 * View. It draws its glow outside its bounds (the parents must not clip, see [noClip]), its fill, its children and its
 * border on top, and scales on focus over [NeonColors.FOCUS_MS].
 */
internal open class SetupSurface(
    context: Context,
    style: SurfaceStyle,
) : FrameLayout(context) {
    var style: SurfaceStyle = style
        set(value) {
            field = value
            applyShape()
            animateScale(hasFocus() && isFocused)
            invalidate()
        }

    /** The selected look (eg the current avatar) */
    var selectedState: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the focus state changes, eg to recolour a label */
    var onFocusVisual: ((Boolean) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val dashPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(context.dpF(5f), context.dpF(4f)), 0f)
            strokeWidth = context.dpF(1.5f)
        }
    private val rect = RectF()
    private var scaleAnimator: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
        setWillNotDraw(false)
        clipChildren = true
        applyShape()
    }

    private fun applyShape() {
        val s = style
        if (s.circle || s.pill || s.cornerDp > 0f) {
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) {
                        when {
                            s.circle -> outline.setOval(0, 0, view.width, view.height)
                            s.pill -> outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                            else -> outline.setRoundRect(0, 0, view.width, view.height, context.dpF(s.cornerDp))
                        }
                    }
                }
            clipToOutline = true
        } else {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = false
        }
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateScale(gainFocus)
        onFocusVisual?.invoke(gainFocus)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        invalidate()
    }

    private fun animateScale(focused: Boolean) {
        val target = if (focused) style.focusedScale else 1f
        scaleAnimator?.cancel()
        if (scaleX == target) return
        scaleAnimator =
            ValueAnimator.ofFloat(scaleX, target).apply {
                duration = NeonColors.FOCUS_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Float
                    scaleX = v
                    scaleY = v
                }
                start()
            }
    }

    override fun draw(canvas: Canvas) {
        val s = style
        val focused = isFocused
        val w = width.toFloat()
        val h = height.toFloat()
        // Glow first, outside the bounds (never clipped: only rect surfaces glow)
        if (focused && isEnabled && s.glowRadiusDp > 0f && !clipToOutline) {
            GlowPainter.rect(canvas, 0f, 0f, w, h, s.glowColor, context.dpF(s.glowRadiusDp))
        }
        fillPaint.color =
            when {
                !isEnabled -> s.disabledFill
                focused -> s.focusedFill
                selectedState && s.selectedFill != null -> s.selectedFill
                else -> s.fill
            }
        if ((fillPaint.color ushr 24) != 0) {
            when {
                s.circle -> canvas.drawOval(0f, 0f, w, h, fillPaint)
                s.pill -> canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, fillPaint)
                s.cornerDp > 0f -> canvas.drawRoundRect(0f, 0f, w, h, context.dpF(s.cornerDp), context.dpF(s.cornerDp), fillPaint)
                else -> canvas.drawRect(0f, 0f, w, h, fillPaint)
            }
        }
        super.draw(canvas)
        // Border on top of the content, as tv-material3 draws it
        val (bw, bc) =
            when {
                !isEnabled -> s.disabledBorderWidthDp to s.disabledBorderColor
                focused -> s.focusedBorderWidthDp to s.focusedBorderColor
                selectedState && s.selectedBorderWidthDp != null -> s.selectedBorderWidthDp to (s.selectedBorderColor ?: 0)
                else -> s.borderWidthDp to s.borderColor
            }
        if (bw > 0f && (bc ushr 24) != 0) {
            val stroke = context.dpF(bw)
            borderPaint.strokeWidth = stroke
            borderPaint.color = bc
            rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
            when {
                s.circle -> canvas.drawOval(rect, borderPaint)
                s.pill -> canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, borderPaint)
                s.cornerDp > 0f -> canvas.drawRoundRect(rect, context.dpF(s.cornerDp), context.dpF(s.cornerDp), borderPaint)
                else -> canvas.drawRect(rect, borderPaint)
            }
        } else if (!focused && s.dashedRestColor != null && isEnabled) {
            val stroke = dashPaint.strokeWidth
            dashPaint.color = s.dashedRestColor
            canvas.drawRect(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2, dashPaint)
        }
    }

    override fun onDetachedFromWindow() {
        scaleAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Neon styles shared by the setup screens (the Neon Board recipe, `ui/theme/Neon.kt`) */
internal object NeonSurfaces {
    /**
     * The server/user/add tiles (`ServerIconCard`, `UserIconCard`, `AddServerCard`, `AddUserCard`): square `card2` with
     * a 1dp `line2` edge, `chipOn(volt)` and a 1dp volt border with a 24dp volt glow when focused, scale 1.06; on other
     * themes a circle of [restFill]/[focusedFill], a 3dp `onSurface` border when focused and scale 1.2.
     */
    fun tile(
        restFill: Int,
        focusedFill: Int,
    ): SurfaceStyle =
        if (AppTheme.isNeon) {
            SurfaceStyle(
                fill = NeonColors.CARD2,
                focusedFill = NeonColors.chipOn(NeonColors.VOLT),
                borderWidthDp = 1f,
                borderColor = NeonColors.LINE2,
                focusedBorderWidthDp = 1f,
                focusedBorderColor = NeonColors.VOLT,
                glowColor = withAlpha(NeonSections.glowFor(NeonColors.VOLT), .5f),
                glowRadiusDp = 24f,
                focusedScale = 1.06f,
            )
        } else {
            SurfaceStyle(
                fill = restFill,
                focusedFill = focusedFill,
                focusedBorderWidthDp = 3f,
                focusedBorderColor = AppTheme.palette.onSurface,
                focusedScale = 1.2f,
                circle = true,
            )
        }

    /**
     * The outline `Button` / `TextButton` (`ui/components/Button.kt`): Neon is transparent with a 1dp `line2` edge,
     * `chipOn(accent)` with a 1dp accent border and glow when focused; the stock button is a pill of `surfaceVariant`
     * that turns `onSurface` when focused. Both scale 1.1 on focus (tv-material3 default).
     */
    fun button(accent: Int = NeonColors.VOLT): SurfaceStyle {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            SurfaceStyle(
                fill = 0,
                focusedFill = NeonColors.chipOn(accent),
                disabledFill = 0,
                borderWidthDp = 1f,
                borderColor = NeonColors.LINE2,
                focusedBorderWidthDp = 1f,
                focusedBorderColor = accent,
                disabledBorderWidthDp = 1f,
                disabledBorderColor = withAlpha(NeonColors.LINE2, NeonColors.DISABLED_ALPHA),
                glowColor = withAlpha(NeonSections.glowFor(accent), .85f),
                glowRadiusDp = 10f,
                focusedScale = 1.1f,
            )
        } else {
            SurfaceStyle(
                fill = withAlpha(p.surfaceVariant, .8f),
                focusedFill = p.onSurface,
                disabledFill = withAlpha(p.surfaceVariant, .4f),
                focusedScale = 1.1f,
                pill = true,
            )
        }
    }

    /** The label colour of [button] in each state */
    fun buttonContent(
        focused: Boolean,
        enabled: Boolean,
        accent: Int = NeonColors.VOLT,
    ): Int {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            when {
                !enabled -> withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA)
                focused -> accent
                else -> NeonColors.TEXT
            }
        } else {
            when {
                !enabled -> withAlpha(p.onSurface, .4f)
                focused -> p.inverseOnSurface
                else -> withAlpha(p.onSurface, .8f)
            }
        }
    }

    /**
     * A dialog/menu row (`neonListItemColors/Border/Glow/Shape`): transparent at rest, `chipOn(accent)` with a 1dp
     * accent border and glow when focused. Other themes: the tv-material3 list item (rounded, `inverseSurface` focus).
     */
    fun listItem(accent: Int = NeonColors.VOLT): SurfaceStyle {
        val p = AppTheme.palette
        return if (AppTheme.isNeon) {
            SurfaceStyle(
                fill = 0,
                focusedFill = NeonColors.chipOn(accent),
                focusedBorderWidthDp = 1f,
                focusedBorderColor = accent,
                glowColor = withAlpha(NeonSections.glowFor(accent), .85f),
                glowRadiusDp = 10f,
                focusedScale = 1.02f,
            )
        } else {
            SurfaceStyle(
                fill = 0,
                focusedFill = p.inverseSurface,
                focusedScale = 1.02f,
                cornerDp = 8f,
            )
        }
    }

    fun listItemContent(focused: Boolean): Int {
        val p = AppTheme.palette
        return when {
            AppTheme.isNeon -> NeonColors.TEXT
            focused -> p.inverseOnSurface
            else -> p.onSurface
        }
    }
}
