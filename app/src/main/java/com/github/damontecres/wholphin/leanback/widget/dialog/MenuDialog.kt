package com.github.damontecres.wholphin.leanback.widget.dialog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.text.FittedTitleView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.ui.components.ContextMenuEntry
import com.github.damontecres.wholphin.ui.components.MenuEntryIcon
import com.github.damontecres.wholphin.ui.components.MenuIcon
import kotlin.math.min

/** An entry of a [MenuDialog]: a [MenuItem] or a [MenuDivider] */
sealed interface MenuEntry

/** A 16dp gap with a hairline at its top (`DialogItemDivider`) */
data object MenuDivider : MenuEntry

/**
 * A row of a [MenuDialog] (the old `DialogItem`, presentation-free): a headline with optional overline, supporting
 * line, leading icon / glyph / selected dot and trailing text.
 */
data class MenuItem(
    val headline: CharSequence,
    val onClick: () -> Unit,
    val overline: CharSequence? = null,
    val supporting: CharSequence? = null,
    /** Keep the two-line height without supporting text (an empty `supportingContent = {}` did) */
    val reserveSupportingLine: Boolean = supporting != null,
    val leading: ListLeading? = null,
    val trailing: CharSequence? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    /** Close the dialog after this row's action even when the dialog does not close on every click */
    val dismissOnClick: Boolean = false,
) : MenuEntry {
    companion object {
        /** Text with a Material icon (`DialogItem(text, icon, iconColor)`) */
        fun icon(
            text: CharSequence,
            @DrawableRes icon: Int,
            iconColor: Int? = null,
            dismissOnClick: Boolean = false,
            onClick: () -> Unit,
        ) = MenuItem(text, onClick, leading = ListLeading.Icon(icon, iconColor), dismissOnClick = dismissOnClick)

        /** Text with a Font Awesome glyph (`DialogItem(text, iconStringRes, iconColor)`) */
        fun glyph(
            text: CharSequence,
            @StringRes glyph: Int,
            iconColor: Int? = null,
            dismissOnClick: Boolean = false,
            onClick: () -> Unit,
        ) = MenuItem(text, onClick, leading = ListLeading.Glyph(glyph, iconColor), dismissOnClick = dismissOnClick)

        /** Text only (`DialogItem(text)`) */
        fun text(
            text: CharSequence,
            dismissOnClick: Boolean = false,
            onClick: () -> Unit,
        ) = MenuItem(text, onClick, dismissOnClick = dismissOnClick)
    }
}

/** What a [MenuDialog] shows (the old `DialogParams`) */
data class MenuParams(
    val fromLongClick: Boolean,
    val title: String,
    val items: List<MenuEntry>,
)

/** The drawable of each menu icon */
@DrawableRes
fun MenuIcon.drawable(): Int =
    when (this) {
        MenuIcon.ARROW_FORWARD -> R.drawable.lb_widget_ctl_ic_arrow_forward
        MenuIcon.ARROW_FORWARD_MIRRORED -> R.drawable.lb_widget_ctl_ic_arrow_forward_mirrored
        MenuIcon.PLAY -> R.drawable.lb_widget_ctl_ic_play_arrow
        MenuIcon.REFRESH -> R.drawable.lb_widget_ctl_ic_refresh
        MenuIcon.DELETE -> R.drawable.lb_widget_ctl_ic_delete
        MenuIcon.INFO -> R.drawable.lb_widget_ctl_ic_info
    }

/** A context menu entry as a menu row */
fun ContextMenuEntry.toMenuItem(): MenuItem =
    MenuItem(
        headline = text,
        onClick = onClick,
        leading =
            when (val i = icon) {
                is MenuEntryIcon.Material -> ListLeading.Icon(i.icon.drawable(), iconColor)
                is MenuEntryIcon.FontAwesome -> ListLeading.Glyph(i.glyph, iconColor)
                null -> null
            },
        dismissOnClick = dismissOnClick,
    )

/**
 * A dialog with a title and a list of rows (`DialogPopup` / `DialogPopupContent` as a View): on the Neon theme a
 * square 448dp `card` panel with a 1dp `line2` edge and the 3dp tally, the title in the dialog type (uppercase,
 * shrinking from 26sp to fit three lines) over an edge-to-edge accent rule, 24dp side insets for the title and
 * rows; elsewhere a 28dp rounded panel at [elevationDp] with a titleLarge title.
 *
 * Focus starts on the first row. UP on the first row goes to the last and DOWN on the last to the first (the old
 * `lazyListWrapScrolling`); the list scrolls to keep the focused row 20dp inside its edges (`DialogListEdge`).
 *
 * With [waitToLoad] (menus opened by a long press) every row looks disabled and ignores clicks for about a second,
 * or until OK / Enter is released, so the press that opened the menu cannot pick its first row.
 *
 * @param dismissOnClick close after any row's action (otherwise only rows with `dismissOnClick`)
 */
open class MenuDialog(
    context: Context,
    title: String,
    entries: List<MenuEntry>,
    private val dismissOnClick: Boolean = true,
    waitToLoad: Boolean = true,
    elevationDp: Float = 1f,
    accent: Int? = null,
    opener: View? = null,
) : AppDialog(
        context,
        DialogOptions(elevationDp = elevationDp, look = PanelLook.MENU, countForRequestUi = false),
        accent,
        opener,
    ) {
    constructor(
        context: Context,
        params: MenuParams,
        dismissOnClick: Boolean = true,
        elevationDp: Float = 1f,
        accent: Int? = null,
        opener: View? = null,
    ) : this(context, params.title, params.items, dismissOnClick, params.fromLongClick, elevationDp, accent, opener)

    private val rows = mutableListOf<Pair<AppListItem, MenuItem>>()
    private var waiting = waitToLoad
    private val handler = Handler(Looper.getMainLooper())
    private val stopWaiting = Runnable { setWaiting(false) }

    /** The scrolling list of rows */
    val list: MenuListView

    init {
        val neon = AppTheme.isNeon
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, context.dp(DIALOG_PADDING_DP), 0, context.dp(DIALOG_PADDING_DP - DIALOG_LIST_EDGE_DP))
            }
        val pad = context.dp(DIALOG_PADDING_DP)
        if (neon) {
            column.addView(
                FittedTitleView(context, maxLines = 3).apply {
                    setStyledText(title.uppercase(), NeonType.dialogTitle())
                    setTextColor(AppTheme.palette.onSurface)
                    setPaddingRelative(pad, 0, pad, 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            column.addView(
                NeonRuleView(context).apply { ruleAccent = this@MenuDialog.accent },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8)
                },
            )
        } else {
            column.addView(
                TextView(context).apply {
                    text = title
                    applyTextRole(TextRole.TITLE_LARGE)
                    setTextColor(AppTheme.palette.onSurface)
                    setPaddingRelative(pad, 0, pad, 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val items =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        entries.forEach { entry ->
            when (entry) {
                is MenuDivider -> {
                    items.addView(
                        MenuDividerView(context),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(16)),
                    )
                }

                is MenuItem -> {
                    val row = buildRow(context, entry)
                    rows.add(row to entry)
                    items.addView(
                        row,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = pad
                            marginEnd = pad
                        },
                    )
                }
            }
        }
        list =
            MenuListView(context).apply {
                id = R.id.lb_widget_ctl_menu_list
                setPadding(0, context.dp(DIALOG_LIST_EDGE_DP), 0, context.dp(DIALOG_LIST_EDGE_DP))
                addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setPanelContent(
            column,
            android.widget.FrameLayout.LayoutParams(
                if (neon) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        wireWrapAround()
        applyWaiting()
        rows.firstOrNull()?.first?.requestFocus()
    }

    private fun buildRow(
        context: Context,
        item: MenuItem,
    ): AppListItem =
        AppListItem(context).apply {
            id = View.generateViewId()
            setHeadline(item.headline)
            setOverline(item.overline)
            setSupporting(item.supporting, item.reserveSupportingLine)
            setLeading(item.leading)
            setTrailing(item.trailing)
            isItemSelected = item.selected
            setOnClickListener {
                if (dismissOnClick || item.dismissOnClick) requestDismiss()
                item.onClick.invoke()
            }
        }

    private fun wireWrapAround() {
        val first = rows.firstOrNull()?.first ?: return
        val last = rows.last().first
        first.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && first !== last) {
                last.requestFocus()
                true
            } else {
                false
            }
        }
        last.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && first !== last) {
                list.smoothScrollTo(0, 0)
                first.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun setWaiting(value: Boolean) {
        if (waiting == value) return
        waiting = value
        applyWaiting()
    }

    private fun applyWaiting() {
        rows.forEach { (row, item) -> row.isActionEnabled = !waiting && item.enabled }
    }

    override fun onStart() {
        super.onStart()
        if (waiting) handler.postDelayed(stopWaiting, WAIT_TO_LOAD_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(stopWaiting)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP &&
            (
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            )
        ) {
            // Released after the long press that opened the menu: rows work from now on
            val handled = super.dispatchKeyEvent(event)
            setWaiting(false)
            return handled
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val DIALOG_PADDING_DP = 24

        /**
         * Clear space at the top and bottom of a menu's list (`DialogListEdge`). A focused row draws a 1dp border, a
         * 10dp glow and grows a little, all of which the list would otherwise cut off.
         */
        const val DIALOG_LIST_EDGE_DP = 20

        /** How long rows of a menu opened by a long press stay disabled */
        const val WAIT_TO_LOAD_MS = 1000L

        /** Show a menu; see [MenuDialog] */
        fun show(
            context: Context,
            title: String,
            entries: List<MenuEntry>,
            onDismissRequest: () -> Unit = {},
            dismissOnClick: Boolean = true,
            waitToLoad: Boolean = true,
            elevationDp: Float = 1f,
            accent: Int? = null,
            opener: View? = null,
        ): MenuDialog =
            MenuDialog(context, title, entries, dismissOnClick, waitToLoad, elevationDp, accent, opener).also {
                it.onDismissRequest = onDismissRequest
                it.show()
            }
    }
}

/**
 * The scrolling list of a menu or dialog: a ScrollView that keeps the focused row inside its padding (the Compose
 * lazy list's content padding), so the first and last rows' focus border, glow and scale are never cut off. Set
 * vertical padding; `clipToPadding` is off.
 */
open class MenuListView(
    context: Context,
) : ScrollView(context) {
    init {
        clipToPadding = false
        clipChildren = false
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        isSmoothScrollingEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val viewTop = scrollY + paddingTop
        val viewBottom = scrollY + height - paddingBottom
        var delta =
            when {
                rect.top < viewTop -> rect.top - viewTop
                rect.bottom > viewBottom -> min(rect.bottom - viewBottom, rect.top - viewTop)
                else -> 0
            }
        val maxScroll = (getChildAt(0).height + paddingTop + paddingBottom - height).coerceAtLeast(0)
        delta = (scrollY + delta).coerceIn(0, maxScroll) - scrollY
        return delta
    }
}

/** `HorizontalDivider(Modifier.height(16.dp))`: a 16dp gap with a 1dp line in the outline-variant colour at its top */
class MenuDividerView(
    context: Context,
) : View(context) {
    private val paint = Paint().apply { color = AppTheme.palette.borderVariant }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), context.dp(1).toFloat(), paint)
    }
}
