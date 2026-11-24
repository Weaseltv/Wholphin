package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.github.damontecres.wholphin.leanback.theme.CardFocusLook
import com.github.damontecres.wholphin.leanback.theme.CardFocusMotion
import com.github.damontecres.wholphin.leanback.theme.CardFocusPainter

/**
 * The focusable surface of every card (the tv-material3 `Card`): it takes focus, clicks and long clicks (OK held),
 * scales to [CardFocusLook.focusedScale] on focus (300 ms in, 500 ms out) and [CardFocusLook.pressedScale] while OK is
 * down, and draws the focus glow behind and the focus border over its [content], which is clipped to the card shape
 * and filled with the container colour.
 *
 * Its parent must not clip children (the glow reaches past the card); [BaseCardView] and the rows/grids of this
 * package take care of that. While focused it is lifted above its caption with `translationZ` (no shadow: it has no
 * outline), as the focused Compose surface was drawn above its neighbours.
 *
 * Callers set [setOnClickListener] / [setOnLongClickListener]; cards listen to focus through [addFocusListener], so
 * [setOnFocusChangeListener] stays free for pages.
 */
open class CardFrameView(
    context: Context,
) : FrameLayout(context) {
    /** Card content: clipped to the card shape, background = the container colour */
    val content: FrameLayout = FrameLayout(context)

    var look: CardFocusLook =
        CardFocusLook(0f, false, 0f, false, 1f, 1f, 0f, 0, 0f, 0, 0)
        private set

    private val painter = CardFocusPainter()
    private val focusListeners = ArrayList<(Boolean) -> Unit>(2)
    private var wasPressed = false

    private val shapeOutline =
        object : ViewOutlineProvider() {
            override fun getOutline(
                view: View,
                outline: Outline,
            ) {
                if (look.circle) {
                    outline.setOval(0, 0, view.width, view.height)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, look.cornerRadiusPx)
                }
            }
        }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        outlineProvider = null
        content.outlineProvider = shapeOutline
        content.clipToOutline = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Apply a focus look (shape, container colour, scale, border, glow); re-applies the current focus state at once */
    fun setLook(look: CardFocusLook) {
        val shapeChanged = look.circle != this.look.circle || look.cornerRadiusPx != this.look.cornerRadiusPx
        this.look = look
        content.setBackgroundColor(look.containerColor)
        if (shapeChanged) content.invalidateOutline()
        animate().cancel()
        val scale = targetScale()
        scaleX = scale
        scaleY = scale
        invalidate()
    }

    /** Called with true/false when the card gains/loses focus (after the frame updated its own state) */
    fun addFocusListener(listener: (Boolean) -> Unit) {
        focusListeners += listener
    }

    private fun targetScale(): Float =
        when {
            isPressed && isFocused -> look.pressedScale
            isFocused -> look.focusedScale
            else -> 1f
        }

    private fun animateScale(durationMs: Long) {
        val scale = targetScale()
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(durationMs)
            .setInterpolator(CardFocusMotion.interpolator())
            .start()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        translationZ = if (gainFocus) 1f else 0f
        animateScale(if (gainFocus) CardFocusMotion.FOCUS_MS else CardFocusMotion.UNFOCUS_MS)
        invalidate()
        focusListeners.forEach { it(gainFocus) }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressed = isPressed
        if (pressed != wasPressed) {
            wasPressed = pressed
            animateScale(if (pressed) CardFocusMotion.PRESS_MS else CardFocusMotion.RELEASE_MS)
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val active = isFocused || isPressed
        val w = width.toFloat()
        val h = height.toFloat()
        if (active) painter.drawGlow(canvas, w, h, look)
        super.dispatchDraw(canvas)
        if (active) painter.drawBorder(canvas, w, h, look)
    }
}

/**
 * Base of every card View: a vertical column holding the focusable [frame] (the card itself) and, for cards with a
 * caption, the caption below it. Never clips, so the focused card's scale, border and glow show in full.
 */
abstract class BaseCardView(
    context: Context,
) : LinearLayout(context) {
    /** The focusable card surface: set click / long-click / focus listeners here (the row and grid adapters do) */
    val frame: CardFrameView = CardFrameView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(frame, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Set the frame (card) size in px */
    protected fun setFrameSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        val lp = frame.layoutParams as LayoutParams
        if (lp.width != widthPx || lp.height != heightPx) {
            lp.width = widthPx
            lp.height = heightPx
            frame.layoutParams = lp
        }
    }

    /** Called when the card view is recycled: cancel image loads and pending focus work */
    open fun recycle() {}
}
