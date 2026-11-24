package com.github.damontecres.wholphin.leanback.widget.control

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.services.NavTiming
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkPreload
import com.github.damontecres.wholphin.ui.theme.LoadingArtworkRotation

/**
 * What the shell tells the loading views (the old `LocalKidsLoadingArtwork`): whether the signed-in profile is a kid,
 * so loading shows the kids' artwork; null before a profile is signed in, when the last profile's choice is used.
 */
object LoadingArtwork {
    @Volatile
    var signedInKids: Boolean? = null
}

/**
 * The spinner (`CircularProgress`): an indeterminate circular progress indicator in the border colour (the page accent
 * on the Neon theme). Size it with layout params (the old modifier size; 48dp on loading pages).
 */
class CircularProgressView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ProgressBar(context, attrs, android.R.attr.progressBarStyleLarge) {
        init {
            isIndeterminate = true
            isFocusable = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            val color = if (AppTheme.isNeon) resolveNeonScope().accentOrDefault else AppTheme.palette.border
            indeterminateTintList = ColorStateList.valueOf(color)
        }
    }

/**
 * A loading page (`LoadingPage` / `DelayedLoadingPage`): fills its space, takes focus (unless [focusEnabled] is false)
 * so D-pad presses do not land behind it, and shows the Neon loading artwork ([NeonLoadingMarkView]) on the WeaselTV
 * theme or a 48dp spinner elsewhere. With [delayMs] > 0 (300 ms for `DelayedLoadingPage`) the indicator appears only
 * after that delay.
 *
 * Like the old page it logs "content after loading page" to [NavTiming] when it goes away, counted from the page change
 * that showed it.
 */
class LoadingPageView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        val focusEnabled: Boolean = true,
        val delayMs: Long = 0L,
    ) : FrameLayout(context, attrs) {
        private val trip = NavTiming.current()
        private val indicator: View =
            if (AppTheme.isNeon) {
                NeonLoadingMarkView(context)
            } else {
                CircularProgressView(context).apply {
                    layoutParams = LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER)
                }
            }
        private val reveal = Runnable { indicator.visibility = View.VISIBLE }

        init {
            isFocusable = focusEnabled
            isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            if (indicator.layoutParams == null) {
                indicator.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(indicator)
            if (delayMs > 0) indicator.visibility = View.INVISIBLE
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (delayMs > 0 && indicator.visibility != View.VISIBLE) postDelayed(reveal, delayMs)
            if (focusEnabled) requestFocus()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(reveal)
            NavTiming.mark("content after loading page", of = trip)
            super.onDetachedFromWindow()
        }
    }

/**
 * The Neon loading mark (`NeonLoadingMark`): the next picture of the bundled loading artwork (the kids' set for a kid
 * profile), fitted whole and centred on black; each new mark advances the rotation once and decodes the following
 * picture in the background. Flavours without artwork show the mascot (96dp) with its volt glow.
 */
class NeonLoadingMarkView(
    context: Context,
) : FrameLayout(context) {
    init {
        val kids = LoadingArtwork.signedInKids ?: LoadingArtworkRotation.lastProfileWasKids(context)
        val artworks =
            LoadingArtworkRotation.resourceNames(kids).mapNotNull { name ->
                context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
            }
        val artwork = LoadingArtworkRotation.next(context, artworks, kids)
        LoadingArtworkRotation.peek(context, artworks, kids)?.let { LoadingArtworkPreload.request(context.resources, it) }
        if (artwork != null) {
            setBackgroundColor(0xFF000000.toInt())
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(artwork)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        } else {
            val mascot = context.resources.getIdentifier("weaselplex_mascot", "drawable", context.packageName)
            if (mascot != 0) {
                val size = context.dp(NeonSizes.SCREENSAVER_MARK)
                addView(MascotView(context, mascot), LayoutParams(size, size, Gravity.CENTER))
            }
        }
    }
}

/** The mascot with its volt radial glow (`NeonMascot(glow = true)`) */
class MascotView(
    context: Context,
    drawableRes: Int,
    private val glow: Boolean = true,
) : ImageView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setImageResource(drawableRes)
        scaleType = ScaleType.FIT_CENTER
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (glow) {
            val r = minOf(width, height) * .9f
            if (r > 0f) {
                paint.shader =
                    RadialGradient(width / 2f, height / 2f, r, withAlpha(NeonColors.VOLT, .45f), 0, Shader.TileMode.CLAMP)
                canvas.drawCircle(width / 2f, height / 2f, r, paint)
            }
        }
        super.onDraw(canvas)
    }
}
