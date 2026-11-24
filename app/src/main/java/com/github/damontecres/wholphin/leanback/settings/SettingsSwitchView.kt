package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha

/**
 * The switch at the end of a settings row, drawn like the old tv-material3 `Switch` (no-Compose UI, 2026-11-22): a
 * 40 x 24dp pill track with, while off, a 1.5dp outline, and the round handle inside it, 12dp while off (centred in
 * the track's start end), 18dp while on (centred in its far end). Disabled, it is drawn at 38 % opacity.
 *
 * It is the row's `switchWidget`: SwitchPreferenceCompat drives it through [Checkable]; the row takes focus and
 * clicks. The colours per theme come from [styleSwitch].
 */
class SettingsSwitchView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        Checkable {
        private var checkedState = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val outlineWidth = context.dpF(OUTLINE_DP)

        /** Handle and track colours on and off, and the outline colour off ([styleSwitch]) */
        var thumbOn: Int = 0
        var thumbOff: Int = 0
        var trackOn: Int = 0
        var trackOff: Int = 0
        var outlineOff: Int = 0

        init {
            isFocusable = false
            isClickable = false
        }

        override fun setChecked(checked: Boolean) {
            if (checkedState == checked) return
            checkedState = checked
            invalidate()
        }

        override fun isChecked(): Boolean = checkedState

        override fun toggle() {
            setChecked(!checkedState)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            invalidate()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(
                resolveSize(context.dp(TRACK_WIDTH_DP), widthMeasureSpec),
                resolveSize(context.dp(TRACK_HEIGHT_DP), heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val alpha = if (isEnabled) 1f else DISABLED_ALPHA
            val trackWidth = context.dpF(TRACK_WIDTH_DP.toFloat()).coerceAtMost(width.toFloat())
            val trackHeight = context.dpF(TRACK_HEIGHT_DP.toFloat()).coerceAtMost(height.toFloat())
            val left = (width - trackWidth) / 2f
            val top = (height - trackHeight) / 2f
            rect.set(left, top, left + trackWidth, top + trackHeight)
            val radius = trackHeight / 2f
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(if (checkedState) trackOn else trackOff, alpha)
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (!checkedState) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outlineWidth
                paint.color = withAlpha(outlineOff, alpha)
                rect.inset(outlineWidth / 2f, outlineWidth / 2f)
                canvas.drawRoundRect(rect, radius - outlineWidth / 2f, radius - outlineWidth / 2f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(if (checkedState) thumbOn else thumbOff, alpha)
            val handle = context.dpF(if (checkedState) HANDLE_ON_DP else HANDLE_OFF_DP)
            val centerX = if (checkedState) left + trackWidth - radius else left + radius
            canvas.drawCircle(centerX, top + radius, handle / 2f, paint)
        }

        private companion object {
            /** The tv-material3 SwitchTokens */
            const val TRACK_WIDTH_DP = 40
            const val TRACK_HEIGHT_DP = 24
            const val OUTLINE_DP = 1.5f
            const val HANDLE_ON_DP = 18f
            const val HANDLE_OFF_DP = 12f
            const val DISABLED_ALPHA = .38f
        }
    }
