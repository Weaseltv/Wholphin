package com.github.damontecres.wholphin.leanback.widget.card

import android.graphics.Rect
import android.view.View

/**
 * Clips a scrolling list to its own height while letting focus glow and scaled cards spill [extraPx] to the left and
 * right. Lists here turn `clipChildren` off (on themselves and their parents) so glows are not cut, which also let rows
 * scrolled out of the list draw over the header above it; the Compose lazy lists clipped their content to their
 * bounds. Uses [View.setClipBounds], which applies in the view's own frame (scroll positions don't move it).
 */
fun View.clipToOwnHeight(extraPx: Int = width) {
    val rect = Rect()
    fun apply(w: Int, h: Int) {
        val extra = if (extraPx > 0) extraPx else w
        rect.set(-extra, 0, w + extra, h)
        clipBounds = rect
    }
    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> apply(r - l, b - t) }
    if (isLaidOut) apply(width, height)
}
