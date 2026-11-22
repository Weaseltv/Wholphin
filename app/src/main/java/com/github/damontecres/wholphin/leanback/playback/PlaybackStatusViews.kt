package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkPreload
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkRotation

/**
 * The loading page (`LoadingPage`): the rotating WeaselPlex loading picture on black with the WeaselTV theme, a
 * 48dp spinner in the theme border colour otherwise. [focusEnabled] lets it hold focus like the old one.
 */
internal class PlaybackLoadingView(
    context: Context,
    focusEnabled: Boolean = true,
) : FrameLayout(context) {
    init {
        isFocusable = focusEnabled
        isFocusableInTouchMode = false
        if (AppTheme.isNeon) {
            val kids = LoadingArtworkRotation.lastProfileWasKids(context)
            val artworks =
                LoadingArtworkRotation.resourceNames(kids).mapNotNull { name ->
                    context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
                }
            val artwork = LoadingArtworkRotation.next(context, artworks, kids)
            // Get the following loading screen's picture ready while this one is up
            LoadingArtworkRotation.peek(context, artworks, kids)?.let { LoadingArtworkPreload.request(context.resources, it) }
            if (artwork != null) {
                setBackgroundColor(Color.BLACK)
                addView(
                    ImageView(context).apply {
                        setImageResource(artwork)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            } else {
                addView(spinner(context), LayoutParams(context.dp(96), context.dp(96), Gravity.CENTER))
            }
        } else {
            addView(spinner(context), LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER))
        }
    }

    private fun spinner(context: Context) =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(if (AppTheme.isNeon) NeonColors.VOLT else AppTheme.palette.border)
        }
}

/**
 * The error page (`ErrorMessageContent`): an 80dp red outline warning box on the WeaselTV theme, the title, the
 * "Send app logs" button (and "Try again" when given), then the message, the exception and its causes. Scrolls with
 * UP/DOWN, at most 480dp tall.
 */
internal class PlaybackErrorView(
    context: Context,
    message: String?,
    exception: Throwable?,
    accent: Int,
    onSendLogs: () -> Unit,
    onRetry: (() -> Unit)? = null,
) : ScrollView(context) {
    private val column = LinearLayout(context)

    init {
        isFocusable = true
        val neon = AppTheme.isNeon
        val p = AppTheme.palette
        column.orientation = LinearLayout.VERTICAL
        val pad = context.dp(16)
        column.setPadding(pad, pad, pad, pad)

        fun add(view: android.view.View) {
            column.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (column.childCount > 0) topMargin = context.dp(16)
                },
            )
        }
        if (neon) {
            val box =
                FrameLayout(context).apply {
                    background =
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            setStroke(context.dp(1), NeonColors.RED)
                        }
                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.lb_playback_ic_warning)
                            imageTintList = ColorStateList.valueOf(NeonColors.RED)
                        },
                        LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER),
                    )
                }
            column.addView(box, LinearLayout.LayoutParams(context.dp(80), context.dp(80)))
        }
        add(
            context.textView(color = if (neon) NeonColors.TEXT else p.error).apply {
                text = PlaybackText.upper(context.getString(R.string.compose_error_message_title))
                if (neon) PlaybackText.condensedTitle(this, 30f) else PlaybackText.role(this, TextRole.TITLE_MEDIUM)
            },
        )
        onRetry?.let { retry ->
            add(OutlineButton.text(context, context.getString(R.string.try_again), accent).apply { setOnClickListener { retry() } })
        }
        add(OutlineButton.text(context, context.getString(R.string.send_app_logs), accent).apply { setOnClickListener { onSendLogs() } })
        message?.let {
            add(
                context.textView(color = if (neon) NeonColors.MID else p.error).apply {
                    text = it
                    if (neon) PlaybackText.body(this) else PlaybackText.role(this, TextRole.TITLE_LARGE)
                },
            )
        }
        exception?.localizedMessage?.let {
            add(context.textView(TextRole.TITLE_MEDIUM, p.error).apply { text = it })
        }
        var cause = exception?.cause
        while (cause != null) {
            cause.localizedMessage?.let { add(context.textView(TextRole.TITLE_SMALL, p.error).apply { text = "Caused by: $it" }) }
            cause = cause.cause
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val max = context.dp(480)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(if (h in 1 until max) h else max, MeasureSpec.AT_MOST))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The scrollable column scrolls 100px per UP/DOWN while it can
        if (event.action == KeyEvent.ACTION_DOWN && isFocused) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && canScrollVertically(1)) {
                smoothScrollBy(0, 100)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && canScrollVertically(-1)) {
                smoothScrollBy(0, -100)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
