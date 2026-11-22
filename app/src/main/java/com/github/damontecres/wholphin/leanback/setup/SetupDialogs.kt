package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A full-window dialog with its content centred (the Compose `Dialog` with `usePlatformDefaultWidth = false`): the
 * window behind is dimmed, BACK calls [onDismissRequest] and the dialog stays until its owner dismisses it, exactly as a
 * Compose dialog stayed until its state left the composition. Popups are `Dialog`s, so the platform gives focus back to
 * the view that opened them when they close.
 */
internal open class SetupDialog(
    context: Context,
    var onDismissRequest: () -> Unit,
) : ComponentDialog(context, android.R.style.Theme_Material_Dialog_NoActionBar) {
    /** The full-window root; content is centred in it */
    val root: FrameLayout =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

    /** The view that takes focus once when the dialog first shows */
    var initialFocus: View? = null

    private var shownOnce = false

    init {
        setCancelable(false)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onDismissRequest()
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.6f)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!shownOnce) {
            shownOnce = true
            initialFocus?.requestFocus()
        }
    }

    /** Coroutines for as long as the dialog shows */
    val scope get() = lifecycleScope

    /** Runs [block] after the dialog has shown for [millis] (eg the long-press guard) */
    fun after(
        millis: Long,
        block: () -> Unit,
    ) {
        lifecycleScope.launch {
            delay(millis)
            block()
        }
    }
}

/**
 * [KidPanelView] as a dialog [widthDp] wide (`KidPanelDialog`), at most 516dp tall, with room around the panel for the
 * bar's glow. [setPanel] replaces the panel (a new step with another width, or a scrolling change).
 */
internal open class KidPanelDialog(
    context: Context,
    widthDp: Float,
    scrollable: Boolean = true,
    onDismissRequest: () -> Unit,
) : SetupDialog(context, onDismissRequest) {
    private val holder =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(12))
        }
    var panel: KidPanelView = newPanel(widthDp, scrollable)
        private set
    val content: LinearLayout get() = panel.content
    private var widthDp = widthDp
    private var scrollable = scrollable

    init {
        root.addView(holder, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        holder.addView(panel, FrameLayout.LayoutParams(context.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun newPanel(
        widthDp: Float,
        scrollable: Boolean,
    ) = KidPanelView(context, scrollable, context.dp(516))

    /** A fresh, empty panel when the width or scrolling changes, else the current one emptied */
    fun reset(
        widthDp: Float = this.widthDp,
        scrollable: Boolean = this.scrollable,
    ): LinearLayout {
        if (widthDp != this.widthDp || scrollable != this.scrollable) {
            this.widthDp = widthDp
            this.scrollable = scrollable
            holder.removeAllViews()
            panel = newPanel(widthDp, scrollable)
            holder.addView(panel, FrameLayout.LayoutParams(context.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            panel.content.removeAllViews()
        }
        return panel.content
    }
}

/**
 * The panel `BasicDialog` draws its content on (`BasicDialogPanel`): on the WeaselTV theme a square `card` panel with a
 * 1dp `line2` edge, a 20dp shadow and the 3dp accent tally on its left; elsewhere a rounded tonal surface at [elevationDp].
 */
internal class BasicPanelView(
    context: Context,
    elevationDp: Float = 1f,
    private val accent: Int = NeonColors.VOLT,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val edge =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(1f)
            color = NeonColors.LINE2
        }
    private val tally = Paint().apply { color = accent }
    private val tallyGlow = Paint()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        if (neon) {
            setBackgroundColor(NeonColors.CARD)
            elevation = context.dpF(20f)
            outlineProvider = ViewOutlineProvider.BOUNDS
        } else {
            val radius = context.dpF(8f)
            background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(surfaceAtElevation(elevationDp))
                    cornerRadius = radius
                }
            elevation = context.dpF(elevationDp)
            outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: Outline,
                    ) = outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!neon) return
        val half = edge.strokeWidth / 2
        canvas.drawRect(half, half, width - half, height - half, edge)
        val glowW = context.dpF(12f)
        tallyGlow.shader =
            LinearGradient(0f, 0f, glowW, 0f, withAlpha(NeonSections.glowFor(accent), .45f), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, glowW, height.toFloat(), tallyGlow)
        canvas.drawRect(0f, 0f, context.dpF(3f), height.toFloat(), tally)
    }
}

/**
 * `BasicDialog`: [content] on a [BasicPanelView], centred. [widthFraction] sizes the panel to a share of the screen
 * width (the old `fillMaxWidth(.4f)` columns); [maxHeightDp] caps it with the content scrolling inside.
 */
internal open class BasicDialog(
    context: Context,
    onDismissRequest: () -> Unit,
    elevationDp: Float = 1f,
    private val widthFraction: Float? = null,
    private val maxHeightDp: Float? = null,
) : SetupDialog(context, onDismissRequest) {
    val panel = BasicPanelView(context, elevationDp)
    val scroll =
        object : ScrollView(context) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                val max = maxHeightDp?.let { context.dp(it) }
                if (max == null) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    return
                }
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val capped = if (mode == MeasureSpec.UNSPECIFIED) max else minOf(max, size)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    /** The column the dialog fills */
    val content: LinearLayout = vertical(context)

    init {
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
        // The dialog window is the full screen, so a share of the screen width is a share of the window's
        val width =
            widthFraction?.let { (context.resources.displayMetrics.widthPixels * it).toInt() }
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(panel, frame(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
}

/** One row of a [MenuDialog] (`DialogItem`); [divider] draws a rule instead */
internal data class MenuItem(
    val text: String,
    val glyph: String? = null,
    @param:DrawableRes val icon: Int = 0,
    val iconTint: Int? = null,
    val supporting: String? = null,
    val enabled: Boolean = true,
    val divider: Boolean = false,
    val onClick: () -> Unit = {},
)

/**
 * A dialog with a list of entries (`DialogPopup`): Neon is a 448dp square `card` panel with the accent tally, the
 * uppercase Condensed 26 title on a rule and the hairline rows; the stock one a rounded tonal panel. UP from the first
 * row goes to the last and DOWN from the last to the first, like the old list.
 *
 * [waitToLoad]: the rows start disabled for about a second (the OK of the long press that opened it would otherwise
 * click the first row), unless OK is released here first.
 */
internal class MenuDialog(
    context: Context,
    title: String,
    items: List<MenuItem>,
    onDismissRequest: () -> Unit,
    private val dismissOnClick: Boolean = true,
    waitToLoad: Boolean = true,
    elevationDp: Float = 1f,
    accent: Int = NeonColors.VOLT,
) : SetupDialog(context, onDismissRequest) {
    private val rows = mutableListOf<SetupListItem>()
    private var waiting = waitToLoad

    init {
        val neon = AppTheme.isNeon
        val panel: ViewGroup =
            if (neon) {
                BasicPanelView(context, 20f, accent)
            } else {
                FrameLayout(context).apply {
                    val radius = context.dpF(28f)
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(surfaceAtElevation(elevationDp))
                            cornerRadius = radius
                        }
                    elevation = context.dpF(elevationDp)
                    clipToOutline = true
                    outlineProvider =
                        object : ViewOutlineProvider() {
                            override fun getOutline(
                                view: View,
                                outline: Outline,
                            ) = outline.setRoundRect(0, 0, view.width, view.height, radius)
                        }
                }
            }
        val column =
            vertical(context).apply {
                setPadding(0, context.dp(24), 0, context.dp(24 - 20))
            }
        val titleView =
            context.setupText(if (neon) title.uppercase() else title) {
                if (neon) {
                    neonHeader(26f, TextRole.HEADLINE_SMALL)
                    fitOneLine(12, 26)
                } else {
                    applyTextRole(TextRole.TITLE_LARGE)
                }
            }
        titleView.setPadding(context.dp(24), 0, context.dp(24), 0)
        column.addView(titleView, matchWidth())
        column.addView(NeonRuleView(context, accent), matchWidth().apply { topMargin = context.dp(8) })
        val list = vertical(context).apply { setPadding(0, context.dp(20), 0, context.dp(20)) }
        items.forEach { item ->
            if (item.divider) {
                list.addView(
                    View(context).apply { setBackgroundColor(withAlpha(AppTheme.palette.onSurface, .12f)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                        topMargin = context.dp(8)
                        bottomMargin = context.dp(7)
                    },
                )
                return@forEach
            }
            val row =
                SetupListItem(
                    context,
                    item.text,
                    item.supporting,
                    item.glyph,
                    item.icon,
                    item.iconTint,
                    accent,
                ).apply {
                    id = View.generateViewId()
                    isEnabled = item.enabled && !waiting
                    setOnClickListener {
                        if (!isEnabled) return@setOnClickListener
                        if (dismissOnClick) {
                            this@MenuDialog.onDismissRequest()
                        }
                        item.onClick()
                    }
                    tag = item
                }
            rows += row
            list.addView(
                row,
                matchWidth().apply {
                    marginStart = context.dp(24)
                    marginEnd = context.dp(24)
                },
            )
        }
        // Wrap around at the ends, like the old list
        if (rows.size > 1) {
            rows.first().nextFocusUpId = rows.last().id
            rows.last().nextFocusDownId = rows.first().id
        }
        val scroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(list)
            }
        column.addView(scroll, matchWidth())
        panel.addView(column, frame(height = ViewGroup.LayoutParams.WRAP_CONTENT))
        val width = if (neon) context.dp(448) else ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(panel, frame(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        initialFocus = rows.firstOrNull()
        if (waitToLoad) after(1000) { stopWaiting() }
    }

    private fun stopWaiting() {
        if (!waiting) return
        waiting = false
        rows.forEach { it.isEnabled = (it.tag as MenuItem).enabled }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Releasing OK here ends the long-press guard straight away
        if (event.action == KeyEvent.ACTION_UP && waiting && isEnterKeyCode(event.keyCode)) {
            stopWaiting()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
