package com.github.damontecres.wholphin.leanback.shell

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.asImage
import coil3.load
import coil3.request.placeholder
import coil3.request.transitionFactory
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.CrossFadeFactory
import com.github.damontecres.wholphin.ui.components.ScreensaverItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The screensaver (the old `AppScreensaverContent`), in the app and in [com.github.damontecres.wholphin.WholphinDreamService]:
 * each item's backdrop fitted to the screen, slowly zooming to 110 % and back when [animate], cross-fading over 2 s to
 * the next; its logo (or title) bottom start; a vignette; the clock with its scrim when [showClock]. With no items,
 * "No results" (or the error) moves to a random place every [duration], with the brand mark on the WeaselTV theme.
 */
@SuppressLint("ViewConstructor")
class ScreensaverView(
    context: Context,
) : FrameLayout(context) {
    private val backdrop = ImageView(context)
    private val logo = ImageView(context)
    private val titleBox = FrameLayout(context)
    private val title = TextView(context)
    private val placeholder = FrameLayout(context)
    private val vignette = VignetteView(context)
    private val clock = ClockView(context)

    private var showClock = false
    private var duration: Duration = 30_000.milliseconds
    private var animate = true
    private var zoom: ValueAnimator? = null
    private var placeholderJob: Job? = null
    private var collectJob: Job? = null
    private var current: ScreensaverItem? = null

    init {
        setBackgroundColor(if (AppTheme.isNeon) NeonColors.STAGE else 0xFF000000.toInt())
        backdrop.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        logo.contentDescription = "Logo"
        val p16 = context.dp(16)
        logo.setPadding(p16, p16, p16, p16)
        addView(logo, LayoutParams(context.dp(240), context.dp(120), Gravity.BOTTOM or Gravity.START))
        title.applyTextRole(TextRole.DISPLAY_SMALL)
        title.setTextColor(AppTheme.palette.onSurface)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        titleBox.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(titleBox, LayoutParams(0, 0, Gravity.BOTTOM or Gravity.START).apply { setMargins(p16, p16, p16, p16) })
        titleBox.visibility = GONE
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        placeholder.visibility = GONE
        addView(vignette, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val clockLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        clock.setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
        addView(clock, clockLp)
        isFocusable = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        // The title takes half the width and 30 % of the height, bottom start
        (titleBox.layoutParams as LayoutParams).apply {
            width = (MeasureSpec.getSize(widthMeasureSpec) * .5f).toInt()
            height = (MeasureSpec.getSize(heightMeasureSpec) * .3f).toInt()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The screensaver's preferences */
    fun configure(
        showClock: Boolean,
        duration: Duration,
        animate: Boolean,
    ) {
        this.showClock = showClock
        this.duration = duration
        this.animate = animate
        clock.visibility = if (showClock) VISIBLE else GONE
        vignette.showClockScrim = showClock
        restartZoom()
    }

    /** Collect [items] in [scope] until [stop] */
    fun start(
        scope: CoroutineScope,
        items: Flow<ScreensaverItem?>,
    ) {
        collectJob?.cancel()
        collectJob = scope.launch { items.collect { show(it) } }
        restartZoom()
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        placeholderJob?.cancel()
        placeholderJob = null
        zoom?.cancel()
        zoom = null
    }

    private fun restartZoom() {
        zoom?.cancel()
        backdrop.scaleX = 1f
        backdrop.scaleY = 1f
        if (!animate || !isAttachedToWindow) return
        zoom =
            ValueAnimator.ofFloat(1f, 1.1f).apply {
                duration = this@ScreensaverView.duration.inWholeMilliseconds
                startDelay = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val s = it.animatedValue as Float
                    backdrop.scaleX = s
                    backdrop.scaleY = s
                }
                start()
            }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartZoom()
    }

    override fun onDetachedFromWindow() {
        zoom?.cancel()
        zoom = null
        super.onDetachedFromWindow()
    }

    private fun show(item: ScreensaverItem?) {
        current = item
        when (item) {
            ScreensaverItem.Empty -> {
                showPlaceholder(context.getString(R.string.no_results))
            }

            is ScreensaverItem.Error -> {
                showPlaceholder("Error connecting to Jellyfin server")
            }

            null, is ScreensaverItem.CurrentItem -> {
                placeholderJob?.cancel()
                placeholder.visibility = GONE
                backdrop.visibility = VISIBLE
                val previous = backdrop.drawable
                backdrop.load(item?.backdropUrl) {
                    transitionFactory(CrossFadeFactory(2000.milliseconds))
                    // The last picture stays until the next one has faded in over it
                    if (previous != null) placeholder(previous.asImage())
                }
                titleBox.visibility = GONE
                logo.visibility = VISIBLE
                title.text = item?.title ?: ""
                logo.load(item?.logoUrl) {
                    transitionFactory(CrossFadeFactory(750.milliseconds))
                    listener(
                        onError = { _, _ ->
                            if (current === item) {
                                logo.visibility = GONE
                                titleBox.visibility = VISIBLE
                            }
                        },
                    )
                }
            }
        }
    }

    private fun showPlaceholder(text: String) {
        backdrop.visibility = GONE
        logo.visibility = GONE
        titleBox.visibility = GONE
        placeholder.visibility = VISIBLE
        placeholderJob?.cancel()
        val gravities =
            listOf(
                Gravity.TOP or Gravity.START,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                Gravity.TOP or Gravity.END,
                Gravity.CENTER_VERTICAL or Gravity.START,
                Gravity.CENTER,
                Gravity.CENTER_VERTICAL or Gravity.END,
                Gravity.BOTTOM or Gravity.START,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                Gravity.BOTTOM or Gravity.END,
            )
        var gravity = Gravity.BOTTOM or Gravity.START
        val first = placeholderContent(text)
        placeholder.removeAllViews()
        placeholder.addView(first, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
        placeholderJob =
            CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                while (isActive) {
                    delay(duration)
                    gravity = (gravities - gravity).random()
                    // Cross-fade to the new place (500 ms each way, 100 ms in)
                    val old = placeholder.getChildAt(placeholder.childCount - 1)
                    val next = placeholderContent(text)
                    next.alpha = 0f
                    placeholder.addView(next, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
                    next
                        .animate()
                        .alpha(1f)
                        .setStartDelay(100)
                        .setDuration(500)
                        .start()
                    old
                        ?.animate()
                        ?.alpha(0f)
                        ?.setStartDelay(100)
                        ?.setDuration(500)
                        ?.withEndAction {
                            placeholder.removeView(old)
                        }?.start()
                }
            }
    }

    private fun placeholderContent(text: String): View {
        if (AppTheme.isNeon) {
            // Neon Board (T12): the brand mark drifts every cycle to avoid burn-in
            val column =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val p = context.dp(48)
                    setPadding(p, p, p, p)
                }
            column.addView(
                brandRow(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            val label =
                TextView(context).apply {
                    this.text = text
                    ShellType.small(this)
                    setTextColor(NeonColors.MID)
                }
            column.addView(
                label,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(12)
                },
            )
            return column
        }
        return TextView(context).apply {
            this.text = text
            applyTextRole(TextRole.DISPLAY_SMALL)
            setTextColor(AppTheme.palette.error)
            val p = context.dp(40)
            setPadding(p, p, p, p)
        }
    }

    /** `NeonBrandRow(mascotSize = 96dp, wordmarkSize = 36sp)`: the lockup when this flavor ships it */
    private fun brandRow(): View {
        val lockup = context.resources.getIdentifier("weaselplex_lockup", "drawable", context.packageName)
        if (lockup != 0) {
            return ImageView(context).apply {
                setImageResource(lockup)
                adjustViewBounds = true
                contentDescription = "WeaselPlex"
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (context.dp(96) * 1.6f).toInt())
                maxHeight = (context.dp(96) * 1.6f).toInt()
            }
        }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val mascot = context.resources.getIdentifier("weaselplex_mascot", "drawable", context.packageName)
        if (mascot != 0) {
            row.addView(
                ImageView(context).apply { setImageResource(mascot) },
                LinearLayout.LayoutParams(context.dp(96), context.dp(96)).apply { marginEnd = context.dp(12) },
            )
        }
        row.addView(
            TextView(context).apply {
                text = context.getString(R.string.app_name).uppercase()
                typeface = AppFonts.display(context)
                textSize = 36f
                setTextColor(NeonColors.TEXT)
            },
        )
        return row
    }

    /** The vignette (25 % black towards the corners) and the clock's top scrim */
    private class VignetteView(
        context: Context,
    ) : View(context) {
        var showClockScrim = false
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.shader =
                RadialGradient(
                    w / 2f,
                    h / 2f,
                    maxOf(w, h) / 1.5f,
                    intArrayOf(0, 0x40000000),
                    floatArrayOf(0f, .85f),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(0f, 0f, w, h, paint)
            if (showClockScrim) {
                // Add scrim to make clock more readable
                paint.shader =
                    LinearGradient(
                        0f,
                        0f,
                        0f,
                        h * TOP_SCRIM_END_FRACTION,
                        ((TOP_SCRIM_ALPHA * 255).toInt()) shl 24,
                        0,
                        Shader.TileMode.CLAMP,
                    )
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            paint.shader = null
        }
    }
}
