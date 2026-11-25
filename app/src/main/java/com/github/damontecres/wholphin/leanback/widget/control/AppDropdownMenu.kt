package com.github.damontecres.wholphin.leanback.widget.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonScope
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.provideNeonScope
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import kotlin.math.ceil

/**
 * A row of a dropdown menu (`TvDropdownMenuItem`, a Material3 `DropdownMenuItem`): optional leading icon, the text
 * (labelLarge) and optional trailing icon or view, 12dp side padding, at least 48dp tall, 112–280dp wide. Neon: `card`
 * at rest, `chipOn` with a 1dp section-accent border when focused; stock: the 3dp elevated surface, inverseSurface
 * with inverseOnSurface text when focused.
 *
 * Focus: the row itself. Disabled rows ([isActionEnabled] false) stay focusable and ignore clicks.
 */
class DropdownMenuItemView(
    context: Context,
    elevationDp: Float = 3f,
) : LinearLayout(context) {
    val textView: TextView = TextView(context).apply { applyTextRole(TextRole.LABEL_LARGE) }
    private val leading = ImageView(context).apply { visibility = View.GONE }
    private val trailingSlot = FrameLayout(context).apply { visibility = View.GONE }
    private var leadingTint: Int? = null

    val surface = SurfaceController(this) { scope -> ControlStyles.dropdownItem(scope, elevationDp) }

    var isActionEnabled: Boolean
        get() = surface.actionEnabled
        set(value) {
            surface.actionEnabled = value
        }

    /** Called with the content colour of each state, for a custom trailing view */
    var onContentColor: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        minimumHeight = context.dp(48)
        minimumWidth = context.dp(112)
        val pad = context.dp(12)
        setPaddingRelative(pad, 0, pad, 0)
        addView(leading, LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(12) })
        addView(textView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(trailingSlot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12) })
        surface.onContentColor = { color ->
            textView.setTextColor(color)
            leading.imageTintList = ColorStateList.valueOf(leadingTint ?: color)
            onContentColor?.invoke(color)
        }
    }

    fun setText(text: CharSequence) {
        textView.text = text
    }

    fun setLeadingIcon(
        @DrawableRes res: Int?,
        tint: Int? = null,
    ) {
        leadingTint = tint
        if (res == null) {
            leading.visibility = View.GONE
        } else {
            leading.setImageResource(res)
            leading.visibility = View.VISIBLE
        }
        surface.refresh()
    }

    fun setTrailingView(view: View?) {
        trailingSlot.removeAllViews()
        view?.let { trailingSlot.addView(it) }
        trailingSlot.visibility = if (view == null) View.GONE else View.VISIBLE
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val maxW = context.dp(280)
        val spec =
            when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> {
                    MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
                }

                else -> {
                    MeasureSpec.makeMeasureSpec(
                        minOf(MeasureSpec.getSize(widthMeasureSpec), maxW),
                        MeasureSpec.getMode(widthMeasureSpec),
                    )
                }
            }
        super.onMeasure(spec, heightMeasureSpec)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // Null while the View constructor runs, before this class's fields are set
        @Suppress("SENSELESS_COMPARISON")
        if (surface != null) surface.refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.onAttached()
    }

    override fun performClick(): Boolean {
        if (!surface.actionEnabled) return false
        return super.performClick()
    }
}

/**
 * The sort / filter popup (`AppDropdownMenu`, a Material3 `DropdownMenu`) as a focusable [PopupWindow] anchored below
 * [anchor] (above when there is no room): the Neon `card` panel with square corners and no tonal tint, or the stock
 * 4dp-rounded menu surface (3dp tonal elevation); 8dp vertical padding; it scrolls when taller than the screen.
 *
 * Add rows with [addItem] (or any view with [addView]); the first row takes focus. BACK closes it and [onDismissRequest]
 * runs; focus returns to the view that was focused before. The accent of focused rows is the anchor's section accent.
 */
class AppDropdownMenu(
    private val anchor: View,
    private val offsetXDp: Float = 0f,
    private val offsetYDp: Float = 0f,
    /** Stock themes: the menu surface colour (`containerColor`); null for the default */
    containerColor: Int? = null,
) {
    private val context: Context = anchor.context
    private val neon = AppTheme.isNeon
    private val column =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dp(8)
            setPadding(0, pad, 0, pad)
            clipChildren = false
            clipToPadding = false
        }
    private val scroller: ScrollView =
        ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            background =
                GradientDrawable().apply {
                    setColor(if (neon) NeonColors.CARD else containerColor ?: AppTheme.palette.surfaceAtElevation(3f))
                    cornerRadius = if (neon) 0f else context.dpF(4f)
                }
            elevation = context.dpF(3f)
            provideNeonScope(NeonScope(accent = anchor.resolveNeonScope().accentOrDefault))
        }

    // A focusable popup: it takes the keys, and BACK closes it
    private val popup: PopupWindow =
        PopupWindow(scroller, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            if (Build.VERSION.SDK_INT >= 23) elevation = context.dpF(3f)
        }

    /** Runs when the menu closes (BACK, outside, or [dismiss]) */
    var onDismissRequest: (() -> Unit)? = null
        set(value) {
            field = value
            popup.setOnDismissListener { value?.invoke() }
        }

    val isShowing: Boolean get() = popup.isShowing

    /** Add a row and return it; [onClick] runs on OK */
    fun addItem(
        text: CharSequence,
        @DrawableRes leadingIcon: Int? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): DropdownMenuItemView =
        DropdownMenuItemView(context).apply {
            setText(text)
            setLeadingIcon(leadingIcon)
            isActionEnabled = enabled
            setOnClickListener { onClick() }
            this@AppDropdownMenu.addView(this)
        }

    /** Add any view (eg a custom row or a divider) */
    fun addView(view: View) {
        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun clear() = column.removeAllViews()

    fun show() {
        popup.showAsDropDown(anchor, anchor.context.dp(offsetXDp), anchor.context.dp(offsetYDp) + belowFocusedAnchor(), Gravity.START)
        (0 until column.childCount).map { column.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
    }

    fun dismiss() = popup.dismiss()

    /**
     * How far below the anchor's bottom the menu starts. A focused button grows (1.1 for buttons) past its bounds, and
     * the menu used to start at the bounds, covering the button's bottom border (owner, 2026-11-25). Now it starts
     * below the grown button with a small gap. The focused scale comes from the button's style, since the button may
     * still be growing back after the press when the menu opens.
     */
    private fun belowFocusedAnchor(): Int {
        val scale =
            (anchor as? AppButton)
                ?.surface
                ?.style
                ?.scale
                ?.focused ?: anchor.scaleY
        val grown = (scale - 1f).coerceAtLeast(0f) * (anchor.height - anchor.pivotY)
        return ceil(grown).toInt() + context.dp(MENU_GAP_DP)
    }

    private companion object {
        const val MENU_GAP_DP = 4f
    }
}
