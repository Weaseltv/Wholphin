package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle

/**
 * A title that shrinks to fit (`FittedPanelTitle` and the WeaselPlex `TitleOrLogo` title): starting at its style's
 * size it steps down 1sp at a time, to 18sp at least, until the whole text fits in [maxLines] lines without breaking a
 * word; line height 1.25x. When even 18sp does not fit it ends with an ellipsis.
 *
 * Set the text with [setStyledText] so it knows the starting size; with [fit] false it behaves as a plain
 * [ControlTextStyle] text (stock themes did not fit their titles).
 */
open class FittedTitleView(
    context: Context,
    maxLines: Int = 3,
) : AppCompatTextView(context) {
    private var style: ControlTextStyle? = null
    private var baseSizeSp = 0f
    private var fittedFor: Pair<CharSequence, Int>? = null

    /** Shrink to fit; false keeps the style's size */
    var fit: Boolean = true
        set(value) {
            field = value
            fittedFor = null
            requestLayout()
        }

    init {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    fun setStyledText(
        text: CharSequence?,
        style: ControlTextStyle,
    ) {
        this.style = style
        applyTextStyle(style)
        baseSizeSp = textSize / resources.displayMetrics.scaledDensity
        fittedFor = null
        setText(text)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val text = text ?: ""
        if (fit && style != null && mode != MeasureSpec.UNSPECIFIED && available > 0 && fittedFor != (text to available)) {
            fittedFor = text to available
            val sp = fittedSize(text, available)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * 1.25f, resources.displayMetrics)
            setLineSpacing(px - paint.getFontMetricsInt(null), 1f)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fittedSize(
        text: CharSequence,
        width: Int,
    ): Float {
        val metrics = resources.displayMetrics
        val probe = TextPaint(paint)
        val start = baseSizeSp.toInt().coerceAtLeast(MIN_SP)
        for (sp in start downTo MIN_SP) {
            probe.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), metrics)
            val longestWord = text.split(' ', '\n').maxOfOrNull { Layout.getDesiredWidth(it, probe) } ?: 0f
            if (longestWord > width) continue
            val layout =
                if (Build.VERSION.SDK_INT >= 23) {
                    StaticLayout.Builder
                        .obtain(text, 0, text.length, probe, width)
                        .setIncludePad(includeFontPadding)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(text, probe, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, includeFontPadding)
                }
            if (layout.lineCount <= maxLines) return sp.toFloat()
        }
        return MIN_SP.toFloat()
    }

    companion object {
        const val MIN_SP = 18
    }
}
