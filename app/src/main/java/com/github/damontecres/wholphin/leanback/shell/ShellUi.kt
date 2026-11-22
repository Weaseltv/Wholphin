package com.github.damontecres.wholphin.leanback.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha

/*
 * Small View helpers private to the shell (menu rows, prompts, chips), styled with the leanback.theme tokens. The
 * CARDS and CONTROLS areas own the shared widgets; these exist so the shell does not wait for them.
 */

/**
 * The Neon roles of the old `NeonType` that the shell uses. Each falls back to the stock text role when the WeaselTV
 * theme is off, as `NeonType` did.
 */
internal object ShellType {
    /** Exo 2 body styles: `NeonType.exo(...)` */
    fun exo(
        view: TextView,
        sizeSp: Float,
        weight: Int,
        lineHeight: Float,
        trackingEm: Float,
        fallback: TextRole,
    ) {
        if (!AppTheme.isNeon) {
            view.applyTextRole(fallback)
            return
        }
        view.applyTextRole(TextRole.BODY_MEDIUM, sizeSp = sizeSp, weight = weight)
        view.letterSpacing = trackingEm
        view.setLineHeightPx(view.context.spToPx(sizeSp * lineHeight))
    }

    /** Audiowide display styles: `NeonType.header(...)` */
    fun header(
        view: TextView,
        sizeSp: Float,
        fallback: TextRole,
    ) {
        if (!AppTheme.isNeon) {
            view.applyTextRole(fallback)
            return
        }
        view.typeface = AppFonts.display(view.context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.letterSpacing = .06f
        view.setLineHeightPx(view.context.spToPx(sizeSp * 1.2f))
    }

    /** `NeonType.dialogTitle()`: 26 */
    fun dialogTitle(view: TextView) = header(view, 26f, TextRole.HEADLINE_SMALL)

    /** `NeonType.eyebrow()`: 12 / 700 / tracking .16em */
    fun eyebrow(view: TextView) = exo(view, 12f, 700, 1.2f, .16f, TextRole.LABEL_SMALL)

    /** `NeonType.button()`: 16 / 700 / tracking .06em */
    fun button(view: TextView) = exo(view, 16f, 700, 1f, .06f, TextRole.LABEL_LARGE)

    /** `NeonType.railLabel()`: 15 / 500 */
    fun railLabel(view: TextView) = exo(view, 15f, 500, 1.2f, 0f, TextRole.LABEL_MEDIUM)

    /** `NeonType.rowSubtitle()`: 14 / 400 */
    fun rowSubtitle(view: TextView) = exo(view, 14f, 400, 1.3f, 0f, TextRole.BODY_MEDIUM)

    /** `NeonType.small()`: 13 / 400 */
    fun small(view: TextView) = exo(view, 13f, 400, 1.4f, 0f, TextRole.BODY_SMALL)

    /** `NeonType.numeral(size)`: SemiBold */
    fun numeral(
        view: TextView,
        sizeSp: Float,
    ) {
        if (!AppTheme.isNeon) {
            view.applyTextRole(TextRole.TITLE_MEDIUM, sizeSp = sizeSp)
            return
        }
        exo(view, sizeSp, 600, 1.2f, 0f, TextRole.TITLE_MEDIUM)
    }
}

/** `String.neonUpper()`: display text is uppercased in code on the WeaselTV theme only */
internal fun String.neonUpper(): String = if (AppTheme.isNeon) uppercase() else this

internal fun Context.spToPx(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

/** Line height in px, like `TextView.setLineHeight` on API 28+, on every API */
internal fun TextView.setLineHeightPx(px: Float) {
    val fontHeight = paint.getFontMetricsInt(null)
    if (px.toInt() != fontHeight) setLineSpacing(px - fontHeight, 1f)
}

/**
 * A rectangle (optionally rounded or inset) with a fill, a border and a glow around it, drawn by one Drawable: the
 * shell's version of the Neon focus recipe (fill, 1dp accent border, accent glow) and of plain panels.
 *
 * The glow uses the framework shadow layer, as tv-material's `Glow` did, and draws outside the drawable's bounds:
 * the view's parent must not clip children for it to show.
 */
internal class ShellBoxDrawable(
    private val context: Context,
) : Drawable() {
    var fillColor: Int = 0
        set(value) {
            field = value
            invalidateSelf()
        }
    var borderColor: Int = 0
        set(value) {
            field = value
            invalidateSelf()
        }
    var borderWidthPx: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }
    var cornerRadiusPx: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }
    var glowColor: Int = 0
        set(value) {
            field = value
            invalidateSelf()
        }
    var glowRadiusPx: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Horizontal inset of the box inside the bounds (the rail rows' `RailItemShape`) */
    var insetXPx: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = insetXPx.coerceAtMost(b.width() / 4f)
        rect.set(b.left + inset, b.top.toFloat(), b.right - inset, b.bottom.toFloat())
        if (glowColor ushr 24 != 0 && glowRadiusPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Only the shadow shows: the shape itself is drawn in a fully transparent colour
            glowPaint.color = 0x01000000
            glowPaint.setShadowLayer(glowRadiusPx, 0f, 0f, glowColor)
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, glowPaint)
        }
        if (fillColor ushr 24 != 0) {
            fillPaint.color = fillColor
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)
        }
        if (borderColor ushr 24 != 0 && borderWidthPx > 0f) {
            borderPaint.color = borderColor
            borderPaint.strokeWidth = borderWidthPx
            val half = borderWidthPx / 2f
            canvas.drawRoundRect(
                rect.left + half,
                rect.top + half,
                rect.right - half,
                rect.bottom - half,
                (cornerRadiusPx - half).coerceAtLeast(0f),
                (cornerRadiusPx - half).coerceAtLeast(0f),
                borderPaint,
            )
        }
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        val inset = insetXPx.coerceAtMost(b.width() / 4f).toInt()
        outline.setRoundRect(Rect(b.left + inset, b.top, b.right - inset, b.bottom), cornerRadiusPx)
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Sets everything at once */
    fun set(
        fill: Int = 0,
        border: Int = 0,
        borderDp: Float = 0f,
        cornerDp: Float = cornerRadiusPx / context.dpF(1f),
        glow: Int = 0,
        glowDp: Float = 0f,
    ) {
        fillColor = fill
        borderColor = border
        borderWidthPx = context.dpF(borderDp)
        cornerRadiusPx = context.dpF(cornerDp)
        glowColor = glow
        glowRadiusPx = context.dpF(glowDp)
    }
}

/**
 * A text button for the shell's own prompts (update popup, What's New), with the old Compose `Button` looks: on the
 * WeaselTV theme an outline button (1dp `line2` at rest; accent border, `chipOn` fill, accent label and glow when
 * focused), elsewhere a pill that turns `onSurface` when focused. It never scales.
 */
internal open class ShellButton(
    context: Context,
) : AppCompatTextView(context) {
    private val box = ShellBoxDrawable(context)

    /** The Neon accent (the old `LocalNeonAccent`) */
    var accent: Int = NeonColors.VOLT
        set(value) {
            field = value
            refresh()
        }

    /** Overrides for What's New: its own rest/focused fills and label colours */
    var restFill: Int? = null
    var focusedFill: Int? = null
    var restLabel: Int? = null
    var focusedLabel: Int? = null

    init {
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        background = box
        maxLines = 1
        if (AppTheme.isNeon) ShellType.button(this) else applyTextRole(TextRole.LABEL_LARGE)
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

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = super.onKeyUp(keyCode, event)

    fun refresh() {
        val focused = isFocused
        if (AppTheme.isNeon) {
            box.set(
                fill = if (focused) focusedFill ?: NeonColors.chipOn(accent) else restFill ?: 0,
                border = if (focused) accent else NeonColors.LINE2,
                borderDp = 1f,
                cornerDp = 0f,
                glow = if (focused) withAlpha(neonGlowAccent(accent), .85f) else 0,
                glowDp = 10f,
            )
            setTextColor(if (focused) focusedLabel ?: accent else restLabel ?: NeonColors.TEXT)
        } else {
            val p = AppTheme.palette
            box.set(
                fill = if (focused) p.onSurface else withAlpha(p.surfaceVariant, .8f),
                cornerDp = 50f,
            )
            setTextColor(if (focused) p.inverseOnSurface else withAlpha(p.onSurface, .8f))
        }
    }
}

/** The tonal surface colour of a stock Material 3 panel at [elevationDp] (`surfaceColorAtElevation`) */
internal fun surfaceAtElevation(elevationDp: Float): Int {
    val p = AppTheme.palette
    if (elevationDp <= 0f) return p.surface
    val alpha = ((4.5f * kotlin.math.ln(elevationDp + 1f)) + 2f) / 100f
    return compositeOver(withAlpha(p.primary, alpha), p.surface)
}

/** Ignore every key while [block] says so (used for views that only show) */
internal fun View.setKeyBlocker(block: () -> Boolean) {
    setOnKeyListener { _, _, _ -> block() }
}
