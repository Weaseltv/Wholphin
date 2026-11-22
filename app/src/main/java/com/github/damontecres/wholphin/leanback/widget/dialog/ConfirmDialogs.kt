package com.github.damontecres.wholphin.leanback.widget.dialog

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.neonUpper
import com.github.damontecres.wholphin.leanback.widget.control.AppButton

/**
 * A confirmation dialog (`ConfirmDialog`): title (uppercase in the Neon dialog type, start aligned; titleLarge centred
 * elsewhere), optional body (`mid` in the Neon body type), then Cancel and Confirm text buttons spread evenly. 16dp
 * side and 20dp vertical padding, 8dp between rows, the platform's default dialog width.
 *
 * Focus starts on the first button (Cancel), as the first focusable of the old dialog. BACK and Cancel call
 * [onCancel]; Confirm calls [onConfirm]. Neither closes the dialog by itself, as in Compose: callers dismiss it (or use
 * [show], which does).
 *
 * @param bodyColor colour of the body; null keeps onSurface (`mid` on the Neon theme)
 */
class ConfirmDialog(
    context: Context,
    title: String,
    body: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    elevationDp: Float = 1f,
    bodyColor: Int? = null,
    accent: Int? = null,
    opener: View? = null,
) : AppDialog(context, DialogOptions(elevationDp = elevationDp), accent, opener) {
    /** The Cancel and Confirm buttons */
    val cancelButton: AppButton = AppButton.text(context, R.string.cancel, onCancel)
    val confirmButton: AppButton = AppButton.text(context, R.string.confirm, onConfirm)

    init {
        onDismissRequest = onCancel
        setPanelContent(confirmContent(context, title, body, bodyColor, cancelButton, confirmButton))
        cancelButton.requestFocus()
    }

    companion object {
        /** Show a confirmation that closes itself before calling [onCancel] / [onConfirm] */
        fun show(
            context: Context,
            title: String,
            body: String?,
            onCancel: () -> Unit = {},
            onConfirm: () -> Unit,
            accent: Int? = null,
            opener: View? = null,
        ): ConfirmDialog {
            var dialog: ConfirmDialog? = null
            dialog =
                ConfirmDialog(
                    context,
                    title,
                    body,
                    onCancel = {
                        dialog?.dismiss()
                        onCancel()
                    },
                    onConfirm = {
                        dialog?.dismiss()
                        onConfirm()
                    },
                    accent = accent,
                    opener = opener,
                )
            dialog.show()
            return dialog
        }
    }
}

/**
 * The content of a confirmation (`ConfirmDialogContent`), for screens that show it inside their own panel: title,
 * optional body and a row with [cancel] and [confirm] spread evenly.
 */
fun confirmContent(
    context: Context,
    title: String,
    body: String?,
    bodyColor: Int?,
    cancel: View,
    confirm: View,
): View {
    val neon = AppTheme.isNeon
    val palette = AppTheme.palette
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(context.dp(16), context.dp(MenuDialog.DIALOG_LIST_EDGE_DP), context.dp(16), context.dp(MenuDialog.DIALOG_LIST_EDGE_DP))
        addView(
            TextView(context).apply {
                text = title.neonUpper()
                setTextColor(palette.onSurface)
                applyTextStyle(if (neon) NeonType.dialogTitle() else ControlTextStyle.of(TextRole.TITLE_LARGE))
                gravity = if (neon) Gravity.START else Gravity.CENTER_HORIZONTAL
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (body != null) {
            addView(
                TextView(context).apply {
                    text = body
                    setTextColor(
                        when {
                            bodyColor != null -> bodyColor
                            neon -> NeonColors.MID
                            else -> palette.onSurface
                        },
                    )
                    applyTextStyle(if (neon) NeonType.body() else ControlTextStyle.of(TextRole.BODY_LARGE))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8)
                },
            )
        }
        addView(
            evenRow(context, cancel, confirm),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            },
        )
    }
}

/** A row that spreads its children evenly (Compose `Arrangement.SpaceEvenly`) */
private fun evenRow(
    context: Context,
    vararg children: View,
): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        children.forEach { child ->
            addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        }
    }

/**
 * Confirm deleting an item (`ConfirmDeleteDialog`): "Delete item" title, the item's title (titleLarge, centred), then a
 * 72dp Cancel button and a red outline Confirm with the delete icon, 32dp apart. The tally is red (destructive). The
 * dialog is as wide as its content (no platform width limit), elevation 3.
 *
 * Focus starts on Cancel. BACK and Cancel call [onCancel]; Confirm calls [onConfirm].
 */
class ConfirmDeleteDialog(
    context: Context,
    itemTitle: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    elevationDp: Float = 3f,
    accent: Int? = null,
    opener: View? = null,
) : AppDialog(
        context,
        DialogOptions(usePlatformDefaultWidth = false, elevationDp = elevationDp, tallyColor = NeonColors.RED),
        accent,
        opener,
    ) {
    val cancelButton: AppButton =
        AppButton(context).apply {
            setLabel(R.string.cancel)
            labelView.gravity = Gravity.CENTER
            setOnClickListener { onCancel() }
        }

    val confirmButton: AppButton =
        AppButton(context).apply {
            setSurfaceStyle { scope -> ControlStyles.outlineButton(NeonColors.RED, scope) }
            setIcon(R.drawable.lb_widget_ctl_ic_delete, tint = 0xCCFF0000.toInt())
            setLabel(R.string.confirm)
            // The icon and label sit in a Row with 4dp side padding inside the button
            setContentPaddingDp(8f, 4f, 8f, 4f)
            setOnClickListener { onConfirm() }
        }

    init {
        onDismissRequest = onCancel
        val neon = AppTheme.isNeon
        val palette = AppTheme.palette
        val content =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(
                    context.dp(16),
                    context.dp(MenuDialog.DIALOG_LIST_EDGE_DP),
                    context.dp(16),
                    context.dp(MenuDialog.DIALOG_LIST_EDGE_DP),
                )
                addView(
                    TextView(context).apply {
                        text = context.getString(R.string.delete_item).neonUpper()
                        setTextColor(palette.onSurface)
                        applyTextStyle(if (neon) NeonType.dialogTitle() else ControlTextStyle.of(TextRole.TITLE_MEDIUM))
                    },
                )
                addView(
                    TextView(context).apply {
                        text = itemTitle
                        setTextColor(palette.onSurface)
                        applyTextStyle(ControlTextStyle.of(TextRole.TITLE_LARGE))
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, 0, 0, context.dp(8))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(8)
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        clipChildren = false
                        clipToPadding = false
                        addView(cancelButton, LinearLayout.LayoutParams(context.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(
                            confirmButton,
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = context.dp(32)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(8)
                    },
                )
            }
        setPanelContent(content)
        cancelButton.requestFocus()
    }
}

/**
 * A dialog for longer content that scrolls (`ScrollableDialog`): [width] wide (600dp), at most [maxHeight] tall
 * (380dp), 16dp padding, [itemSpacingDp] between the views in [content]. The content area itself takes focus and
 * D-pad UP / DOWN scroll it by 100px, as before; other keys are not used.
 */
open class ScrollableDialog(
    context: Context,
    widthDp: Float = 600f,
    maxHeightDp: Float = 380f,
    itemSpacingDp: Float = 8f,
    accent: Int? = null,
    opener: View? = null,
) : AppDialog(context, DialogOptions(usePlatformDefaultWidth = false), accent, opener) {
    /** Add the dialog's content here, top to bottom */
    val content: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable =
                android.graphics.drawable.ShapeDrawable().apply {
                    intrinsicHeight = context.dp(itemSpacingDp)
                    paint.color = 0
                }
        }

    private val scroller =
        object : android.widget.ScrollView(context) {
            private val maxHeightPx = context.dp(maxHeightDp)

            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(context.dp(widthDp), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isVerticalScrollBarEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        (v as android.widget.ScrollView).smoothScrollBy(0, SCROLL_PX)
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        (v as android.widget.ScrollView).smoothScrollBy(0, -SCROLL_PX)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }

    init {
        setPanelContent(scroller)
        scroller.requestFocus()
    }

    companion object {
        /** D-pad scroll step in pixels (the old `scrollBy(100f)`) */
        const val SCROLL_PX = 100
    }
}
