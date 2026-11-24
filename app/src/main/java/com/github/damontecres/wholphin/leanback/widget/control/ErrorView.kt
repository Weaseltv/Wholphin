package com.github.damontecres.wholphin.leanback.widget.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.neonUpper
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.LoadingState

/**
 * An error message (`ErrorMessage` / `ErrorMessageContent`): on the Neon theme an 80dp red outline box with the warning
 * icon, then the "error" title (uppercase Audiowide 30sp; titleMedium in the error colour elsewhere), "Try again"
 * when [onRetry] is set, "Send app logs", the [message] and the exception chain ("Caused by: …"). 16dp padding and
 * spacing, at most 480dp tall.
 *
 * It scrolls when the text is long: D-pad DOWN / UP scroll by 100px while there is more to show, and only then move
 * focus (as the old focusable scrolling column did), so the final cause is always readable.
 *
 * In a screen, send logs with the screen's [com.github.damontecres.wholphin.ui.components.ErrorViewModel]:
 * `ErrorView(context, error.message, error.exception, onSendLogs = errorViewModel::sendLogs, onRetry = …)`.
 */
class ErrorView(
    context: Context,
    message: String?,
    exception: Throwable?,
    onSendLogs: () -> Unit,
    onRetry: (() -> Unit)? = null,
) : ScrollView(context) {
    constructor(context: Context, error: LoadingState.Error, onSendLogs: () -> Unit, onRetry: (() -> Unit)? = null) :
        this(context, error.message, error.exception, onSendLogs, onRetry)

    constructor(context: Context, error: DataLoadingState.Error, onSendLogs: () -> Unit) :
        this(context, error.message, error.exception, onSendLogs, null)

    /** "Try again", when shown */
    val retryButton: AppButton?

    /** "Send app logs" */
    val sendLogsButton: AppButton

    private val maxHeightPx = context.dp(480)

    init {
        val neon = AppTheme.isNeon
        val palette = AppTheme.palette
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                val p = context.dp(16)
                setPadding(p, p, p, p)
            }
        val gap = context.dp(16)

        fun add(view: View) {
            column.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (column.childCount > 0) topMargin = gap
                },
            )
        }

        fun text(
            value: String,
            color: Int,
            style: ControlTextStyle,
        ) = TextView(context).apply {
            text = value
            setTextColor(color)
            applyTextStyle(style)
        }
        if (neon) {
            add(
                FrameLayout(context).apply {
                    background =
                        GradientDrawable().apply {
                            setColor(0)
                            setStroke(context.dp(1), NeonColors.RED)
                        }
                    layoutParams = LinearLayout.LayoutParams(context.dp(80), context.dp(80))
                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.lb_widget_ctl_ic_warning)
                            imageTintList = ColorStateList.valueOf(NeonColors.RED)
                        },
                        FrameLayout.LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER),
                    )
                },
            )
            (column.getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply {
                width = context.dp(80)
                height = context.dp(80)
            }
        }
        add(
            text(
                context.getString(R.string.compose_error_message_title).neonUpper(),
                if (neon) NeonColors.TEXT else palette.error,
                if (neon) NeonType.condensedTitle(30f) else ControlTextStyle.of(TextRole.TITLE_MEDIUM),
            ),
        )
        retryButton = onRetry?.let { retry -> AppButton.text(context, R.string.try_again, retry).also { add(it) } }
        sendLogsButton = AppButton.text(context, R.string.send_app_logs, onSendLogs).also { add(it) }
        message?.let {
            add(
                text(
                    it,
                    if (neon) NeonColors.MID else palette.error,
                    if (neon) NeonType.body() else ControlTextStyle.of(TextRole.TITLE_LARGE),
                ),
            )
        }
        exception?.localizedMessage?.let { add(text(it, palette.error, ControlTextStyle.of(TextRole.TITLE_MEDIUM))) }
        var cause = exception?.cause
        while (cause != null) {
            cause.localizedMessage?.let { add(text("Caused by: $it", palette.error, ControlTextStyle.of(TextRole.TITLE_SMALL))) }
            cause = cause.cause
        }
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val limited =
            when (mode) {
                MeasureSpec.UNSPECIFIED -> {
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                }

                else -> {
                    MeasureSpec.makeMeasureSpec(
                        minOf(size, maxHeightPx),
                        if (mode == MeasureSpec.EXACTLY &&
                            size <= maxHeightPx
                        ) {
                            MeasureSpec.EXACTLY
                        } else {
                            MeasureSpec.AT_MOST
                        },
                    )
                }
            }
        super.onMeasure(widthMeasureSpec, limited)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val amount =
                when {
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && canScrollVertically(1) -> SCROLL_PX
                    event.keyCode == KeyEvent.KEYCODE_DPAD_UP && canScrollVertically(-1) -> -SCROLL_PX
                    else -> 0
                }
            if (amount != 0) {
                smoothScrollBy(0, amount)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val SCROLL_PX = 100
    }
}
