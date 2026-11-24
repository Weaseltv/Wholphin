package com.github.damontecres.wholphin.leanback.widget.control

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlMotion
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.NeonScope
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp

/** The look of an [AppButton] (which recipe of `ControlStyles` it wears) */
enum class ButtonVariant {
    /** The old `Button` default: outline square in Neon, translucent pill elsewhere */
    DEFAULT,

    /** Play / Resume / Restart on detail pages: the accent fill (`neonPrimaryColors/Border/Glow`) */
    PRIMARY,

    /** Icon buttons over video: the `glass` fill (`neonGlassColors`) */
    GLASS,

    /** A selectable chip; [AppButton.isChipSelected] fills it */
    CHIP,
}

/**
 * The app's button (Button.kt / `TextButton` as a View): a focusable, clickable row of an optional icon or Font
 * Awesome glyph and a label, on a [SurfaceController] surface.
 *
 * Defaults are the old `Button`'s: 4dp content padding around a 40dp content row (48dp tall), the Neon outline
 * recipe on the WeaselTV theme (a minimum height, so wrapped labels grow it) and the stock translucent pill elsewhere
 * (an exact height), label in the button type (Exo 2 16/700, 0.06em; labelLarge off-theme), focus scale 1.1.
 * [text] makes the old `TextButton` (8/4dp padding, 32dp content). Labels are not uppercased for you; pass
 * `allCaps = true` where the old code uppercased.
 *
 * Focus: the view itself takes focus (its children never do). D-pad centre presses it (scale back to 1), release
 * clicks, holding it long-clicks. A disabled button ([isActionEnabled] false) stays focusable and ignores clicks.
 * The accent comes from the nearest `provideNeonAccent` / page section accent unless [accent] is set.
 */
open class AppButton
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        val iconView: ImageView =
            ImageView(context).apply {
                id = R.id.lb_widget_ctl_button_icon
                visibility = View.GONE
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

        /** A Font Awesome glyph centred in a 32dp box (ExpandableFaButton) */
        val glyphView: TextView =
            TextView(context).apply {
                visibility = View.GONE
                gravity = Gravity.CENTER
                includeFontPadding = false
            }

        val labelView: TextView =
            TextView(context).apply {
                id = R.id.lb_widget_ctl_button_label
                gravity = Gravity.CENTER
                applyTextStyle(NeonType.button())
            }

        private val labelHolder = ExpandingFrame(context).apply { addView(labelView) }

        private var variantValue: ButtonVariant = ButtonVariant.DEFAULT
        private var styleOverride: ((NeonScope) -> ControlSurfaceStyle)? = null

        /** The focus machinery; use it for the state or to set a custom style */
        val surface = SurfaceController(this) { scope -> styleFor(scope) }

        private var glyphColor: Int? = null
        private var iconTint: Int? = null

        /** Content height in dp (the old `contentHeight`, `MinButtonSize` 40dp by default) */
        var contentHeightDp: Float = 40f
            set(value) {
                field = value
                requestLayout()
            }

        /**
         * Neon used `heightIn(min = contentHeight)` so long labels grow the button; other themes a fixed
         * `height(contentHeight)`. Null keeps that per-theme behaviour.
         */
        var exactContentHeight: Boolean? = null
            set(value) {
                field = value
                requestLayout()
            }

        /** Show the label only while focused, expanding the button (the `collapsed` expandable buttons) */
        var labelOnlyWhenFocused: Boolean = false
            set(value) {
                field = value
                labelHolder.setFraction(if (!value || isFocused) 1f else 0f, animate = false)
            }

        /** Called with the content colour of each state, for custom children added to the button */
        var onContentColor: ((Int) -> Unit)? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            isLongClickable = false
            addView(iconView, LayoutParams(context.dp(24), context.dp(24)))
            addView(glyphView, LayoutParams(context.dp(32), context.dp(32)))
            addView(labelHolder, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setContentPaddingDp(4f, 4f, 4f, 4f)
            surface.onContentColor = { applyContentColor(it) }
            surface.onStateChanged = { state ->
                if (labelOnlyWhenFocused) labelHolder.setFraction(if (state.focused) 1f else 0f, animate = true)
            }
        }

        /** The recipe; also resets a custom style */
        var variant: ButtonVariant
            get() = variantValue
            set(value) {
                variantValue = value
                styleOverride = null
                surface.rebuild()
            }

        /** Use a custom style built from the resolved scope (eg `ControlStyles.outlineButton(NeonColors.RED)`) */
        fun setSurfaceStyle(builder: (NeonScope) -> ControlSurfaceStyle) {
            styleOverride = builder
            surface.rebuild()
        }

        private fun styleFor(scope: NeonScope): ControlSurfaceStyle =
            styleOverride?.invoke(scope) ?: when (variantValue) {
                ButtonVariant.DEFAULT -> {
                    ControlStyles.button(scope)
                }

                ButtonVariant.PRIMARY -> {
                    ControlStyles.primaryButton(scope)
                }

                ButtonVariant.GLASS -> {
                    val stock = AppTheme.palette
                    ControlStyles.glassButton(scope, 0x40000000, stock.onSurface)
                }

                ButtonVariant.CHIP -> {
                    ControlStyles.chip(scope)
                }
            }

        /** An explicit accent (the old `LocalNeonAccent` at the call site); null takes the view tree's */
        var accent: Int?
            get() = surface.explicitScope?.accent
            set(value) {
                surface.explicitScope = (surface.explicitScope ?: NeonScope()).copy(accent = value)
            }

        /** Explicit scope values (accent, outline colours); null fields come from the view tree */
        var neonScope: NeonScope?
            get() = surface.explicitScope
            set(value) {
                surface.explicitScope = value
            }

        /** False: drawn disabled and ignores clicks, but stays focusable (tv-material3) */
        var isActionEnabled: Boolean
            get() = surface.actionEnabled
            set(value) {
                surface.actionEnabled = value
            }

        /** The selected state of a [ButtonVariant.CHIP] */
        var isChipSelected: Boolean
            get() = surface.selected
            set(value) {
                surface.selected = value
            }

        /** Inner padding around the content row in dp (the old `contentPadding`) */
        fun setContentPaddingDp(
            start: Float,
            top: Float,
            end: Float,
            bottom: Float,
        ) {
            setPaddingRelative(context.dp(start), context.dp(top), context.dp(end), context.dp(bottom))
        }

        fun setLabel(
            text: CharSequence?,
            allCaps: Boolean = false,
        ) {
            labelView.text = if (allCaps) text?.toString()?.uppercase() else text
            labelHolder.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun setLabel(
            @StringRes text: Int,
            allCaps: Boolean = false,
        ) = setLabel(context.getString(text), allCaps)

        /** Padding around the label in dp (the old `Modifier.padding` on the button's Text) */
        fun setLabelPaddingDp(
            start: Float,
            end: Float,
        ) {
            labelView.setPaddingRelative(context.dp(start), 0, context.dp(end), 0)
        }

        /** A drawable icon (a tv-material3 `Icon`, 24dp, tinted with the content colour unless [tint] is set) */
        fun setIcon(
            @DrawableRes res: Int,
            tint: Int? = null,
            sizeDp: Float = 24f,
        ) {
            iconView.setImageResource(res)
            iconTint = tint
            iconView.visibility = View.VISIBLE
            iconView.layoutParams =
                (iconView.layoutParams as LayoutParams).apply {
                    width = context.dp(sizeDp)
                    height = context.dp(sizeDp)
                }
            applyContentColor(surface.contentColor)
        }

        /** A Font Awesome glyph (ExpandableFaButton: 16sp in a 32dp box), in the content colour unless [color] is set */
        fun setGlyph(
            @StringRes glyph: Int,
            color: Int? = null,
            sizeSp: Float = 16f,
            boxDp: Float = 32f,
        ) {
            glyphView.typeface = AppFonts.icons(context)
            glyphView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            glyphView.setText(glyph)
            glyphColor = color
            glyphView.visibility = View.VISIBLE
            glyphView.layoutParams =
                (glyphView.layoutParams as LayoutParams).apply {
                    width = context.dp(boxDp)
                    height = context.dp(boxDp)
                }
            applyContentColor(surface.contentColor)
        }

        fun clearIcon() {
            iconView.visibility = View.GONE
            glyphView.visibility = View.GONE
        }

        private fun applyContentColor(color: Int) {
            labelView.setTextColor(color)
            iconView.imageTintList = ColorStateList.valueOf(iconTint ?: color)
            glyphView.setTextColor(glyphColor ?: color)
            onContentColor?.invoke(color)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            // Null while the View constructor runs, before this class's fields are set
            @Suppress("SENSELESS_COMPARISON")
            if (surface != null) surface.refresh()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            surface.onAttached()
            if (labelOnlyWhenFocused) labelHolder.setFraction(if (isFocused) 1f else 0f, animate = false)
        }

        override fun performClick(): Boolean {
            if (!surface.actionEnabled) return false
            return super.performClick()
        }

        override fun performLongClick(): Boolean {
            if (!surface.actionEnabled) return false
            return super.performLongClick()
        }

        override fun setOnLongClickListener(l: OnLongClickListener?) {
            super.setOnLongClickListener(l)
            isLongClickable = l != null
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val content = context.dp(contentHeightDp) + paddingTop + paddingBottom
            val exact = exactContentHeight ?: !AppTheme.isNeon
            if (exact && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(content, MeasureSpec.EXACTLY))
            } else {
                minimumHeight = content
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        companion object {
            /** The old `TextButton(stringRes)`: 8dp side / 4dp vertical padding, 32dp content */
            fun text(
                context: Context,
                @StringRes label: Int,
                onClick: () -> Unit,
            ): AppButton = text(context, context.getString(label), onClick)

            fun text(
                context: Context,
                label: CharSequence,
                onClick: () -> Unit,
            ): AppButton =
                AppButton(context).apply {
                    setContentPaddingDp(8f, 4f, 8f, 4f)
                    contentHeightDp = 32f
                    setLabel(label)
                    setOnClickListener { onClick() }
                }
        }
    }

/**
 * Holds the button label at its natural width and reports [fraction] of it, clipping the rest: the
 * `AnimatedVisibility` expand / shrink of labels shown only while focused.
 */
internal class ExpandingFrame(
    context: Context,
) : FrameLayout(context) {
    private var fraction = 1f
    private var animator: ValueAnimator? = null

    init {
        clipChildren = true
        clipToPadding = true
    }

    fun setFraction(
        value: Float,
        animate: Boolean,
    ) {
        animator?.cancel()
        if (!animate || !isAttachedToWindow) {
            apply(value)
            return
        }
        animator =
            ValueAnimator.ofFloat(fraction, value).apply {
                duration = ControlMotion.FOCUS_MS
                interpolator = ControlMotion.EASING
                addUpdateListener { apply(it.animatedValue as Float) }
                start()
            }
    }

    private fun apply(value: Float) {
        fraction = value
        alpha = value
        requestLayout()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (fraction >= 1f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val child = getChildAt(0)
        if (child == null || fraction <= 0f) {
            setMeasuredDimension(0, 0)
            child?.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
            return
        }
        child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        setMeasuredDimension((child.measuredWidth * fraction).toInt(), child.measuredHeight)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val child = getChildAt(0) ?: return
        val childTop = ((bottom - top) - child.measuredHeight) / 2
        child.layout(0, childTop, child.measuredWidth, childTop + child.measuredHeight)
    }
}
