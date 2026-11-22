package com.github.damontecres.wholphin.leanback.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import kotlin.math.ln
import kotlin.math.min

/*
 * Control recipes for the View UI (no-Compose rewrite, CONTROLS area): the clickable-surface, button, list-item,
 * drawer-item, chip, tally, rule and overline parts of `ui/theme/Neon.kt` as plain ARGB/dp values and Drawables.
 *
 * As in the Compose code, every recipe returns the stock tv-material3 look unless the WeaselTV theme is active
 * ([AppTheme.isNeon]). API reference: `runbooks/no-compose/WIDGETS-CONTROLS.md`.
 */

/** Neon Board sizes in dp (`NeonBoard.Size`) */
object NeonSizes {
    const val OVERSCAN_X = 48f
    const val OVERSCAN_Y = 27f
    const val RAIL_COLLAPSED = 72f
    const val RAIL_EXPANDED = 240f
    const val RAIL_ROW = 44f
    const val RAIL_GLYPH = 24f
    const val RAIL_AVATAR = 36f
    const val TALLY = 3f
    val RULE = ApprovedHomeLayout.DIVIDER_THICKNESS_DP.toFloat()
    const val PROGRESS_CARD = 3f
    const val BUTTON = 48f
    const val BUTTON_HERO = 52f
    const val CHIP = 36f
    const val TAB = 40f
    const val ROW_SETTINGS = 60f
    const val ROW_MENU = 52f
    const val USER_TILE = 120f
    const val DIALOG_WIDTH = 448f
    const val DIALOG_BUTTON_ROW = 52f
    const val OPTIONS_MENU_WIDTH = 340f
    const val SEEK_BAR = 4f
    const val SEEK_THUMB = 14f
    const val SCREENSAVER_MARK = 96f
}

/** Neon Board glow elevations (dp) and alphas (`NeonBoard.GlowSpec`) */
object NeonGlowSpec {
    const val CARD_FOCUS = 10f
    const val CARD_FOCUS_ALPHA = .85f
    const val ROW_FOCUS = 10f
    const val ROW_FOCUS_ALPHA = .85f
    const val PRIMARY_BUTTON = 20f
    const val PRIMARY_BUTTON_ALPHA = .4f
    const val PRIMARY_BUTTON_FOCUS = 10f
    const val PRIMARY_BUTTON_FOCUS_ALPHA = .85f
    val RULE = ApprovedHomeLayout.DIVIDER_GLOW_DP.toFloat()
    const val RULE_ALPHA = .8f
    const val TALLY = 12f
    const val BADGE = 12f
    const val BADGE_ALPHA = .3f
    const val BADGE_FILLED_ALPHA = .5f
    const val PROGRESS = 8f
    const val PROGRESS_ALPHA = .8f
    const val ICON = 7f
    const val ICON_ALPHA = .85f
}

/** tv-material3 `surfaceColorAtElevation`: the primary colour over the surface, more of it the higher the elevation */
fun AppPalette.surfaceAtElevation(elevationDp: Float): Int {
    if (elevationDp <= 0f) return surface
    val alpha = ((4.5f * ln(elevationDp + 1)) + 2f) / 100f
    return compositeOver(withAlpha(primary, alpha), surface)
}

/** Multiply [color]'s own alpha by [factor] (Compose `color.copy(alpha = color.alpha * factor)` is rarely wanted; see [withAlpha]) */
fun scaleAlpha(
    color: Int,
    factor: Float,
): Int = withAlpha(color, ((color ushr 24) / 255f) * factor)

// ---------------------------------------------------------------------------------------
// Surface states and styles
// ---------------------------------------------------------------------------------------

/** The interaction state of a control surface: tv-material3's enabled / focused / pressed / selected */
data class ControlState(
    val focused: Boolean = false,
    val pressed: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

/** A colour per state, resolved in tv-material3's order */
data class ControlColors(
    val rest: Int,
    val focused: Int = rest,
    val pressed: Int = focused,
    val disabled: Int = rest,
    val selected: Int = rest,
    val focusedSelected: Int = focused,
    val pressedSelected: Int = focusedSelected,
) {
    fun of(state: ControlState): Int =
        when {
            !state.enabled -> disabled
            state.selected && state.pressed -> pressedSelected
            state.selected && state.focused -> focusedSelected
            state.selected -> selected
            state.pressed -> pressed
            state.focused -> focused
            else -> rest
        }

    companion object {
        val TRANSPARENT = ControlColors(0)
    }
}

/** A border stroke drawn inside the bounds, as Compose `Modifier.border` */
data class ControlBorder(
    val widthDp: Float,
    val color: Int,
) {
    val isVisible: Boolean get() = widthDp > 0f && (color ushr 24) != 0

    companion object {
        val NONE = ControlBorder(0f, 0)
    }
}

data class ControlBorders(
    val rest: ControlBorder = ControlBorder.NONE,
    val focused: ControlBorder = rest,
    val pressed: ControlBorder = focused,
    val disabled: ControlBorder = rest,
    val focusedDisabled: ControlBorder = disabled,
    val selected: ControlBorder = rest,
    val focusedSelected: ControlBorder = focused,
    val pressedSelected: ControlBorder = focusedSelected,
) {
    fun of(state: ControlState): ControlBorder =
        when {
            !state.enabled -> if (state.focused) focusedDisabled else disabled
            state.selected && state.pressed -> pressedSelected
            state.selected && state.focused -> focusedSelected
            state.selected -> selected
            state.pressed -> pressed
            state.focused -> focused
            else -> rest
        }

    companion object {
        val NONE = ControlBorders()
    }
}

/** A tv-material3 `Glow`: a blurred shadow of the surface's shape in [color] with radius [radiusDp] */
data class ControlGlow(
    val radiusDp: Float,
    val color: Int,
) {
    val isVisible: Boolean get() = radiusDp > 0f && (color ushr 24) != 0

    companion object {
        val NONE = ControlGlow(0f, 0)
    }
}

data class ControlGlows(
    val rest: ControlGlow = ControlGlow.NONE,
    val focused: ControlGlow = rest,
    val pressed: ControlGlow = focused,
    val selected: ControlGlow = rest,
    val focusedSelected: ControlGlow = focused,
) {
    /** Disabled surfaces keep the rest glow, as tv-material3 did */
    fun of(state: ControlState): ControlGlow =
        when {
            !state.enabled -> rest
            state.selected && (state.focused || state.pressed) -> focusedSelected
            state.selected -> selected
            state.pressed -> pressed
            state.focused -> focused
            else -> rest
        }

    companion object {
        val NONE = ControlGlows()
    }
}

data class ControlScales(
    val rest: Float = 1f,
    val focused: Float = rest,
    val pressed: Float = rest,
    val disabled: Float = rest,
    val focusedDisabled: Float = disabled,
) {
    fun of(state: ControlState): Float =
        when {
            !state.enabled -> if (state.focused) focusedDisabled else disabled
            state.pressed -> pressed
            state.focused -> focused
            else -> rest
        }

    companion object {
        val NONE = ControlScales()

        /** `ClickableSurfaceDefaults.scale()`: every `Button` grows to 1.1 when focused, back to 1 while pressed */
        val BUTTON = ControlScales(focused = 1.1f)

        /** `ListItemDefaults.scale()`: rows grow to 1.05 when focused */
        val LIST_ITEM = ControlScales(focused = 1.05f)

        /** `neonSurfaceScale(focusedScale)`: tiles take 1.06, everything else 1; 0.97 while pressed */
        fun neonSurface(focusedScale: Float = 1f) = ControlScales(focused = focusedScale, pressed = .97f)
    }
}

/**
 * Everything a clickable surface draws: container and content colours, border, glow, focus scale and corner shape.
 * Copy one from [ControlStyles] and change what a call site changed in Compose.
 */
data class ControlSurfaceStyle(
    val container: ControlColors,
    val content: ControlColors,
    val border: ControlBorders = ControlBorders.NONE,
    val glow: ControlGlows = ControlGlows.NONE,
    val scale: ControlScales = ControlScales.NONE,
    /** Corner radius in dp; [CORNER_PILL] is a full circle / pill (Compose `CircleShape`) */
    val cornerDp: Float = 0f,
) {
    companion object {
        const val CORNER_PILL = Float.POSITIVE_INFINITY
    }
}

/**
 * Draws a [ControlSurfaceStyle] for the current [state]: the glow (outside the bounds, as tv-material3's
 * `Paint.setShadowLayer` glow), the container fill and the border (inside the bounds).
 *
 * The glow and the focus scale reach outside the view: its parent must have `clipChildren = false` (the controls in
 * this package switch it off on their direct parent when attached).
 */
class ControlSurfaceDrawable(
    context: Context,
    style: ControlSurfaceStyle,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val rect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.STROKE }

    var style: ControlSurfaceStyle = style
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    var state: ControlState = ControlState()
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    /** Extra tint drawn over the fill (eg the stock focused tab indicator); 0 for none */
    var overlayColor: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private fun corner(): Float {
        val c = style.cornerDp
        return if (c == ControlSurfaceStyle.CORNER_PILL) min(rect.width(), rect.height()) / 2f else c * density
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds)
        val radius = corner()
        val glow = style.glow.of(state)
        if (glow.isVisible) {
            glowPaint.color = withAlpha(glow.color, 0f)
            glowPaint.setShadowLayer(glow.radiusDp * density, 0f, 0f, glow.color)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
        }
        val fill = style.container.of(state)
        if ((fill ushr 24) != 0) {
            fillPaint.color = fill
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        if ((overlayColor ushr 24) != 0) {
            fillPaint.color = overlayColor
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        val border = style.border.of(state)
        if (border.isVisible) {
            val w = border.widthDp * density
            borderPaint.strokeWidth = w
            borderPaint.color = border.color
            rect.inset(w / 2f, w / 2f)
            val r = (radius - w / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, borderPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The tv-material3 surface scale motion: 300 ms to focus, 500 ms back, 120 ms pressed, ease-out cubic(0, 0, .2, 1) */
object ControlMotion {
    const val FOCUS_MS = 300L
    const val UNFOCUS_MS = 500L
    const val PRESSED_MS = 120L
    const val RELEASE_MS = 300L
    val EASING = PathInterpolator(0f, 0f, .2f, 1f)
}

/**
 * Animates a view's scale to the [ControlScales] value of each new [ControlState], with tv-material3's durations.
 * The pivot stays at the centre, as `graphicsLayer` scale did.
 */
class ControlScaleAnimator(
    private val view: View,
) {
    private var target = 1f
    private var lastState = ControlState()
    private var animator: ValueAnimator? = null

    fun update(
        scales: ControlScales,
        state: ControlState,
    ) {
        val next = scales.of(state)
        val previous = lastState
        lastState = state
        if (next == target) return
        target = next
        val duration =
            when {
                state.pressed && !previous.pressed -> ControlMotion.PRESSED_MS
                !state.pressed && previous.pressed -> ControlMotion.RELEASE_MS
                next > view.scaleX -> ControlMotion.FOCUS_MS
                else -> ControlMotion.UNFOCUS_MS
            }
        animator?.cancel()
        if (!view.isAttachedToWindow || !view.isShown) {
            view.scaleX = next
            view.scaleY = next
            return
        }
        val from = view.scaleX
        animator =
            ValueAnimator.ofFloat(from, next).apply {
                this.duration = duration
                interpolator = ControlMotion.EASING
                addUpdateListener {
                    val v = it.animatedValue as Float
                    view.scaleX = v
                    view.scaleY = v
                }
                start()
            }
    }

    /** Jump to the current target (eg when re-bound in a recycled row) */
    fun snap(
        scales: ControlScales,
        state: ControlState,
    ) {
        animator?.cancel()
        lastState = state
        target = scales.of(state)
        view.scaleX = target
        view.scaleY = target
    }
}

// ---------------------------------------------------------------------------------------
// Accent scope (LocalNeonAccent, LocalNeonOutlineRest, LocalNeonOutlineContainer)
// ---------------------------------------------------------------------------------------

/**
 * What the Compose tree provided to controls through `LocalNeonAccent`, `LocalNeonOutlineRest` and
 * `LocalNeonOutlineContainer`. Set it on a view with [provideNeonScope] (or [provideNeonAccent],
 * [provideButtonsOverArtwork]); controls inside resolve it with [resolveNeonScope] when they attach.
 */
data class NeonScope(
    /** `LocalNeonAccent`: the accent controls use (focus border, glow, label, tally) */
    val accent: Int? = null,
    /** `LocalNeonOutlineRest`: the rest outline of outline buttons, null keeps `line2` */
    val outlineRest: Int? = null,
    /** `LocalNeonOutlineContainer`: the rest fill of outline buttons, null keeps them transparent */
    val outlineContainer: Int? = null,
) {
    /** This scope with unset values taken from [outer] */
    fun orElse(outer: NeonScope): NeonScope =
        NeonScope(
            accent = accent ?: outer.accent,
            outlineRest = outlineRest ?: outer.outlineRest,
            outlineContainer = outlineContainer ?: outer.outlineContainer,
        )

    /** The accent, or volt (the Compose default) */
    val accentOrDefault: Int get() = accent ?: NeonColors.VOLT

    companion object {
        /** `ProvideButtonsOverArtwork`: a white outline and a dark backing so artwork doesn't show through labels */
        val OVER_ARTWORK = NeonScope(outlineRest = 0xFFFFFFFF.toInt(), outlineContainer = withAlpha(0xFF000000.toInt(), .8f))
    }
}

/** Set (merge) the [NeonScope] for every control inside this view */
fun View.provideNeonScope(scope: NeonScope) {
    val existing = getTag(R.id.lb_widget_ctl_neon_scope) as? NeonScope
    setTag(R.id.lb_widget_ctl_neon_scope, if (existing != null) scope.orElse(existing) else scope)
}

/** Compose `ProvideNeonAccent(accent)` for this subtree */
fun View.provideNeonAccent(accent: Int) = provideNeonScope(NeonScope(accent = accent))

/** Compose `ProvideButtonsOverArtwork` for this subtree (detail pages) */
fun View.provideButtonsOverArtwork() = provideNeonScope(NeonScope.OVER_ARTWORK)

/**
 * The [NeonScope] for this view: tags set with [provideNeonScope] on it and its ancestors (nearest wins), then the
 * section accent of the [DestinationFragment] that hosts it (`pageStyle.sectionAccent`, the old
 * `ProvideNeonSectionAccent`). Call it once the view is in its hierarchy (eg in `onAttachedToWindow`).
 */
fun View.resolveNeonScope(): NeonScope {
    var result = NeonScope()
    var v: View? = this
    while (v != null) {
        (v.getTag(R.id.lb_widget_ctl_neon_scope) as? NeonScope)?.let { result = result.orElse(it) }
        v = v.parent as? View
    }
    if (result.accent == null) {
        pageSectionAccent()?.let { result = result.copy(accent = it) }
    }
    return result
}

/** The section accent of the destination screen hosting this view, if any */
fun View.pageSectionAccent(): Int? {
    val fragment = runCatching { FragmentManager.findFragment<Fragment>(this) }.getOrNull() ?: return null
    return fragment.pageSectionAccent()
}

/** The section accent of this fragment's destination screen (itself or a parent fragment), if any */
fun Fragment.pageSectionAccent(): Int? {
    var f: Fragment? = this
    while (f != null) {
        if (f is DestinationFragment) return f.pageStyle.sectionAccent
        f = f.parentFragment
    }
    return null
}

/** Controls draw their glow and focus scale outside their bounds; let their direct parent show it */
fun View.allowDrawingOutsideParent() {
    (parent as? ViewGroup)?.let {
        it.clipChildren = false
        it.clipToPadding = false
    }
}

// ---------------------------------------------------------------------------------------
// Recipes
// ---------------------------------------------------------------------------------------

/**
 * The control recipes of `ui/theme/Neon.kt` and the tv-material3 defaults the old components used. Each takes the
 * accent (from [resolveNeonScope]) and returns the stock look off the WeaselTV theme.
 */
object ControlStyles {
    private val neon: Boolean get() = AppTheme.isNeon
    private val palette: AppPalette get() = AppTheme.palette

    /** The focused border of Neon surfaces: `ApprovedHomeLayout.CARD_APPEARANCE` width and opacity in the accent */
    private fun focusBorder(accent: Int): ControlBorder =
        ControlBorder(
            ApprovedHomeLayout.CARD_APPEARANCE.borderWidthDp.toFloat(),
            withAlpha(accent, ApprovedHomeLayout.CARD_APPEARANCE.borderOpacityPercent / 100f),
        )

    /** `neonSurfaceGlow(accent)`: row and button focus glow, 10dp at 85 % in the accent's glow colour */
    fun surfaceGlow(
        accent: Int,
        radiusDp: Float = NeonGlowSpec.ROW_FOCUS,
        alpha: Float = NeonGlowSpec.ROW_FOCUS_ALPHA,
    ): ControlGlows {
        if (!neon) return ControlGlows.NONE
        val glow = ControlGlow(radiusDp, withAlpha(neonGlowAccent(accent), alpha))
        return ControlGlows(focused = glow, pressed = glow)
    }

    /**
     * `neonSurfaceBorder(accent, restWidth, restColor)`: a [restWidthDp] hairline at rest (outline buttons and chips
     * take 1dp `line2`), the accent when focused.
     */
    fun surfaceBorder(
        accent: Int,
        restWidthDp: Float = 0f,
        restColor: Int = NeonColors.LINE2,
    ): ControlBorders {
        if (!neon) return ControlBorders.NONE
        val rest = if (restWidthDp > 0f) ControlBorder(restWidthDp, restColor) else ControlBorder.NONE
        val focused = focusBorder(accent)
        val disabled =
            if (restWidthDp > 0f) ControlBorder(restWidthDp, withAlpha(restColor, NeonColors.DISABLED_ALPHA)) else ControlBorder.NONE
        return ControlBorders(rest = rest, focused = focused, pressed = focused, disabled = disabled, focusedDisabled = focused)
    }

    /** `neonOutlineColors(accent)`: transparent (or [restContainer]) at rest, `chipOn` when focused; label `text` then accent */
    fun outlineColors(
        accent: Int,
        restContainer: Int = 0,
    ): Pair<ControlColors, ControlColors> {
        val chipOn = NeonColors.chipOn(accent)
        return ControlColors(rest = restContainer, focused = chipOn, pressed = chipOn, disabled = restContainer) to
            ControlColors(
                rest = NeonColors.TEXT,
                focused = accent,
                pressed = accent,
                disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
            )
    }

    /** The stock colours of the old `Button` (Button.kt): a translucent surfaceVariant pill, onSurface when focused */
    private fun stockButtonColors(): Pair<ControlColors, ControlColors> {
        val p = palette
        return ControlColors(
            rest = withAlpha(p.surfaceVariant, .8f),
            focused = p.onSurface,
            pressed = p.onSurface,
            disabled = withAlpha(p.surfaceVariant, .4f),
        ) to
            ControlColors(
                rest = withAlpha(p.onSurface, .8f),
                focused = p.inverseOnSurface,
                pressed = p.inverseOnSurface,
                disabled = withAlpha(p.onSurface, .4f),
            )
    }

    /**
     * The old `Button`'s defaults (Button.kt), used by every app button unless a variant below applies: on the
     * WeaselTV theme an outline square (1dp `line2` or [scope] outline at rest, accent border, `chipOn` fill and
     * accent label when focused, accent glow); elsewhere a translucent pill that turns onSurface when focused.
     * Both grow to 1.1 when focused.
     */
    fun button(scope: NeonScope): ControlSurfaceStyle {
        val accent = scope.accentOrDefault
        return if (neon) {
            val (container, content) = outlineColors(accent, scope.outlineContainer ?: 0)
            ControlSurfaceStyle(
                container = container,
                content = content,
                border = surfaceBorder(accent, 1f, scope.outlineRest ?: NeonColors.LINE2),
                glow = surfaceGlow(accent),
                scale = ControlScales.BUTTON,
                cornerDp = 0f,
            )
        } else {
            val (container, content) = stockButtonColors()
            ControlSurfaceStyle(
                container = container,
                content = content,
                border =
                    ControlBorders(
                        focusedDisabled = ControlBorder(1.5f, withAlpha(palette.onSurfaceVariant, .2f)),
                    ),
                glow = ControlGlows.NONE,
                scale = ControlScales.BUTTON,
                cornerDp = ControlSurfaceStyle.CORNER_PILL,
            )
        }
    }

    /**
     * An outline button in a specific colour (the destructive Confirm of the delete dialog: `neonOutlineColors(Red)`,
     * `neonSurfaceBorder(Red, 1dp)`, `neonSurfaceGlow(Red)`); stock themes keep [button].
     */
    fun outlineButton(
        accent: Int,
        scope: NeonScope = NeonScope(),
    ): ControlSurfaceStyle {
        if (!neon) return button(scope.copy(accent = accent))
        val (container, content) = outlineColors(accent, scope.outlineContainer ?: 0)
        return ControlSurfaceStyle(
            container = container,
            content = content,
            border = surfaceBorder(accent, 1f, scope.outlineRest ?: NeonColors.LINE2),
            glow = surfaceGlow(accent),
            scale = ControlScales.BUTTON,
        )
    }

    /**
     * The primary action (Play / Resume / Restart on detail pages): `neonPrimaryColors` accent fill with `onAccent` ink,
     * a 1dp `text` ring when focused (`neonPrimaryBorder`) and `neonPrimaryGlow` (20dp at 40 % at rest, 10dp at 85 %
     * focused). Off the WeaselTV theme it is the stock [button].
     */
    fun primaryButton(scope: NeonScope): ControlSurfaceStyle {
        if (!neon) return button(scope)
        val accent = scope.accentOrDefault
        val glowColor = neonGlowAccent(accent)
        val focusedRing = ControlBorder(ApprovedHomeLayout.CARD_APPEARANCE.borderWidthDp.toFloat(), NeonColors.TEXT)
        val focusedGlow = ControlGlow(NeonGlowSpec.PRIMARY_BUTTON_FOCUS, withAlpha(glowColor, NeonGlowSpec.PRIMARY_BUTTON_FOCUS_ALPHA))
        return ControlSurfaceStyle(
            container =
                ControlColors(
                    rest = accent,
                    focused = accent,
                    pressed = accent,
                    disabled = withAlpha(accent, NeonColors.DISABLED_ALPHA),
                ),
            content =
                ControlColors(
                    rest = NeonColors.ON_ACCENT,
                    focused = NeonColors.ON_ACCENT,
                    pressed = NeonColors.ON_ACCENT,
                    disabled = withAlpha(NeonColors.ON_ACCENT, NeonColors.DISABLED_ALPHA),
                ),
            border =
                ControlBorders(
                    focused = focusedRing,
                    pressed = focusedRing,
                    focusedDisabled = focusedRing,
                ),
            glow =
                ControlGlows(
                    rest = ControlGlow(NeonGlowSpec.PRIMARY_BUTTON, withAlpha(glowColor, NeonGlowSpec.PRIMARY_BUTTON_ALPHA)),
                    focused = focusedGlow,
                    pressed = focusedGlow,
                ),
            scale = ControlScales.BUTTON,
        )
    }

    /**
     * Glass buttons (icon-only over video, `neonGlassColors`): `glass` fill, `text` glyph, accent glyph when focused,
     * with the default button border and glow. Off the WeaselTV theme: [stockContainer] at rest, [stockFocusedContainer]
     * when focused (the player passed `TransparentBlack25` and its selected colour), stock tv-material3 content colours.
     */
    fun glassButton(
        scope: NeonScope,
        stockContainer: Int,
        stockFocusedContainer: Int,
    ): ControlSurfaceStyle {
        val accent = scope.accentOrDefault
        return if (neon) {
            ControlSurfaceStyle(
                container = ControlColors(NeonColors.GLASS),
                content =
                    ControlColors(
                        rest = NeonColors.TEXT,
                        focused = accent,
                        pressed = accent,
                        disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
                    ),
                border = surfaceBorder(accent, 1f, scope.outlineRest ?: NeonColors.LINE2),
                glow = surfaceGlow(accent),
                scale = ControlScales.BUTTON,
            )
        } else {
            val p = palette
            ControlSurfaceStyle(
                container = ControlColors(rest = stockContainer, focused = stockFocusedContainer, pressed = stockFocusedContainer),
                content =
                    ControlColors(
                        rest = p.onSurface,
                        focused = p.inverseOnSurface,
                        pressed = p.inverseOnSurface,
                        disabled = withAlpha(p.onSurface, .6f),
                    ),
                border =
                    ControlBorders(
                        focusedDisabled = ControlBorder(1.5f, withAlpha(p.onSurfaceVariant, .2f)),
                    ),
                scale = ControlScales.BUTTON,
                cornerDp = ControlSurfaceStyle.CORNER_PILL,
            )
        }
    }

    /**
     * A Neon tab (TabRow.kt `Tab`): the outline [button], its rest outline in the accent and its label in the accent
     * when [selected].
     */
    fun tab(
        scope: NeonScope,
        selected: Boolean,
    ): ControlSurfaceStyle {
        val base = button(scope)
        if (!neon || !selected) return base
        val accent = scope.accentOrDefault
        return base.copy(
            border = surfaceBorder(accent, 1f, accent),
            content = base.content.copy(rest = accent),
        )
    }

    /**
     * A chip (Neon Board: 36dp, Exo 2 14/600): an outline square whose selected state is the `chipOn` fill with an
     * accent label and outline. Off the WeaselTV theme it is the stock [button] with the stock selected colours.
     */
    fun chip(scope: NeonScope): ControlSurfaceStyle {
        val accent = scope.accentOrDefault
        val base = button(scope)
        if (!neon) {
            val p = palette
            return base.copy(
                container = base.container.copy(selected = p.secondaryContainer, focusedSelected = p.onSurface),
                content = base.content.copy(selected = p.onSecondaryContainer, focusedSelected = p.inverseOnSurface),
            )
        }
        val chipOn = NeonColors.chipOn(accent)
        return base.copy(
            container = base.container.copy(selected = chipOn, focusedSelected = chipOn),
            content = base.content.copy(selected = accent, focusedSelected = accent),
            border = base.border.copy(selected = ControlBorder(1f, accent), focusedSelected = focusBorder(accent)),
        )
    }

    /**
     * List rows in menus, dialogs and lists (`ListItem` with `neonListItemShape/Colors/Border/Glow`): transparent at
     * rest, `chipOn` and a 1dp accent border with the row glow when focused, accent label when selected. Stock:
     * transparent, inverseSurface when focused, secondaryContainer 40 % when selected, 8dp corners. Rows grow to 1.05.
     */
    fun listItem(
        scope: NeonScope,
        borderWidthDp: Float = ApprovedHomeLayout.CARD_APPEARANCE.borderWidthDp.toFloat(),
        borderOpacity: Float = 1f,
        glowRadiusDp: Float = NeonGlowSpec.ROW_FOCUS,
        glowAlpha: Float = NeonGlowSpec.ROW_FOCUS_ALPHA,
        cornerDp: Float = 0f,
    ): ControlSurfaceStyle {
        val accent = scope.accentOrDefault
        return if (neon) {
            val fill = NeonColors.chipOn(accent)
            val focused = ControlBorder(borderWidthDp, withAlpha(accent, borderOpacity))
            val glow = ControlGlow(glowRadiusDp, withAlpha(neonGlowAccent(accent), glowAlpha))
            ControlSurfaceStyle(
                container =
                    ControlColors(
                        rest = 0,
                        focused = fill,
                        pressed = fill,
                        disabled = 0,
                        selected = 0,
                        focusedSelected = fill,
                        pressedSelected = fill,
                    ),
                content =
                    ControlColors(
                        rest = NeonColors.TEXT,
                        focused = NeonColors.TEXT,
                        pressed = NeonColors.TEXT,
                        disabled = withAlpha(NeonColors.TEXT, NeonColors.DISABLED_ALPHA),
                        selected = accent,
                        focusedSelected = accent,
                        pressedSelected = accent,
                    ),
                border =
                    ControlBorders(
                        focused = focused,
                        pressed = focused,
                        focusedSelected = focused,
                        pressedSelected = focused,
                    ),
                glow = ControlGlows(focused = glow, pressed = glow, focusedSelected = glow),
                scale = ControlScales.LIST_ITEM,
                cornerDp = cornerDp,
            )
        } else {
            stockListItem()
        }
    }

    /** The tv-material3 `ListItemDefaults` look */
    fun stockListItem(): ControlSurfaceStyle {
        val p = palette
        return ControlSurfaceStyle(
            container =
                ControlColors(
                    rest = 0,
                    focused = p.inverseSurface,
                    pressed = p.inverseSurface,
                    disabled = 0,
                    selected = withAlpha(p.secondaryContainer, .4f),
                    focusedSelected = p.inverseSurface,
                    pressedSelected = p.inverseSurface,
                ),
            content =
                ControlColors(
                    rest = p.onSurface,
                    focused = p.inverseOnSurface,
                    pressed = p.inverseOnSurface,
                    disabled = p.onSurface,
                    selected = p.onSecondaryContainer,
                    focusedSelected = p.inverseOnSurface,
                    pressedSelected = p.inverseOnSurface,
                ),
            scale = ControlScales.LIST_ITEM,
            cornerDp = 8f,
        )
    }

    /**
     * Side-menu rows (`neonDrawerItemColors(accent)`): `stage` at rest, `chipOn` when focused; glyph and label `low` at
     * rest, `text` when focused, the accent when selected. Stock themes use the tv-material3 drawer item colours,
     * which the shell supplies through [stockContainer] and [stockContent].
     */
    fun drawerItem(
        accent: Int,
        stockContainer: ControlColors,
        stockContent: ControlColors,
    ): Pair<ControlColors, ControlColors> {
        if (!neon) return stockContainer to stockContent
        val fill = NeonColors.chipOn(accent)
        return ControlColors(
            rest = 0,
            focused = fill,
            pressed = fill,
            disabled = 0,
            selected = 0,
            focusedSelected = fill,
            pressedSelected = fill,
        ) to
            ControlColors(
                rest = NeonColors.LOW,
                focused = NeonColors.TEXT,
                pressed = NeonColors.TEXT,
                disabled = withAlpha(NeonColors.LOW, NeonColors.DISABLED_ALPHA),
                selected = accent,
                focusedSelected = accent,
                pressedSelected = accent,
            )
    }

    /**
     * Dropdown menu rows (TvDropdownMenuItem.kt): Neon `card` at rest, `chipOn` with a 1dp accent border when focused;
     * stock: the elevated surface, inverseSurface when focused.
     */
    fun dropdownItem(
        scope: NeonScope,
        elevationDp: Float = 3f,
    ): ControlSurfaceStyle {
        val p = palette
        return if (neon) {
            val accent = scope.accentOrDefault
            val focused = ControlBorder(1f, accent)
            ControlSurfaceStyle(
                container = ControlColors(rest = NeonColors.CARD, focused = NeonColors.chipOn(accent)),
                content = ControlColors(rest = p.onSurface, disabled = withAlpha(p.onSurface, .38f)),
                border = ControlBorders(focused = focused, pressed = focused, focusedDisabled = focused),
            )
        } else {
            ControlSurfaceStyle(
                container = ControlColors(rest = p.surfaceAtElevation(elevationDp), focused = p.inverseSurface),
                content =
                    ControlColors(
                        rest = p.onSurface,
                        focused = p.inverseOnSurface,
                        disabled = withAlpha(p.onSurface, .38f),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Tally, rule, overline, icon glow
// ---------------------------------------------------------------------------------------

/**
 * `Modifier.neonTally(accent)`: the selected / current marker, a 3dp bar on the leading edge in the accent with a 12dp
 * glow, drawn inside the bounds so nothing moves when it appears. Draw it over a view's content
 * (`view.overlay.add(drawable)` with matching bounds, or from `onDrawForeground`). Draws nothing off the WeaselTV theme.
 */
class NeonTallyDrawable(
    context: Context,
    accent: Int,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint()
    private val glowPaint = Paint()

    var accent: Int = accent
        set(value) {
            field = value
            invalidateSelf()
        }

    var enabled: Boolean = true
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (!enabled || !AppTheme.isNeon || bounds.isEmpty) return
        val left = bounds.left.toFloat()
        val glowW = NeonGlowSpec.TALLY * density
        glowPaint.shader =
            LinearGradient(
                left,
                0f,
                left + glowW,
                0f,
                withAlpha(neonGlowAccent(accent), .45f),
                0,
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(left, bounds.top.toFloat(), left + glowW, bounds.bottom.toFloat(), glowPaint)
        paint.color = accent
        canvas.drawRect(left, bounds.top.toFloat(), left + NeonSizes.TALLY * density, bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * `Modifier.neonRuleBelow(accent, thickness, glowHeight, glowStrength)`: a [thicknessDp] rule in the accent along the
 * bottom edge with a [glowHeightDp] glow fading up from it, inside the bounds. Draws nothing off the WeaselTV theme.
 */
class NeonRuleBelowDrawable(
    context: Context,
    accent: Int,
    private val thicknessDp: Float = NeonSizes.RULE,
    private val glowHeightDp: Float = NeonGlowSpec.RULE,
    private val glowStrength: Float = ApprovedHomeLayout.DIVIDER_GLOW_STRENGTH / 100f,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint()
    private val glowPaint = Paint()

    var accent: Int = accent
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (!AppTheme.isNeon || bounds.isEmpty) return
        val bottom = bounds.bottom.toFloat()
        val h = thicknessDp * density
        val glowH = glowHeightDp * density
        if (glowH > 0f && glowStrength > 0f) {
            glowPaint.shader =
                LinearGradient(
                    0f,
                    bottom - glowH,
                    0f,
                    bottom,
                    0,
                    withAlpha(neonGlowAccent(accent), glowStrength.coerceIn(0f, 1f)),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(bounds.left.toFloat(), bottom - glowH, bounds.right.toFloat(), bottom, glowPaint)
        }
        paint.color = accent
        canvas.drawRect(bounds.left.toFloat(), bottom - h, bounds.right.toFloat(), bottom, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * `Modifier.neonOverline(accent)`: the selected tab's 2dp overline in the accent with its glow fading down, along the
 * top edge inside the bounds. Draws nothing off the WeaselTV theme.
 */
class NeonOverlineDrawable(
    context: Context,
    accent: Int,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint()
    private val glowPaint = Paint()

    var accent: Int = accent
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (!AppTheme.isNeon || bounds.isEmpty) return
        val top = bounds.top.toFloat()
        val glowH = NeonGlowSpec.RULE * density
        glowPaint.shader =
            LinearGradient(0f, top, 0f, top + glowH, withAlpha(neonGlowAccent(accent), .35f), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds.left.toFloat(), top, bounds.right.toFloat(), top + glowH, glowPaint)
        paint.color = accent
        canvas.drawRect(bounds.left.toFloat(), top, bounds.right.toFloat(), top + 2f * density, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * `Modifier.neonIconGlow(accent)`: the soft radial glow behind a selected rail glyph (no blur filter). Draws nothing
 * off the WeaselTV theme or when not [enabled].
 */
class NeonIconGlowDrawable(
    accent: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var accent: Int = accent
        set(value) {
            field = value
            invalidateSelf()
        }

    var enabled: Boolean = true
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (!enabled || !AppTheme.isNeon || bounds.isEmpty) return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = min(bounds.width(), bounds.height()) * .95f
        val glow = neonGlowAccent(accent)
        paint.shader =
            android.graphics.RadialGradient(
                cx,
                cy,
                r,
                intArrayOf(withAlpha(glow, NeonGlowSpec.ICON_ALPHA * .5f), withAlpha(glow, .12f), 0),
                null,
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** `Modifier.neonFocusFill(accent, focused)`: the `chipOn` fill for rows that are not control surfaces (Neon only) */
fun neonFocusFill(
    accent: Int,
    focused: Boolean,
): Int = if (focused && AppTheme.isNeon) NeonColors.chipOn(accent) else 0

// ---------------------------------------------------------------------------------------
// Requests activity palette
// ---------------------------------------------------------------------------------------

/**
 * Colours of the Requests ACTIVITY page (owner mockup 2026-11-03; `ui/theme/RequestActivityPalette.kt`). Each role
 * reuses the section colour with the same value; Waiting has its own.
 */
object RequestActivityPalette {
    /** ACTIVE section: the Search glow (#00A3FF) */
    val ACTIVE = NeonSections.Search.glow

    /** WAITING section (#FFC400) */
    const val WAITING = 0xFFFFC400.toInt()

    /** READY TO WATCH section: the Home glow (#00E04B) */
    val READY = NeonSections.Home.glow

    /** "Movie" label, bar and percentage: the Movies glow (#00E1FF) */
    val MOVIE = NeonSections.Movies.glow

    /** "Series" label, bar and percentage: the TV Shows glow (#FF1A1A) */
    val SERIES = NeonSections.TvShows.glow

    /** Progress bar track */
    const val TRACK = NeonColors.LINE

    /** Row dividers and the rule after each section heading */
    const val DIVIDER = NeonColors.LINE

    /** Column headings, years, secondary lines and legend notes */
    const val MUTED = NeonColors.MID

    /** Empty-state circle border, glow and icon */
    val EMPTY_BORDER = NeonSections.Requests.border
    val EMPTY_GLOW = NeonSections.Requests.glow
}
