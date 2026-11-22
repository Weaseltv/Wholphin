package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import kotlin.math.ln

/**
 * The look of the settings panels (no-Compose UI, 2026-11-22): the ARGB/dp port of the old Compose recipe in
 * `ui/theme/Neon.kt` (`neonListItemColors/Border/Glow/Shape`), `NeonType.settingsLabel/settingsValue`,
 * `PreferencePanelHeader` and the stock tv-material3 `ListItem` the other themes used.
 *
 * Every value is read when a row binds, so all nine themes and the page's section accent apply.
 */
internal object SettingsLook {
    /** The Neon Board recipe is active (the old `isWeaselTv()`) */
    val neon: Boolean get() = AppTheme.isNeon

    /** tv-material3 `surfaceColorAtElevation`: primary tinted over surface by the elevation */
    fun surfaceAt(elevationDp: Float): Int {
        val palette = AppTheme.palette
        if (elevationDp <= 0f) return palette.surface
        val alpha = ((4.5f * ln(elevationDp + 1f)) + 2f) / 100f
        return compositeOver(withAlpha(palette.primary, alpha), palette.surface)
    }

    /** The settings panel background: Neon `stage`, otherwise the surface at 1dp elevation */
    val panelBackground: Int get() = if (neon) NeonColors.STAGE else surfaceAt(1f)

    /** The page behind the panel (`MaterialTheme.colorScheme.background`) */
    val pageBackground: Int get() = AppTheme.palette.background

    /** Row label colour at rest */
    val textColor: Int get() = if (neon) NeonColors.TEXT else AppTheme.palette.onSurface

    /** Row label colour while focused: Neon keeps `text`; tv-material3 rows switch to `inverseOnSurface` */
    val focusedTextColor: Int get() = if (neon) NeonColors.TEXT else AppTheme.palette.inverseOnSurface

    /** Row summary colour: the Neon value is the page accent; other themes keep the content colour */
    fun summaryColor(accent: Int): Int = if (neon) accent else withAlpha(AppTheme.palette.onSurface, .8f)

    fun focusedSummaryColor(accent: Int): Int = if (neon) accent else withAlpha(AppTheme.palette.inverseOnSurface, .8f)

    fun titleColors(): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(),
            ),
            intArrayOf(withAlpha(textColor, NeonColors.DISABLED_ALPHA), focusedTextColor, focusedTextColor, textColor),
        )

    fun summaryColors(accent: Int): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(),
            ),
            intArrayOf(
                withAlpha(summaryColor(accent), NeonColors.DISABLED_ALPHA),
                focusedSummaryColor(accent),
                focusedSummaryColor(accent),
                summaryColor(accent),
            ),
        )

    /** A row's label: Neon `settingsLabel` (Exo 2 17 / 500 / 1.3), otherwise `titleSmall` */
    fun styleTitle(view: TextView) {
        if (neon) {
            view.setFont(17f, 500, 1.3f)
        } else {
            view.applyTextRole(TextRole.TITLE_SMALL)
        }
        view.setTextColor(titleColors())
    }

    /** A row's value: Neon `settingsValue` (Exo 2 15 / 600 / 1.3, accent), otherwise `bodySmall` */
    fun styleSummary(
        view: TextView,
        accent: Int,
    ) {
        if (neon) {
            view.setFont(15f, 600, 1.3f)
        } else {
            view.applyTextRole(TextRole.BODY_SMALL)
        }
        view.setTextColor(summaryColors(accent))
    }

    /** A group heading: Neon `titleMedium` at 22 / 28 bold, otherwise `titleMedium` */
    fun styleCategory(view: TextView) {
        if (neon) {
            view.applyTextRole(TextRole.TITLE_MEDIUM, sizeSp = 22f, weight = 700)
            view.setLineHeightPx(view.context, 28f)
        } else {
            view.applyTextRole(TextRole.TITLE_MEDIUM)
        }
        view.setTextColor(AppTheme.palette.onSurface)
    }

    /** Body text in panels and dialogs */
    fun styleBody(view: TextView) {
        view.applyTextRole(TextRole.BODY_MEDIUM)
        view.setTextColor(AppTheme.palette.onSurface)
    }

    /** Exo 2 (or the platform font) at [sizeSp] / [weight] with a [lineHeight] multiple, no tracking */
    fun TextView.setFont(
        sizeSp: Float,
        weight: Int,
        lineHeight: Float,
    ) {
        typeface = AppFonts.body(context, weight)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        letterSpacing = 0f
        setLineHeightPx(context, sizeSp * lineHeight)
    }

    fun TextView.setLineHeightPx(
        context: Context,
        lineHeightSp: Float,
    ) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeightSp, context.resources.displayMetrics)
        val fontHeight = paint.getFontMetricsInt(null)
        if (px.toInt() != fontHeight) setLineSpacing(px - fontHeight, 1f)
    }

    /** The section accent's paired glow colour (`neonGlowAccent`) */
    fun glowFor(accent: Int): Int = NeonSections.glowFor(accent)

    /** A settings row's background: [SettingsRowBackground] in the page accent */
    fun rowBackground(
        context: Context,
        accent: Int,
    ): Drawable = SettingsRowBackground(context, accent)

    /** Applies the row background and the focus scale of the stock tv-material3 list item (Neon rows do not scale) */
    fun decorateRow(
        row: View,
        accent: Int,
    ) {
        row.background = rowBackground(row.context, accent)
        if (!neon) {
            row.setOnFocusChangeListener { v, hasFocus ->
                v
                    .animate()
                    .scaleX(if (hasFocus) LIST_ITEM_FOCUS_SCALE else 1f)
                    .scaleY(if (hasFocus) LIST_ITEM_FOCUS_SCALE else 1f)
                    .setDuration(NeonColors.FOCUS_MS)
                    .start()
            }
        }
    }

    /** A filled surface with the given corner radius, for dialogs, cards and the notes pane */
    fun surface(
        context: Context,
        color: Int,
        radiusDp: Float = if (neon) 0f else 16f,
    ): Drawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dpF(radiusDp)
        }

    /** tv-material3 `ListItemDefaults.scale` focused scale */
    const val LIST_ITEM_FOCUS_SCALE = 1.02f

    /** The Neon row focus glow spread (`NeonBoard.GlowSpec.RowFocus`) and strength (`ROW_FOCUS_ALPHA`) */
    const val ROW_GLOW_DP = 10f
    const val ROW_GLOW_ALPHA = .85f

    /** The old `DialogListEdge`: vertical room for the first and last rows' focus border and glow */
    const val LIST_EDGE_DP = 20
}

/**
 * The settings row background (`neonListItemColors/Border/Glow` on the WeaselTV theme, the stock tv-material3 list
 * item otherwise): transparent at rest; focused or pressed it is `chipOn(accent)` with a 1dp accent border and a
 * 10dp accent glow drawn outside the row (Neon, square corners), or `inverseSurface` with 8dp corners.
 *
 * The glow paints outside the view, so the list holding the rows must not clip its children.
 */
internal class SettingsRowBackground(
    context: Context,
    private val accent: Int,
) : Drawable() {
    private val neon = SettingsLook.neon
    private val density = context.resources.displayMetrics.density
    private val radius = if (neon) 0f else 8f * density
    private val border = ApprovedHomeLayout.CARD_APPEARANCE.borderWidthDp * density
    private val glowSpread = SettingsLook.ROW_GLOW_DP * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var active = false
    private var disabled = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val focused = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)
        val off = !state.contains(android.R.attr.state_enabled)
        val changed = focused != active || off != disabled
        active = focused
        disabled = off
        if (changed) invalidateSelf()
        return changed
    }

    override fun draw(canvas: Canvas) {
        if (!active) return
        rect.set(bounds)
        if (neon) {
            drawGlow(canvas)
            fill.color = NeonColors.chipOn(accent)
            canvas.drawRect(rect, fill)
            stroke.color = accent
            stroke.strokeWidth = border
            val half = border / 2f
            canvas.drawRect(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half, stroke)
        } else {
            fill.color = AppTheme.palette.inverseSurface
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
    }

    /** A soft glow: rings fading out over [glowSpread] around the row (the tv-material3 `Glow`) */
    private fun drawGlow(canvas: Canvas) {
        val glow = SettingsLook.glowFor(accent)
        val steps = (glowSpread / density).toInt().coerceAtLeast(1) * 2
        val step = glowSpread / steps
        stroke.strokeWidth = step + .5f
        for (i in 0 until steps) {
            val distance = step * (i + .5f)
            val strength = (1f - distance / glowSpread).coerceIn(0f, 1f)
            stroke.color = withAlpha(glow, SettingsLook.ROW_GLOW_ALPHA * strength * strength * .5f)
            canvas.drawRect(
                rect.left - distance,
                rect.top - distance,
                rect.right + distance,
                rect.bottom + distance,
                stroke,
            )
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The Neon section rule under a panel title (`NeonRule`): a 3dp accent line with a 10dp glow fading up into the
 * space above it. The view is 10dp tall with the line along its bottom; place it with a -7dp top margin so the line
 * sits where the old 3dp rule did. Draws nothing on the other themes.
 */
internal class SettingsRuleView(
    context: Context,
    private val accent: Int,
) : View(context) {
    private val linePaint = Paint().apply { color = accent }
    private val glowPaint = Paint()
    private val thickness = context.dpF(ApprovedHomeLayout.DIVIDER_THICKNESS_DP.toFloat())

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            context.dp(ApprovedHomeLayout.DIVIDER_GLOW_DP),
        )
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        val strength = ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f
        glowPaint.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                h.toFloat(),
                0x00000000,
                withAlpha(SettingsLook.glowFor(accent), strength),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDraw(canvas: Canvas) {
        if (!SettingsLook.neon) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        canvas.drawRect(0f, height - thickness, width.toFloat(), height.toFloat(), linePaint)
    }
}
