package com.github.damontecres.wholphin.leanback.widget.control

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.AppThemeColors

/**
 * A labelled switch where the whole row is focusable and clickable (`SwitchWithLabel`): a 20dp-rounded row with 8dp
 * padding, the surfaceVariant fill at 80 % (onSurface when focused), the label in labelLarge (onSurfaceVariant;
 * surfaceVariant when focused), 8dp, then a tv-material3 switch (40x24 track; 18dp handle on primary when checked,
 * 12dp border-coloured handle on surfaceVariant with a 1.5dp border outline when not).
 *
 * OK toggles it and calls [onStateChange] with the new value (when [isSwitchEnabled]).
 */
class SwitchWithLabelView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        val label: TextView = TextView(context).apply { applyTextRole(TextRole.LABEL_LARGE) }
        private val switch = TvSwitchView(context)
        private val fill = GradientDrawable().apply { cornerRadius = context.dpF(20f) }

        var checked: Boolean
            get() = switch.checked
            set(value) {
                switch.checked = value
            }

        var isSwitchEnabled: Boolean = true
            set(value) {
                field = value
                switch.enabledLook = value
            }

        /** Called with the new value when toggled */
        var onStateChange: ((Boolean) -> Unit)? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            background = fill
            val p = context.dp(8)
            setPadding(p, p, p, p)
            addView(label)
            addView(switch, LayoutParams(context.dp(40), context.dp(24)).apply { marginStart = context.dp(8) })
            setOnClickListener {
                if (isSwitchEnabled) onStateChange?.invoke(!checked)
            }
            refresh()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            @Suppress("SENSELESS_COMPARISON")
            if (fill != null) refresh()
        }

        private fun refresh() {
            val p = AppTheme.palette
            fill.setColor(if (isFocused) p.onSurface else withAlpha(p.surfaceVariant, .8f))
            label.setTextColor(if (isFocused) p.surfaceVariant else p.onSurfaceVariant)
            switch.invalidate()
        }
    }

/** The tv-material3 `Switch` drawing (40x24 track, 1.5dp outline, 18 / 12dp handle) */
class TvSwitchView(
    context: Context,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var checked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var enabledLook: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val p = AppTheme.palette
        val density = resources.displayMetrics.density
        val alpha = if (enabledLook) 1f else .38f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(if (checked) p.primary else p.surfaceVariant, alpha)
        canvas.drawRoundRect(rect, r, r, paint)
        if (!checked) {
            val w = 1.5f * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w
            paint.color = withAlpha(p.border, alpha)
            rect.inset(w / 2f, w / 2f)
            canvas.drawRoundRect(rect, r - w / 2f, r - w / 2f, paint)
        }
        paint.style = Paint.Style.FILL
        val handle = (if (checked) 18f else 12f) * density
        val cx = if (checked) width - height / 2f else height / 2f
        paint.color = withAlpha(if (checked) p.onPrimary else p.border, alpha)
        canvas.drawCircle(cx, height / 2f, handle / 2f, paint)
    }
}

/**
 * A TV control for choosing a value (`SliderBar`): a track 6dp tall (12dp while focused, animated) inset 4dp at the
 * sides, the filled part up to [value] between [min] and [max], and a white knob. LEFT / RIGHT change the value by
 * [interval] (wrapping around at the ends with [enableWrapAround]) and call [onChange] at once; OK calls [onChange]
 * with the current value. With [outlineColor] the track is a black pill with a 1dp outline.
 *
 * Colours per theme as before: the filled part is the border colour (the page accent on the Neon theme), the rest a
 * muted track (`line2` on the Neon theme).
 */
class SliderBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        var min: Long = 0
        var max: Long = 100
        var interval: Int = 1
        var enableWrapAround: Boolean = false

        /** The black pill look with this outline; null for the plain track */
        var outlineColor: Int? = null

        var onChange: ((Long) -> Unit)? = null

        var value: Long = 0
            set(v) {
                field = v
                invalidate()
            }

        private var trackHeight = context.dpF(6f)
        private var animator: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var accent = NeonColors.VOLT

        init {
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            accent = resolveNeonScope().accentOrDefault
            (parent as? android.view.ViewGroup)?.clipChildren = false
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), trackHeight.toInt().coerceAtLeast(1))
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            animator?.cancel()
            animator =
                ValueAnimator.ofFloat(trackHeight, context.dpF(if (gainFocus) 12f else 6f)).apply {
                    duration = 200
                    addUpdateListener {
                        trackHeight = it.animatedValue as Float
                        requestLayout()
                        invalidate()
                    }
                    start()
                }
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent,
        ): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    onChange?.invoke(value)
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    value = if (enableWrapAround && value <= min) max else (value - interval).coerceAtLeast(min)
                    onChange?.invoke(value)
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    value = if (enableWrapAround && value >= max) min else (value + interval).coerceAtMost(max)
                    onChange?.invoke(value)
                }

                else -> {
                    return super.onKeyDown(keyCode, event)
                }
            }
            return true
        }

        private fun activeColor(): Int {
            val p = AppTheme.palette
            return when (AppTheme.theme) {
                AppThemeColors.WEASELTV -> accent
                AppThemeColors.OLED_BLACK -> if (isFocused) p.primaryContainer else p.border
                else -> p.border
            }
        }

        private fun inactiveColor(): Int {
            val p = AppTheme.palette
            return when (AppTheme.theme) {
                AppThemeColors.UNRECOGNIZED, AppThemeColors.PURPLE -> {
                    withAlpha(compositeOver(withAlpha(p.border, .25f), p.surfaceVariant), .66f)
                }

                AppThemeColors.BLUE, AppThemeColors.GREEN, AppThemeColors.ORANGE, AppThemeColors.BOLD_BLUE,
                AppThemeColors.RED, AppThemeColors.BROWN,
                -> {
                    withAlpha(p.secondaryContainer, .66f)
                }

                AppThemeColors.OLED_BLACK -> {
                    if (isFocused) p.tertiaryContainer else p.primaryContainer
                }

                AppThemeColors.WEASELTV -> {
                    NeonColors.LINE2
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val inset = context.dpF(4f)
            val w = width - 2 * inset
            val h = height.toFloat()
            val percent = if (max == min) 0f else ((value - min).toFloat() / (max - min))
            val y = h / 2f
            canvas.save()
            canvas.translate(inset, 0f)
            val outline = outlineColor
            if (outline != null) {
                val r = h / 2f
                paint.style = Paint.Style.FILL
                paint.color = 0xFF000000.toInt()
                rect.set(0f, 0f, w, h)
                canvas.drawRoundRect(rect, r, r, paint)
                if (percent > 0f) {
                    paint.color = activeColor()
                    rect.set(0f, 0f, w * percent.coerceIn(0f, 1f), h)
                    canvas.drawRoundRect(rect, r, r, paint)
                }
                val stroke = context.dpF(1f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = outline
                rect.set(stroke / 2f, stroke / 2f, (w - stroke / 2f).coerceAtLeast(0f), (h - stroke / 2f).coerceAtLeast(0f))
                canvas.drawRoundRect(rect, r, r, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = h
                paint.color = inactiveColor()
                canvas.drawLine(0f, y, w, y, paint)
                paint.color = activeColor()
                canvas.drawLine(0f, y, w * percent, y, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(w * percent, y, h + 2, paint)
            canvas.restore()
        }
    }
