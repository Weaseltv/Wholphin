package com.github.damontecres.wholphin.leanback.widget.control

import android.os.Build
import android.view.View
import com.github.damontecres.wholphin.leanback.theme.ControlScaleAnimator
import com.github.damontecres.wholphin.leanback.theme.ControlState
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceDrawable
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.NeonScope
import com.github.damontecres.wholphin.leanback.theme.allowDrawingOutsideParent
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope

/**
 * The shared focus machinery of every clickable control in this package (the tv-material3 `Surface` they replace):
 * it owns the [ControlSurfaceDrawable] background, resolves the [NeonScope] (accent, outline colours) when the view
 * attaches, turns the view's focused / pressed state plus [actionEnabled] / [selected] into a [ControlState], and
 * animates the focus scale.
 *
 * Disabled controls stay focusable, as tv-material3 surfaces did (they have a "focused disabled" look); only
 * clicks are ignored. Use [actionEnabled] for that, never `View.setEnabled(false)`, which removes the view from
 * D-pad focus.
 *
 * @param styleFor builds the style for the resolved scope; called again by [rebuild]
 */
class SurfaceController(
    private val view: View,
    private var styleFor: (NeonScope) -> ControlSurfaceStyle,
) {
    val drawable = ControlSurfaceDrawable(view.context, styleFor(NeonScope()))
    private val scaler = ControlScaleAnimator(view)

    /** Scope set in code; overrides what the view tree provides */
    var explicitScope: NeonScope? = null
        set(value) {
            field = value
            rebuild()
        }

    /** The scope in use: [explicitScope] over the view tree's ([resolveNeonScope]) */
    var scope: NeonScope = NeonScope()
        private set

    var style: ControlSurfaceStyle = drawable.style
        private set

    /** False: drawn disabled, clicks ignored, still focusable */
    var actionEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    var selected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** Called with the content colour of every new state (labels, icons) */
    var onContentColor: ((Int) -> Unit)? = null

    /** Called after every state change (eg to show a label only while focused) */
    var onStateChanged: ((ControlState) -> Unit)? = null

    var state: ControlState = ControlState()
        private set

    init {
        view.background = drawable
        view.isFocusable = true
        view.isClickable = true
        view.isSoundEffectsEnabled = false
        if (Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
    }

    /** Replace how the style is built (eg a selected tab) and apply it */
    fun setStyleFor(builder: (NeonScope) -> ControlSurfaceStyle) {
        styleFor = builder
        rebuild()
    }

    /** Call from `onAttachedToWindow`: resolves the scope from the view tree and lets the parent show the glow */
    fun onAttached() {
        view.allowDrawingOutsideParent()
        rebuild()
        scaler.snap(style.scale, state)
    }

    /** Re-resolve the scope and rebuild the style (after the page accent or the theme changed) */
    fun rebuild() {
        val treeScope = if (view.isAttachedToWindow) view.resolveNeonScope() else NeonScope()
        scope = explicitScope?.orElse(treeScope) ?: treeScope
        style = styleFor(scope)
        drawable.style = style
        refresh()
    }

    /** Call from `drawableStateChanged` */
    fun refresh() {
        val next =
            ControlState(
                focused = view.isFocused,
                pressed = view.isPressed,
                enabled = actionEnabled,
                selected = selected,
            )
        val changed = next != state
        state = next
        drawable.state = next
        scaler.update(style.scale, next)
        onContentColor?.invoke(style.content.of(next))
        if (changed) onStateChanged?.invoke(next)
    }

    /** The current content colour */
    val contentColor: Int get() = style.content.of(state)
}
