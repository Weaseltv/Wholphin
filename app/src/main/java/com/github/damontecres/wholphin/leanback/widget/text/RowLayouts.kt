package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.github.damontecres.wholphin.leanback.theme.dp
import kotlin.math.max

/**
 * A row whose children each get an equal share of the width but may be narrower (Compose
 * `Modifier.weight(1f, fill = false)`), placed one after another [spacingDp] apart, top aligned.
 */
class WrapWeightRow(
    context: Context,
    spacingDp: Float = 0f,
) : ViewGroup(context) {
    private val spacing = context.dp(spacingDp)

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val share =
            if (visible.isEmpty()) 0 else ((available - spacing * (visible.size - 1)) / visible.size).coerceAtLeast(0)
        var width = 0
        var height = 0
        visible.forEachIndexed { i, child ->
            val spec =
                if (unbounded) {
                    MeasureSpec.makeMeasureSpec(
                        0,
                        MeasureSpec.UNSPECIFIED,
                    )
                } else {
                    MeasureSpec.makeMeasureSpec(share, MeasureSpec.AT_MOST)
                }
            child.measure(spec, getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, ViewGroup.LayoutParams.WRAP_CONTENT))
            width += child.measuredWidth + if (i > 0) spacing else 0
            height = max(height, child.measuredHeight)
        }
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = if (rtl) r - l - paddingRight else paddingLeft
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (rtl) {
                child.layout(x - child.measuredWidth, paddingTop, x, paddingTop + child.measuredHeight)
                x -= child.measuredWidth + spacing
            } else {
                child.layout(x, paddingTop, x + child.measuredWidth, paddingTop + child.measuredHeight)
                x += child.measuredWidth + spacing
            }
        }
    }
}

/**
 * Compose `FlowRow`: children left to right, wrapping to a new line when the next one does not fit; each line as tall
 * as its tallest child, children top aligned. [horizontalSpacingDp] / [verticalSpacingDp] between children and lines.
 */
open class FlowRowLayout(
    context: Context,
    horizontalSpacingDp: Float = 0f,
    verticalSpacingDp: Float = 0f,
) : ViewGroup(context) {
    private val hSpacing = context.dp(horizontalSpacingDp)
    private val vSpacing = context.dp(verticalSpacingDp)

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val maxWidth =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                Int.MAX_VALUE
            } else {
                MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            }
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widest = 0
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(
                    if (maxWidth ==
                        Int.MAX_VALUE
                    ) {
                        0
                    } else {
                        maxWidth
                    },
                    if (maxWidth == Int.MAX_VALUE) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST,
                ),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val w = child.measuredWidth
            if (!first && lineWidth + hSpacing + w > maxWidth) {
                totalHeight += lineHeight + vSpacing
                widest = max(widest, lineWidth)
                lineWidth = w
                lineHeight = child.measuredHeight
            } else {
                lineWidth += if (first) w else hSpacing + w
                lineHeight = max(lineHeight, child.measuredHeight)
            }
            first = false
        }
        totalHeight += lineHeight
        widest = max(widest, lineWidth)
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val maxRight = r - l - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth
            if (!first && x + hSpacing + w > maxRight) {
                x = paddingLeft
                y += lineHeight + vSpacing
                lineHeight = 0
            } else if (!first) {
                x += hSpacing
            }
            child.layout(x, y, x + w, y + child.measuredHeight)
            x += w
            lineHeight = max(lineHeight, child.measuredHeight)
            first = false
        }
    }
}
