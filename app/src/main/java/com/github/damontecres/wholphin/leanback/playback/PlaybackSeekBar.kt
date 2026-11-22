package com.github.damontecres.wholphin.leanback.playback

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.size.Size
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.calculateSeekAccelerationMultiplier
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The player's seek bar (`IntervalSeekBarImpl` / `StaticSeekBarImpl` + `SeekBarDisplay`), no-Compose.
 *
 * Neon Board: 4dp `line2` track, buffered `mid` 35 %, the item's type accent fill with a 10dp glow and a 14dp square
 * thumb with a 2dp `text` ring. Other themes: a rounded 6dp bar (12dp when focused) in the theme border colour with a
 * white dot. LEFT/RIGHT seek by the skip back/forward amounts, accelerating while held.
 */
internal class SeekBarView(
    context: Context,
    /** false: the read-only bar of the D-pad seek overlay (no focus, no thumb) */
    private val interactive: Boolean,
) : View(context) {
    var accent: Int = NeonColors.VOLT
        set(value) {
            field = value
            invalidate()
        }

    /** Live player progress (0..1) */
    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var buffered: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var durationMs: Long = 0L
    var seekBack: Duration = Duration.ZERO
    var seekForward: Duration = Duration.ZERO
    var controllerViewState: ControllerViewState? = null

    /** The new position after a LEFT/RIGHT press */
    var onSeek: ((Long) -> Unit)? = null

    private var hasSeeked = false
    private var seekPositionMs = 0L
    private var leftHandledByRepeat = false
    private var rightHandledByRepeat = false
    private val neon = AppTheme.isNeon
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var barHeight: Float = context.dpF(if (neon) 4f else 6f)
    private var heightAnimator: ValueAnimator? = null

    init {
        isFocusable = interactive
        isEnabled = interactive
    }

    private fun shownProgress(): Float {
        if (interactive && isFocused && hasSeeked && durationMs > 0) {
            return (seekPositionMs.toDouble() / durationMs).toFloat()
        }
        return progress
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight.toInt().coerceAtLeast(1))
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) hasSeeked = false
        if (!neon) {
            // The stock bar doubles its height when focused
            heightAnimator?.cancel()
            val target = context.dpF(if (gainFocus) 12f else 6f)
            heightAnimator =
                PlaybackAnim.value(barHeight, target, 200L) {
                    barHeight = it
                    requestLayout()
                }
        }
        invalidate()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (!interactive) return super.onKeyDown(keyCode, event)
        val left = PlaybackKeys.isDpadLeft(event)
        val right = PlaybackKeys.isDpadRight(event)
        if (!left && !right) return super.onKeyDown(keyCode, event)
        val repeat = event.repeatCount
        if (repeat > 0) {
            val multiplier = calculateSeekAccelerationMultiplier(repeatCount = repeat, durationMs = durationMs)
            if (left) {
                leftHandledByRepeat = true
                seekLeft(multiplier)
            } else {
                rightHandledByRepeat = true
                seekRight(multiplier)
            }
        } else {
            if (left) leftHandledByRepeat = false else rightHandledByRepeat = false
        }
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (!interactive) return super.onKeyUp(keyCode, event)
        val left = PlaybackKeys.isDpadLeft(event)
        val right = PlaybackKeys.isDpadRight(event)
        if (!left && !right) return super.onKeyUp(keyCode, event)
        if (left) {
            if (!leftHandledByRepeat) seekLeft(1)
            leftHandledByRepeat = false
        } else {
            if (!rightHandledByRepeat) seekRight(1)
            rightHandledByRepeat = false
        }
        return true
    }

    private fun current(): Long = if (isFocused && hasSeeked) seekPositionMs else (progress * durationMs).toLong()

    private fun seekLeft(multiplier: Int) {
        controllerViewState?.pulseControls()
        seekPositionMs = (current() - seekBack.inWholeMilliseconds * multiplier).coerceAtLeast(0L)
        hasSeeked = true
        onSeek?.invoke(seekPositionMs)
        invalidate()
    }

    private fun seekRight(multiplier: Int) {
        controllerViewState?.pulseControls()
        seekPositionMs = (current() + seekForward.inWholeMilliseconds * multiplier).coerceAtMost(durationMs)
        hasSeeked = true
        onSeek?.invoke(seekPositionMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = context.dpF(4f)
        val w = width - inset * 2
        val h = height.toFloat()
        if (w <= 0) return
        val p = shownProgress().coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it }
        val b = buffered.coerceIn(0f, 1f).let { if (it.isNaN()) 0f else it }
        canvas.save()
        canvas.translate(inset, 0f)
        val y = h / 2f
        if (neon) {
            val x = w * p
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = NeonColors.LINE2
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.color = withAlpha(NeonColors.MID, .35f)
            canvas.drawRect(0f, 0f, w * b, h, paint)
            // Glow under the fill, then the solid fill so a box without shadows still reads
            val g = context.dpF(10f)
            val glow = withAlpha(neonGlowAccent(accent), .35f)
            paint.shader =
                LinearGradient(0f, y - g, 0f, y + g, intArrayOf(Color.TRANSPARENT, glow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, y - g, x, y + g, paint)
            paint.shader = null
            paint.color = accent
            canvas.drawRect(0f, 0f, x, h, paint)
            if (interactive) {
                val thumb = context.dpF(14f)
                paint.color = NeonColors.TEXT
                canvas.drawRect(x - thumb / 2, y - thumb / 2, x + thumb / 2, y + thumb / 2, paint)
                val inner = thumb - context.dpF(4f)
                paint.color = accent
                canvas.drawRect(x - inner / 2, y - inner / 2, x + inner / 2, y + inner / 2, paint)
            }
        } else {
            val pal = AppTheme.palette
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = h
            paint.color = withAlpha(pal.onSurface, .25f)
            canvas.drawLine(0f, y, w, y, paint)
            paint.color = withAlpha(pal.onSurface, .65f)
            canvas.drawLine(0f, y, w * b, y, paint)
            paint.color = pal.border
            canvas.drawLine(0f, y, w * p, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(w * p, y, h + 2, paint)
        }
        canvas.restore()
    }
}

/** Position on the left and "-remaining" on the right under the seek bar (`SeekTimecodes`), always LTR */
internal class SeekTimecodesView(
    context: Context,
) : LinearLayout(context) {
    private val position: TextView
    private val remaining: TextView
    private var lastPositionSec = Long.MIN_VALUE
    private var lastRemainingSec = Long.MIN_VALUE

    init {
        orientation = HORIZONTAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        val neon = AppTheme.isNeon

        fun make(color: Int) =
            context.textView(color = color).apply {
                if (neon) PlaybackText.numeral(this, 16f) else PlaybackText.role(this, TextRole.LABEL_LARGE)
                val pad = context.dp(8)
                setPadding(pad, pad, pad, pad)
                textDirection = TEXT_DIRECTION_LOCALE
            }
        position = make(AppTheme.palette.onSurface)
        remaining = make(if (neon) NeonColors.MID else AppTheme.palette.onSurface)
        addView(position, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(View(context), LayoutParams(0, 0, 1f))
        addView(remaining, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        positionMs: Long,
        durationMs: Long,
    ) {
        val positionSec = positionMs / 1000
        val remainingSec = (durationMs - positionMs) / 1000
        if (positionSec != lastPositionSec) {
            lastPositionSec = positionSec
            position.text = resources.formatDuration(positionSec.seconds)
        }
        if (remainingSec != lastRemainingSec) {
            lastRemainingSec = remainingSec
            remaining.text = "-${resources.formatDuration(remainingSec.seconds)}"
        }
    }
}

/**
 * The trickplay thumbnail and the time under it (`TrickplayPreview` + `SeekPreviewImage`): a 160dp tall tile cut out
 * of the server's sprite sheet with a 1.5dp border, then the time on a 60 % black chip.
 */
internal class TrickplayPreviewView(
    context: Context,
) : LinearLayout(context) {
    private val image = TileView(context)
    private val time: TextView
    private var info: TrickplayInfo? = null
    private var urlFor: ((Int) -> String?)? = null
    private var lastSec = Long.MIN_VALUE

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(image, LayoutParams(0, context.dp(160)).apply { bottomMargin = context.dp(8) })
        time =
            context.textView(TextRole.LABEL_LARGE).apply {
                background =
                    GradientDrawable().apply {
                        setColor(PlaybackColors.BLACK_60)
                        cornerRadius = context.dpF(4f)
                    }
                setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            }
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTrickplay(
        trickplayInfo: TrickplayInfo?,
        trickplayUrlFor: (Int) -> String?,
    ) {
        info = trickplayInfo
        urlFor = trickplayUrlFor
        image.visibility = GONE
    }

    fun bind(seekProgressMs: Long) {
        val sec = seekProgressMs / 1000L
        if (sec != lastSec) {
            lastSec = sec
            time.text = resources.formatDuration(sec.seconds)
        }
        val ti = info
        if (ti == null || ti.interval <= 0 || ti.tileWidth <= 0 || ti.tileHeight <= 0 || ti.height <= 0) {
            image.visibility = GONE
            return
        }
        val tilesPerImage = ti.tileWidth * ti.tileHeight
        val index = (seekProgressMs / ti.interval).toInt() / tilesPerImage
        val url = urlFor?.invoke(index)
        if (url.isNotNullOrBlank()) {
            val lp = image.layoutParams
            val w = (context.dp(160) * (ti.width.toFloat() / ti.height)).toInt()
            if (lp.width != w) {
                lp.width = w
                image.layoutParams = lp
            }
            image.visibility = VISIBLE
            image.bind(url, seekProgressMs, ti)
        } else {
            image.visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        image.dispose()
    }

    /** Draws one tile of the sprite sheet scaled to the view */
    private class TileView(
        context: Context,
    ) : View(context) {
        private var url: String? = null
        private var drawable: Drawable? = null
        private var request: Disposable? = null
        private var tileX = 0
        private var tileY = 0
        private var info: TrickplayInfo? = null
        private val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = context.dpF(1.5f)
            }

        fun bind(
            newUrl: String,
            seekProgressMs: Long,
            ti: TrickplayInfo,
        ) {
            info = ti
            val index = (seekProgressMs.toDouble() / ti.interval).toInt()
            val tileIndex = index % (ti.tileHeight * ti.tileWidth)
            tileX = tileIndex % ti.tileWidth
            tileY = tileIndex / ti.tileWidth
            if (newUrl != url) {
                url = newUrl
                request?.dispose()
                request =
                    context.imageLoader.enqueue(
                        ImageRequest
                            .Builder(context)
                            .data(newUrl)
                            .size(Size.ORIGINAL)
                            .target(onSuccess = { result ->
                                drawable = result.asDrawable(resources)
                                invalidate()
                            })
                            .build(),
                    )
            }
            invalidate()
        }

        fun dispose() {
            request?.dispose()
            request = null
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val ti = info
            val d = drawable
            if (ti != null && d != null && ti.width > 0) {
                val scale = width.toFloat() / ti.width
                canvas.save()
                canvas.clipRect(0, 0, width, height)
                canvas.scale(scale, scale)
                canvas.translate(-tileX.toFloat() * ti.width, -tileY.toFloat() * ti.height)
                d.setBounds(0, 0, ti.width * ti.tileWidth, ti.height * ti.tileHeight)
                d.draw(canvas)
                canvas.restore()
            }
            borderPaint.color = AppTheme.palette.border
            val half = borderPaint.strokeWidth / 2
            canvas.drawRect(half, half, width - half, height - half, borderPaint)
        }
    }
}
