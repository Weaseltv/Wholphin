package com.github.damontecres.wholphin.leanback.setup

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Base of the setup-layer screens (server list, pinned sign-in, profile chooser): plain fragments the shell hosts in its
 * setup layer. Dialogs opened through [track] close with the screen's view.
 */
abstract class SetupFragment : Fragment() {
    private val dialogs = mutableSetOf<Dialog>()

    /** Collect while the view is at least STARTED, cancelled with the view */
    protected fun <T> Flow<T>.collectWhileStarted(block: suspend (T) -> Unit): Job =
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { block(it) }
            }
        }

    /** Shows [dialog] and closes it with the view; returns it */
    internal fun <D : Dialog> track(dialog: D): D {
        dialogs += dialog
        dialog.show()
        return dialog
    }

    /** Closes a dialog opened with [track] */
    internal fun close(dialog: Dialog?) {
        if (dialog == null) return
        dialogs -= dialog
        if (dialog.isShowing) dialog.dismiss()
    }

    override fun onDestroyView() {
        dialogs.toList().forEach { if (it.isShowing) it.dismiss() }
        dialogs.clear()
        super.onDestroyView()
    }
}

/**
 * The old `dimAndBlur`: the page behind a dialog at half opacity and blurred 16dp (the blur needs Android 12, as
 * Compose's did).
 */
internal fun View.dimAndBlur(enabled: Boolean) {
    alpha = if (enabled) .5f else 1f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val r = context.dpF(16f)
        setRenderEffect(if (enabled) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null)
    }
}

/** A role with the old `.copy(fontWeight = FontWeight.Bold)`: the display face is drawn bold as Compose synthesised it */
internal fun TextView.boldRole(
    role: TextRole,
    sizeSp: Float? = null,
) {
    applyTextRole(role, sizeSp)
    setTypeface(typeface, Typeface.BOLD)
}

/** The server/user tile size: Neon 120dp square, else `Cards.serverUserCircle` (three quarters of a 2:3 card) */
internal fun Context.serverUserTileSize(): Int {
    if (AppTheme.isNeon) return dp(120)
    val card = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.CARD_HEIGHT_DP else 172
    return dp(card * .75f)
}

/**
 * A horizontal row of tiles (the old `LazyRow` with `focusRestorer`): wraps its content so a short row centres in its
 * parent, scrolls only as far as the focused tile needs (`keepFocusedItemVisible`), and gives focus back to the tile
 * focused last when focus comes back into the row (the first tile the first time).
 */
internal class TileRow(
    context: Context,
    spacingDp: Float,
) : RecyclerView(context) {
    private var lastFocused = 0

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        itemAnimator = null
        clipChildren = false
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val spacing = context.dp(spacingDp)
        addItemDecoration(
            object : ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: State,
                ) {
                    if (parent.getChildAdapterPosition(view) > 0) outRect.left = spacing
                }
            },
        )
    }

    override fun requestChildFocus(
        child: View?,
        focused: View?,
    ) {
        super.requestChildFocus(child, focused)
        child?.let { getChildAdapterPosition(it).takeIf { p -> p != NO_POSITION }?.let { p -> lastFocused = p } }
    }

    private fun restoreTarget(): View? {
        val count = adapter?.itemCount ?: 0
        if (count == 0) return null
        val position = lastFocused.coerceIn(0, count - 1)
        return layoutManager?.findViewByPosition(position)
    }

    override fun addFocusables(
        views: ArrayList<View>,
        direction: Int,
        focusableMode: Int,
    ) {
        if (!hasFocus()) {
            val target = restoreTarget()
            if (target != null) {
                target.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?,
    ): Boolean = restoreTarget()?.requestFocus(direction, previouslyFocusedRect) ?: super.onRequestFocusInDescendants(direction, previouslyFocusedRect)

    /** Restarts focus memory at the first tile */
    fun resetFocusMemory() {
        lastFocused = 0
    }
}
