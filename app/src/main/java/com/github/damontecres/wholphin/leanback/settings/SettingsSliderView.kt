package com.github.damontecres.wholphin.leanback.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.AppThemeColors

/**
 * The bar of a slider setting (the old `SliderBar` as the settings rows drew it): 6dp tall, 12dp while its row is
 * focused, inset 4dp, with a white knob at the value. On the WeaselTV theme it is a black pill outlined and filled in
 * ice white; other themes draw the theme's active and inactive tracks. The row handles the keys.
 */
class SettingsSliderView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var percent = 0f
        private var focusedState = false
        private var barHeight = 6f * density
        private var animator: ValueAnimator? = null

        /** The page accent (unused by the Neon recipe, which is ice white in settings) */
        var accent: Int = RailAccents.SETTINGS

        fun setValue(
            value: Long,
            min: Long,
            max: Long,
        ) {
            percent = if (max > min) ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
            invalidate()
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            val focused = drawableState.contains(android.R.attr.state_focused)
            if (focused != focusedState) {
                focusedState = focused
                animator?.cancel()
                animator =
                    ValueAnimator.ofFloat(barHeight, (if (focused) 12f else 6f) * density).apply {
                        duration = NeonColors.FOCUS_MS
                        addUpdateListener {
                            barHeight = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val inset = 4f * density
            val left = paddingLeft + inset
            val right = width - paddingRight - inset
            val width = (right - left).coerceAtLeast(0f)
            val cy = height / 2f
            val top = cy - barHeight / 2f
            val bottom = cy + barHeight / 2f
            val radius = barHeight / 2f
            val x = left + width * percent
            if (AppTheme.isNeon) {
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                rect.set(left, top, right, bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.color = NeonColors.ICE_WHITE
                rect.set(left, top, x, bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                rect.set(left + density / 2, top + density / 2, right - density / 2, bottom - density / 2)
                canvas.drawRoundRect(rect, radius, radius, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = barHeight
                paint.color = inactiveColor(focusedState)
                canvas.drawLine(left, cy, right, cy, paint)
                paint.color = activeColor(focusedState)
                canvas.drawLine(left, cy, x, cy, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, cy, barHeight + 2, paint)
        }

        /** `sliderActiveColor`: the filled side */
        private fun activeColor(focused: Boolean): Int {
            val palette = AppTheme.palette
            return when (AppTheme.theme) {
                AppThemeColors.OLED_BLACK -> if (focused) palette.primaryContainer else palette.border
                AppThemeColors.WEASELTV -> accent
                else -> palette.border
            }
        }

        /** `sliderInactiveColor`: the unfilled side */
        private fun inactiveColor(focused: Boolean): Int {
            val palette = AppTheme.palette
            return when (AppTheme.theme) {
                AppThemeColors.UNRECOGNIZED, AppThemeColors.PURPLE -> {
                    withAlpha(compositeOver(withAlpha(palette.border, .25f), palette.surfaceVariant), .66f)
                }

                AppThemeColors.OLED_BLACK -> {
                    if (focused) palette.tertiaryContainer else palette.primaryContainer
                }

                AppThemeColors.WEASELTV -> {
                    NeonColors.LINE2
                }

                else -> {
                    withAlpha(palette.secondaryContainer, .66f)
                }
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            super.onDetachedFromWindow()
        }
    }
