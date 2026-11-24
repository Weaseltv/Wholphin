package com.github.damontecres.wholphin.leanback.widget.card

import android.os.SystemClock
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * A held arrow key in the lists of cards. Holding DOWN in a library grid jumped around (owner's recording,
 * 2026-11-24), and rows and other lists scrolled the same way:
 * - A held key repeats about 20 times a second. Leanback answered every repeat with a new eased scroll from wherever the
 *   last one had got to, so the list surged and stalled and fell behind the focused card.
 * - When the next card was not laid out yet, Leanback seeked to it at its own speed and, after the key was let go, kept
 *   going for the moves it had queued.
 *
 * So while a key repeats:
 * - each scroll is linear and lasts until the next repeat is due ([SteadyScroll]): the steps join into one even motion;
 * - a repeat that comes before the list has caught up (the next card is not laid out yet, or a layout is pending) is
 *   skipped ([skipHeldRepeat]): the focused card stays in view, and a slow TV scrolls as fast as it can draw;
 * - lists lay out a quarter of their size beyond each edge ([steadyWhenHeld]), so the next card is ready before the last
 *   step ends.
 */
object HeldKeys {
    private const val MIN_GAP_MS = 25L
    private const val MAX_GAP_MS = 150L

    /** The gap between repeats of the held key (Android's default is 50 ms; a remote may differ) */
    private var gapMs = ViewConfiguration.getKeyRepeatDelay().toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    private var lastRepeatAt = 0L
    private var holding = false

    /** Every key event, before the views see it (`MainActivity.dispatchKeyEvent`) */
    fun onKeyEvent(event: KeyEvent) {
        if (!isArrowKey(event.keyCode)) return
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (holding) gapMs = (gapMs * 3 + (event.eventTime - lastRepeatAt).coerceIn(MIN_GAP_MS, MAX_GAP_MS)) / 4
            holding = true
            lastRepeatAt = event.eventTime
        } else {
            holding = false
        }
    }

    /** True while an arrow key is held down and repeating */
    val isHolding: Boolean
        get() = holding && SystemClock.uptimeMillis() - lastRepeatAt <= MAX_GAP_MS * 2

    /** One step of a held key: a little longer than the gap between repeats, so the steps overlap instead of pausing */
    val stepMs: Int get() = (gapMs * 5 / 4).toInt()

    internal fun isArrowKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
}

/** The scroll animation of a list: linear steps while a key is held ([HeldKeys]), otherwise [base] (or Leanback's own) */
class SteadyScroll(
    private val base: BaseGridView.SmoothScrollByBehavior? = null,
) : BaseGridView.SmoothScrollByBehavior {
    override fun configSmoothScrollByDuration(
        dx: Int,
        dy: Int,
    ): Int =
        if (HeldKeys.isHolding) {
            HeldKeys.stepMs
        } else {
            base?.configSmoothScrollByDuration(dx, dy) ?: RecyclerView.UNDEFINED_DURATION
        }

    override fun configSmoothScrollByInterpolator(
        dx: Int,
        dy: Int,
    ): Interpolator? = if (HeldKeys.isHolding) LINEAR else base?.configSmoothScrollByInterpolator(dx, dy)

    private companion object {
        val LINEAR = LinearInterpolator()
    }
}

/**
 * Steady scrolling while a key is held ([HeldKeys]): [SteadyScroll] around the list's own scroll animation, and
 * [extraLayout] (a fraction of the list's size) laid out beyond each edge: by default a quarter for a keep-visible list,
 * whose next item otherwise waits at the edge, and none for an aligned one, which already lays out the items around
 * the focused one. With [interceptKeys] the list skips the repeats it has not caught up with ([skipHeldRepeat]); a list
 * with its own key interceptor or key handling calls that itself.
 */
fun BaseGridView.steadyWhenHeld(
    interceptKeys: Boolean = true,
    extraLayout: () -> Float = { if (focusScrollStrategy == BaseGridView.FOCUS_SCROLL_ALIGNED) 0f else EXTRA_LAYOUT_FRACTION },
) {
    val current = smoothScrollByBehavior
    if (current !is SteadyScroll) smoothScrollByBehavior = SteadyScroll(current)
    if (interceptKeys) setOnKeyInterceptListener { skipHeldRepeat(it) }
    addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
        val grid = view as BaseGridView
        val extent = if (grid is HorizontalGridView) r - l else b - t
        if (extent <= 0) return@addOnLayoutChangeListener
        val extra = (extent * extraLayout()).roundToInt()
        // Setting it requests a layout: not from inside this one
        if (grid.extraLayoutSpace != extra) grid.post { grid.extraLayoutSpace = extra }
    }
}

/** A quarter of a list's size is laid out beyond each edge ([steadyWhenHeld]) */
const val EXTRA_LAYOUT_FRACTION = .25f

/**
 * True when [event] is a repeat of a held arrow key along this list that comes before the list has caught up with the
 * last step: a layout is pending, or it is still scrolling and the next item is not laid out yet. Consume it (see
 * [HeldKeys]). An idle list never skips, so a key is never lost for good.
 *
 * @param columns items per row of a vertical grid
 */
fun BaseGridView.skipHeldRepeat(
    event: KeyEvent,
    columns: Int = 1,
): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount == 0 || !hasFocus()) return false
    val horizontal = this is HorizontalGridView
    val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
    val step =
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (horizontal) return false else columns
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (horizontal) return false else -columns
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!horizontal) {
                    return false
                } else if (rtl) {
                    -1
                } else {
                    1
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!horizontal) {
                    return false
                } else if (rtl) {
                    1
                } else {
                    -1
                }
            }

            else -> {
                return false
            }
        }
    return isCatchingUp(selectedPosition, step)
}

/** True while this list has not caught up with a move from [from] by [step] items (see [skipHeldRepeat]) */
internal fun BaseGridView.isCatchingUp(
    from: Int,
    step: Int,
): Boolean {
    val count = adapter?.itemCount ?: 0
    if (from !in 0 until count) return false
    val next = (from + step).coerceIn(0, count - 1)
    if (next == from) return false
    if (isLayoutRequested) return true
    return scrollState != RecyclerView.SCROLL_STATE_IDLE && layoutManager?.findViewByPosition(next) == null
}

/**
 * Keeps up to [perType] scrolled-out views of each kind for re-use; RecyclerView keeps 5, less than a row of a library
 * grid. A held DOWN in Movies created a card for most new positions (63 in 4 seconds on the emulator), and the frames
 * that did took 100–280 ms, which showed as jumps. One pool can serve the strips of a page's rows, which then re-use
 * each other's cards instead of each creating its own; share it only between rows whose presenters make the same card
 * for each view type.
 */
class CardViewPool(
    private val perType: Int = DEFAULT_PER_TYPE,
) : RecyclerView.RecycledViewPool() {
    private val raised = SparseBooleanArray()

    override fun putRecycledView(scrap: RecyclerView.ViewHolder) {
        val type = scrap.itemViewType
        if (!raised.get(type)) {
            raised.put(type, true)
            setMaxRecycledViews(type, perType)
        }
        super.putRecycledView(scrap)
    }

    companion object {
        const val DEFAULT_PER_TYPE = 40
    }
}
