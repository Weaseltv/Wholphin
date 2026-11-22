package com.github.damontecres.wholphin.leanback.shell

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import coil3.load
import coil3.request.transitionFactory
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.preferences.BackdropStyle
import com.github.damontecres.wholphin.services.BackdropResult
import com.github.damontecres.wholphin.services.NavTiming
import com.github.damontecres.wholphin.ui.CrossFadeFactory
import kotlin.time.Duration.Companion.milliseconds

/** Top scrim for text readability (clock, season tabs): the old `TOP_SCRIM_ALPHA` / `TOP_SCRIM_END_FRACTION` */
internal const val TOP_SCRIM_ALPHA = 0.55f
internal const val TOP_SCRIM_END_FRACTION = 0.25f // Fraction of backdrop image height

/**
 * The shared backdrop behind the pages (the old Compose `Backdrop`): the focused item's picture in the top-right 70 %
 * of the screen, faded out towards the left and bottom, with the top scrim; darkened while the side menu is open.
 * With the dynamic colour style, colours from the picture glow from the corners behind it (1.25 s colour changes).
 */
@SuppressLint("ViewConstructor")
internal class BackdropView(
    context: Context,
) : FrameLayout(context) {
    private val colors = DynamicColorView(context)
    private val image = BackdropImageView(context)

    private var current: BackdropResult = BackdropResult.NONE
    private var style: BackdropStyle = BackdropStyle.BACKDROP_DYNAMIC_COLOR
    private var loadedUrl: String? = null
    private var loaded = false

    init {
        addView(colors, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(0, 0))
        isFocusable = false
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        // The picture fills 70 % of the width and height, aligned top end
        val w = right - left
        val h = bottom - top
        val iw = (w * .7f).toInt()
        val ih = (h * .7f).toInt()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            image.layout(0, 0, iw, ih)
        } else {
            image.layout(w - iw, 0, w, ih)
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        image.measure(
            MeasureSpec.makeMeasureSpec((measuredWidth * .7f).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight * .7f).toInt(), MeasureSpec.EXACTLY),
        )
    }

    /** Whether the side menu is open: the picture is darkened by 75 % */
    fun setDrawerOpen(open: Boolean) {
        image.drawerOpen = open
    }

    /** Show [backdrop] with [backdropStyle] */
    fun bind(
        backdrop: BackdropResult,
        backdropStyle: BackdropStyle,
    ) {
        current = backdrop
        style = backdropStyle
        val dynamic =
            backdrop.hasColors &&
                (backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR || backdropStyle == BackdropStyle.UNRECOGNIZED)
        if (dynamic) {
            colors.visibility = VISIBLE
            colors.setColors(backdrop.primaryColor ?: 0, backdrop.secondaryColor ?: 0, backdrop.tertiaryColor ?: 0)
        } else {
            colors.visibility = GONE
            colors.reset()
        }
        if (backdropStyle == BackdropStyle.BACKDROP_NONE) {
            image.visibility = GONE
            return
        }
        image.visibility = VISIBLE
        val url = backdrop.imageUrl
        if (loaded && url == loadedUrl) return
        loaded = true
        loadedUrl = url
        image.load(url) {
            transitionFactory(CrossFadeFactory(800.milliseconds))
            // Speed plan step 0: log line only
            listener(onSuccess = { _, _ -> NavTiming.mark("backdrop shown", once = true) })
        }
    }

    /** Re-reads the theme's background colour */
    fun onThemeChanged() {
        colors.invalidate()
    }

    /** The colours from the picture, each glowing from a corner over the theme background */
    private class DynamicColorView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var primary = 0
        private var secondary = 0
        private var tertiary = 0
        private var hasColors = false
        private var animator: ValueAnimator? = null
        private val evaluator = ArgbEvaluator()

        fun reset() {
            animator?.cancel()
            hasColors = false
        }

        fun setColors(
            p: Int,
            s: Int,
            t: Int,
        ) {
            if (!hasColors) {
                // First appearance: no animation (animateColorAsState starts at its first value)
                hasColors = true
                primary = p
                secondary = s
                tertiary = t
                invalidate()
                return
            }
            if (p == primary && s == secondary && t == tertiary) return
            val fromP = primary
            val fromS = secondary
            val fromT = tertiary
            animator?.cancel()
            animator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1250
                    interpolator = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)
                    addUpdateListener {
                        val f = it.animatedFraction
                        primary = evaluator.evaluate(f, fromP, p) as Int
                        secondary = evaluator.evaluate(f, fromS, s) as Int
                        tertiary = evaluator.evaluate(f, fromT, t) as Int
                        invalidate()
                    }
                    start()
                }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val base = AppTheme.palette.background
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val start = if (rtl) w else 0f
            val end = if (rtl) 0f else w
            val radius = w * .8f
            paint.shader = null
            paint.color = base
            canvas.drawRect(0f, 0f, w, h, paint)

            fun radial(
                color: Int,
                cx: Float,
                cy: Float,
            ) {
                paint.shader = RadialGradient(cx, cy, radius, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            // Top start (vibrant/muted), bottom end (dark vibrant/dark muted), bottom start (bridge), top end (under the image)
            radial(secondary, start, 0f)
            radial(primary, end, h)
            radial(base, start, h)
            radial(tertiary, end, 0f)
            paint.shader = null
        }
    }

    /**
     * The picture: fitted inside its box, aligned top end, then darkened (menu open), the top scrim, and faded out
     * towards the start and the bottom (drawn in an offscreen layer, as the Compose version was)
     */
    private class BackdropImageView(
        context: Context,
    ) : AppCompatImageView(context) {
        var drawerOpen = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val dim = Paint().apply { color = 0xBF000000.toInt() }
        private val scrim = Paint()
        private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private val fitMatrix = Matrix()

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun setFrame(
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ): Boolean {
            val changed = super.setFrame(l, t, r, b)
            updateMatrix()
            return changed
        }

        override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
            super.setImageDrawable(drawable)
            updateMatrix()
        }

        private fun updateMatrix() {
            val d = drawable ?: return
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) {
                imageMatrix = null
                return
            }
            // ContentScale.Fit, Alignment.TopEnd
            val scale = minOf(vw / dw, vh / dh)
            fitMatrix.reset()
            fitMatrix.setScale(scale, scale)
            val dx = if (layoutDirection == LAYOUT_DIRECTION_RTL) 0f else vw - dw * scale
            fitMatrix.postTranslate(dx, 0f)
            imageMatrix = fitMatrix
        }

        override fun draw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            super.draw(canvas)
            if (drawerOpen) canvas.drawRect(0f, 0f, w, h, dim)
            // Subtle top scrim for system UI readability (clock, tabs)
            scrim.shader =
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    h * TOP_SCRIM_END_FRACTION,
                    (((TOP_SCRIM_ALPHA * 255).toInt()) shl 24),
                    0,
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(0f, 0f, w, h, scrim)
            val start = if (layoutDirection == LAYOUT_DIRECTION_RTL) w else 0f
            mask.shader =
                LinearGradient(start, 0f, w * .6f, 0f, 0, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, mask)
            mask.shader = LinearGradient(0f, 0f, 0f, h, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, mask)
            canvas.restoreToCount(save)
        }
    }
}
