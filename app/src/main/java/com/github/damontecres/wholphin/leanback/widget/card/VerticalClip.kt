package com.github.damontecres.wholphin.leanback.widget.card

import android.graphics.Rect
import android.view.View
import androidx.core.view.doOnNextLayout
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.ViewHolderTask
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

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

/**
 * Select [position] without animation, scroll it into view, and then run [task] on its view.
 *
 * Leanback scrolls to a new selection while laying out only with the aligned focus strategy. A keep-visible list
 * (WeaselTV's grids and detail lists) laid out around a selection that was not laid out yet but left the scroll where it
 * was, so BACK to the first card, a letter or a page jump left the focus off screen until the next key. So for such a
 * jump a keep-visible list aligns (the item at the top of its content) and keeps its own strategy again once laid out.
 * An item already laid out is scrolled into view as usual.
 */
fun BaseGridView.jumpToPosition(
    position: Int,
    task: ViewHolderTask? = null,
) {
    // A scroll still running (a held key's last step) would carry on after the jump
    stopScroll()
    val laidOut = !isLayoutRequested && layoutManager?.findViewByPosition(position) != null
    if (!laidOut && (focusScrollStrategy != BaseGridView.FOCUS_SCROLL_ALIGNED || jumpRestores.containsKey(this))) {
        // A second jump before the first was laid out keeps the settings saved by the first
        val saved =
            jumpRestores.getOrPut(this) {
                JumpRestore(focusScrollStrategy, windowAlignment, windowAlignmentOffset, windowAlignmentOffsetPercent)
            }
        focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ALIGNED
        windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        windowAlignmentOffset = if (this is HorizontalGridView) paddingStart else paddingTop
        windowAlignmentOffsetPercent = 0f
        val jump = ++saved.jumps
        doOnNextLayout {
            // After this layout, not inside it: the setters request another layout. A later jump restores instead.
            post {
                if (jumpRestores[this] !== saved || saved.jumps != jump) return@post
                jumpRestores.remove(this)
                focusScrollStrategy = saved.strategy
                windowAlignment = saved.alignment
                windowAlignmentOffset = saved.offset
                windowAlignmentOffsetPercent = saved.percent
            }
        }
    }
    if (task != null) setSelectedPosition(position, task) else selectedPosition = position
}

/** A keep-visible list's settings while it is aligned for a jump ([jumpToPosition]) */
private class JumpRestore(
    val strategy: Int,
    val alignment: Int,
    val offset: Int,
    val percent: Float,
) {
    var jumps = 0
}

private val jumpRestores = WeakHashMap<BaseGridView, JumpRestore>()
