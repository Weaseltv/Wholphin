package com.github.damontecres.wholphin.leanback.widget.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.NeonScope
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.scaleAlpha

/** What sits in a list row's leading slot */
sealed interface ListLeading {
    /** A drawable icon (tv-material3 `Icon`, 24dp), tinted with the leading content colour unless [tint] is set */
    data class Icon(
        @param:DrawableRes val res: Int,
        val tint: Int? = null,
    ) : ListLeading

    /** A Font Awesome glyph in the body text size, in the leading content colour unless [color] is set */
    data class Glyph(
        @param:StringRes val glyph: Int,
        val color: Int? = null,
    ) : ListLeading

    /** `SelectedLeadingContent`: an 8dp dot in the content colour when [selected], nothing (but the slot) otherwise */
    data class SelectedDot(
        val selected: Boolean,
    ) : ListLeading

    /** Any view */
    data class Custom(
        val view: View,
    ) : ListLeading
}

/**
 * A tv-material3 `ListItem` / `DenseListItem` as a View (ListItemWrapper.kt, and the rows of every menu and dialog):
 * an optional leading slot (at least 32dp, 20dp dense, then 8dp), the overline / headline / supporting lines, and an
 * optional trailing text or view (8dp, then at least 32dp). Padding 16/12dp (12/10 dense); minimum height 48dp, 56
 * with a leading slot, 64 with two lines, 80 with three (dense 40/40/56/72).
 *
 * Text: headline titleMedium (titleSmall dense) in the content colour, overline labelSmall at 60 %, supporting
 * bodySmall at 80 %, trailing labelLarge (labelSmall dense), leading at 80 %. Look: [ControlStyles.listItem] (the
 * Neon list recipe, or the stock tv-material3 one); rows grow to 1.05 when focused.
 *
 * Focus: the row takes focus. Disabled rows ([isActionEnabled] false) stay focusable and ignore clicks.
 */
open class AppListItem
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        val dense: Boolean = false,
    ) : LinearLayout(context, attrs) {
        private val leadingSlot =
            FrameLayout(context).apply {
                minimumWidth = context.dp(if (dense) 20 else 32)
                minimumHeight = context.dp(if (dense) 20 else 32)
                visibility = View.GONE
            }
        private val textColumn = LinearLayout(context).apply { orientation = VERTICAL }

        val overlineView: TextView = line(TextRole.LABEL_SMALL)
        val headlineView: TextView = line(if (dense) TextRole.TITLE_SMALL else TextRole.TITLE_MEDIUM)
        val supportingView: TextView = line(TextRole.BODY_SMALL)

        private val trailingSlot =
            FrameLayout(context).apply {
                minimumWidth = context.dp(if (dense) 20 else 32)
                minimumHeight = context.dp(if (dense) 20 else 32)
                visibility = View.GONE
            }
        private val trailingText: TextView =
            TextView(context).apply {
                applyTextRole(if (dense) TextRole.LABEL_SMALL else TextRole.LABEL_LARGE)
                gravity = Gravity.CENTER
            }

        private var leading: ListLeading? = null
        private var leadingView: View? = null
        private var hasSupportingLine = false
        private var styleOverride: ((NeonScope) -> ControlSurfaceStyle)? = null

        /** The focus machinery */
        val surface = SurfaceController(this) { scope -> styleOverride?.invoke(scope) ?: ControlStyles.listItem(scope) }

        /** Called with the content colour of each state, for custom leading / trailing views */
        var onContentColor: ((Int) -> Unit)? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            val h = context.dp(if (dense) 12 else 16)
            val v = context.dp(if (dense) 10 else 12)
            setPaddingRelative(h, v, h, v)
            addView(
                leadingSlot,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dp(8) },
            )
            textColumn.addView(overlineView)
            textColumn.addView(headlineView)
            textColumn.addView(supportingView)
            overlineView.visibility = View.GONE
            supportingView.visibility = View.GONE
            addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            trailingSlot.addView(
                trailingText,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            addView(
                trailingSlot,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(8) },
            )
            surface.onContentColor = { applyContentColor(it) }
            updateMinHeight()
        }

        private fun line(role: TextRole) = TextView(context).apply { applyTextRole(role) }

        /** Use another surface recipe (eg the side menu's `neonListItemBorder(width, opacity)` variant) */
        fun setSurfaceStyle(builder: (NeonScope) -> ControlSurfaceStyle) {
            styleOverride = builder
            surface.rebuild()
        }

        /** An explicit accent; null takes the view tree's */
        var accent: Int?
            get() = surface.explicitScope?.accent
            set(value) {
                surface.explicitScope = (surface.explicitScope ?: NeonScope()).copy(accent = value)
            }

        var isActionEnabled: Boolean
            get() = surface.actionEnabled
            set(value) {
                surface.actionEnabled = value
            }

        /** tv-material3 `selected`: the selected colours (the accent label on the Neon theme) */
        var isItemSelected: Boolean
            get() = surface.selected
            set(value) {
                surface.selected = value
            }

        fun setHeadline(text: CharSequence?) {
            headlineView.text = text
        }

        fun setOverline(text: CharSequence?) {
            overlineView.text = text
            overlineView.visibility = if (text == null) View.GONE else View.VISIBLE
            updateMinHeight()
        }

        /**
         * The supporting line. [reserveLine] keeps the two-line height with no text, as an empty
         * `supportingContent = {}` lambda did in the old stream choosers.
         */
        fun setSupporting(
            text: CharSequence?,
            reserveLine: Boolean = text != null,
        ) {
            supportingView.text = text
            supportingView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            hasSupportingLine = reserveLine
            updateMinHeight()
        }

        fun setTrailing(text: CharSequence?) {
            trailingSlot.removeAllViews()
            trailingSlot.addView(
                trailingText,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            trailingText.text = text
            trailingSlot.visibility = if (text == null) View.GONE else View.VISIBLE
        }

        fun setTrailingView(view: View?) {
            trailingSlot.removeAllViews()
            if (view != null) {
                trailingSlot.addView(
                    view,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }
            trailingSlot.visibility = if (view == null) View.GONE else View.VISIBLE
        }

        fun setLeading(value: ListLeading?) {
            leading = value
            leadingSlot.removeAllViews()
            leadingView =
                when (value) {
                    null -> {
                        null
                    }

                    is ListLeading.Icon -> {
                        ImageView(context).apply {
                            setImageResource(value.res)
                            layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER)
                        }
                    }

                    is ListLeading.Glyph -> {
                        TextView(context).apply {
                            typeface = AppFonts.icons(context)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setText(value.glyph)
                            gravity = Gravity.CENTER
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER,
                                )
                        }
                    }

                    is ListLeading.SelectedDot -> {
                        SelectedDotView(context).apply {
                            selectedDot = value.selected
                            layoutParams =
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER,
                                )
                        }
                    }

                    is ListLeading.Custom -> {
                        value.view.apply {
                            if (layoutParams == null) {
                                layoutParams =
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.CENTER,
                                    )
                            }
                        }
                    }
                }
            leadingView?.let { leadingSlot.addView(it) }
            leadingSlot.visibility = if (value == null) View.GONE else View.VISIBLE
            updateMinHeight()
            applyContentColor(surface.contentColor)
        }

        private fun updateMinHeight() {
            val overline = overlineView.visibility == View.VISIBLE
            val dp =
                when {
                    hasSupportingLine && overline -> if (dense) 72 else 80
                    hasSupportingLine || overline -> if (dense) 56 else 64
                    leading != null -> if (dense) 40 else 56
                    else -> if (dense) 40 else 48
                }
            minimumHeight = context.dp(dp)
        }

        private fun applyContentColor(color: Int) {
            headlineView.setTextColor(color)
            overlineView.setTextColor(scaleAlpha(color, .6f))
            supportingView.setTextColor(scaleAlpha(color, .8f))
            trailingText.setTextColor(color)
            val leadingColor = scaleAlpha(color, .8f)
            when (val l = leading) {
                is ListLeading.Icon -> {
                    (leadingView as? ImageView)?.imageTintList = ColorStateList.valueOf(l.tint ?: leadingColor)
                }

                is ListLeading.Glyph -> {
                    (leadingView as? TextView)?.setTextColor(l.color ?: leadingColor)
                }

                is ListLeading.SelectedDot -> {
                    (leadingView as? SelectedDotView)?.dotColor = leadingColor
                }

                else -> {}
            }
            onContentColor?.invoke(color)
        }

        /** Headline single line with an ellipsis (eg menu rows); the old rows wrapped by default */
        fun setHeadlineSingleLine(single: Boolean) {
            headlineView.maxLines = if (single) 1 else Int.MAX_VALUE
            headlineView.ellipsize = if (single) TextUtils.TruncateAt.END else null
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            surface.refresh()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            surface.onAttached()
        }

        override fun performClick(): Boolean {
            if (!surface.actionEnabled) return false
            return super.performClick()
        }

        override fun performLongClick(): Boolean {
            if (!surface.actionEnabled) return false
            return super.performLongClick()
        }
    }

/**
 * `SelectedLeadingContent`: when [selectedDot], an 8dp circle with 4dp of space each side in [dotColor]; otherwise
 * it keeps its size and draws nothing.
 */
class SelectedDotView(
    context: Context,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var selectedDot: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var dotColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (selectedDot) {
            setMeasuredDimension(context.dp(16), context.dp(8))
        } else {
            setMeasuredDimension(0, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!selectedDot) return
        paint.color = dotColor
        val r = context.dp(4).toFloat()
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
}
