package com.github.damontecres.wholphin.ui.util

import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Scroll only enough to reveal a clipped target. A visible parent card and its
 * inner focusable image must agree that no further repositioning is needed.
 */
object KeepVisibleBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val end = offset + size
        val distance =
            when {
                // An oversized target spanning both edges cannot be made fully visible.
                offset < 0f && end > containerSize -> 0f

                offset < 0f -> offset

                end > containerSize -> end - containerSize

                else -> 0f
            }
        // Layout rounds to pixels; do not keep animating a fractional remainder.
        return if (abs(distance) <= 1f) 0f else distance
    }
}
