package com.github.damontecres.wholphin.leanback.widget.card

import android.graphics.Rect
import android.view.View
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ViewHolderTask
import androidx.recyclerview.widget.RecyclerView

/**
 * Clips a scrolling list to its own height while letting focus glow and scaled cards spill [extraPx] to the left and
 * right. Lists here turn `clipChildren` off (on themselves and their parents) so glows are not cut, which also let rows
 * scrolled out of the list draw over the header above it; the Compose lazy lists clipped their content to their
 * bounds. Uses [View.setClipBounds], which applies in the view's own frame (scroll positions don't move it).
 */
fun View.clipToOwnHeight(extraPx: Int = width) {
    val rect = Rect()

    fun apply(
        w: Int,
        h: Int,
    ) {
        val extra = if (extraPx > 0) extraPx else w
        rect.set(-extra, 0, w + extra, h)
        clipBounds = rect
    }
    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> apply(r - l, b - t) }
    if (isLaidOut) apply(width, height)
}

/**
 * Focus this grid's selected item: at once when it is laid out, otherwise as soon as Leanback has placed it (a page
 * that set the selected position and asks for focus in the same frame, eg a series page opening on episode 13).
 * Leanback's keep-visible focus strategy would otherwise focus the first fully visible item.
 *
 * @return false when there is no selected item to focus
 */
fun BaseGridView.requestSelectedFocus(
    direction: Int,
    previouslyFocusedRect: Rect?,
): Boolean {
    val selected = selectedPosition
    val count = adapter?.itemCount ?: 0
    if (selected !in 0 until count) return false
    val holder = findViewHolderForAdapterPosition(selected)
    if (holder != null) {
        return holder.itemView.isShown && holder.itemView.requestFocus(direction, previouslyFocusedRect)
    }
    setSelectedPosition(
        selected,
        object : ViewHolderTask {
            override fun run(viewHolder: RecyclerView.ViewHolder) {
                viewHolder.itemView.requestFocus()
            }
        },
    )
    return true
}
