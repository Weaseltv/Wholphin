package com.github.damontecres.wholphin.leanback.widget.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonRulePainter
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.NeonBadgePainter
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.watchedIconBackground
import com.github.damontecres.wholphin.leanback.theme.watchedIconColor
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `SlidingCardText`: the caption under a card. 12dp of padding is shared between above and below the text: none above
 * and 12dp below at rest, 8dp above and 4dp below while the card is focused, so the text moves down as the card scales
 * up. The caption's own height never changes.
 */
open class CardCaptionView(
    context: Context,
) : LinearLayout(context) {
    private val slidePx = context.dpF((CardDimens.SLIDE_TOTAL_DP - CardDimens.SLIDE_FOCUSED_BOTTOM_DP).toFloat())
    private var captionFocused = false

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, context.dp(CardDimens.SLIDE_TOTAL_DP))
    }

    /** Slide for the card's focus state (animated like `animateDpAsState`, a critically damped spring) */
    open fun setCardFocused(
        focused: Boolean,
        animate: Boolean = true,
    ) {
        if (focused == captionFocused && animate) return
        captionFocused = focused
        val target = if (focused) slidePx else 0f
        animate().cancel()
        if (animate) {
            animate().translationY(target).setDuration(SLIDE_MS).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            translationY = target
        }
    }

    companion object {
        private const val SLIDE_MS = 150L
    }
}

/**
 * The poster caption (`PosterCardTitle` + `PosterCardSubtitle`): a centred title of up to two lines (Neon: mid grey,
 * white while focused) and a one-line subtitle hidden when blank, both with 4dp side padding.
 */
class PosterCaptionView(
    context: Context,
) : CardCaptionView(context) {
    val title: AppCompatTextView = captionText(context, maxLines = 2)
    val subtitle: AppCompatTextView = captionText(context, maxLines = 1)
    private var focused = false

    init {
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        title: String?,
        subtitle: String?,
    ) {
        this.title.applySpec(CardTextStyles.posterTitle())
        this.subtitle.applySpec(CardTextStyles.posterSubtitle())
        this.title.text = title.orEmpty()
        this.subtitle.text = subtitle.orEmpty()
        this.subtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        applyColors()
    }

    override fun setCardFocused(
        focused: Boolean,
        animate: Boolean,
    ) {
        this.focused = focused
        applyColors()
        super.setCardFocused(focused, animate)
    }

    private fun applyColors() {
        if (AppTheme.isNeon) {
            title.setTextColor(if (focused) NeonColors.TEXT else NeonColors.MID)
            subtitle.setTextColor(NeonColors.LOW)
        } else {
            title.setTextColor(AppTheme.palette.onSurface)
            subtitle.setTextColor(AppTheme.palette.onSurface)
        }
    }

    companion object {
        /** Height of a poster caption with [titleLines] title lines and a subtitle, padding included (`gridCardHeight`) */
        fun heightPx(
            context: Context,
            titleLines: Int,
            withSubtitle: Boolean = true,
        ): Int =
            context.linesHeightPx(CardTextStyles.posterTitle(), titleLines) +
                (if (withSubtitle) context.linesHeightPx(CardTextStyles.posterSubtitle(), 1) else 0) +
                context.dp(CardDimens.SLIDE_TOTAL_DP)
    }
}

internal fun captionText(
    context: Context,
    maxLines: Int,
): AppCompatTextView =
    AppCompatTextView(context).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        this.maxLines = maxLines
        if (maxLines == 1) isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        val pad = context.dp(4)
        setPaddingRelative(pad, 0, pad, 0)
    }

/** Gives every visible child after the first [spacingPx] of start margin (`Arrangement.spacedBy`) */
internal fun LinearLayout.spaceChildren(spacingPx: Int) {
    var first = true
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child.visibility == View.GONE) continue
        val lp = child.layoutParams as LinearLayout.LayoutParams
        val margin = if (first) 0 else spacingPx
        if (orientation == LinearLayout.HORIZONTAL) {
            if (lp.marginStart != margin) {
                lp.marginStart = margin
                child.layoutParams = lp
            }
        } else if (lp.topMargin != margin) {
            lp.topMargin = margin
            child.layoutParams = lp
        }
        first = false
    }
}

/**
 * `NeonBadge`: the WeaselTV poster type badge (MOVIE / SHOW / S2 · E4 …), uppercase in the accent on a dark fill with
 * a 1dp accent outline and a soft glow, sized by the poster count appearance. Only shown on the WeaselTV theme.
 * Draws its glow outside its bounds; its parent must not clip children.
 */
class NeonBadgeView(
    context: Context,
) : AppCompatTextView(context) {
    private val painter = NeonBadgePainter(context)
    private var accent = NeonColors.VOLT
    private var filled = false

    init {
        maxLines = 1
        isSingleLine = true
    }

    /** @param countAppearance `LocalPosterCountAppearance` (text size and padding), or null for 11sp / 4dp */
    fun bind(
        text: String,
        accent: Int,
        countAppearance: HomeCardAppearance?,
        filled: Boolean = false,
    ) {
        this.accent = accent
        this.filled = filled
        val padH = context.dp(countAppearance?.badgeHorizontalPaddingDp ?: 4)
        val padV = context.dp(countAppearance?.badgeVerticalPaddingDp ?: 4)
        setPaddingRelative(padH, 0, padH, 0)
        applySpec(roleSpec(TextRole.BODY_SMALL, sizeSp = (countAppearance?.badgeTextSizeSp ?: 11).toFloat()).copy(trackingEm = 0f))
        setPadding(paddingLeft, paddingTop + padV, paddingRight, paddingBottom + padV)
        this.text = text.uppercase()
        setTextColor(if (filled) NeonColors.ON_ACCENT else accent)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, width.toFloat(), height.toFloat(), accent, filled)
        super.onDraw(canvas)
    }
}

/**
 * A count / corner badge: text on a translucent black rounded box (`RoundedCornerShape(percent)`), as the unwatched
 * count, versions count and episode corner text of the old cards.
 */
class CountBadgeView(
    context: Context,
) : AppCompatTextView(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var cornerPercent = 25

    init {
        maxLines = 1
        isSingleLine = true
    }

    internal fun bind(
        text: String,
        spec: TextSpec,
        textAlpha: Float,
        backgroundColor: Int,
        cornerPercent: Int,
        paddingHorizontalPx: Int,
        paddingVerticalPx: Int,
    ) {
        this.cornerPercent = cornerPercent
        bgPaint.color = backgroundColor
        setPaddingRelative(paddingHorizontalPx, 0, paddingHorizontalPx, 0)
        applySpec(spec)
        setPadding(paddingLeft, paddingTop + paddingVerticalPx, paddingRight, paddingBottom + paddingVerticalPx)
        this.text = text
        // `onSurface.copy(alpha = …)` replaced the colour's alpha
        setTextColor(withAlpha(AppTheme.palette.onSurface, textAlpha))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = cornerPercent.coerceIn(0, 50) / 100f * min(width, height)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        super.onDraw(canvas)
    }
}

/**
 * `WatchedIcon`: a check on the theme's watched colour, 24dp with 2dp padding and a .5dp black edge. A square on the
 * WeaselTV theme (dark check on green), a circle elsewhere (white check).
 */
class WatchedIconView(
    context: Context,
) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val check: Drawable =
        DrawableCompat.wrap(ContextCompat.getDrawable(context, R.drawable.lb_widget_card_check)!!.mutate())
    private val paddingPx = context.dp(2)

    init {
        edgePaint.strokeWidth = context.dpF(.5f)
        edgePaint.color = 0xFF000000.toInt()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = context.dp(24)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val half = edgePaint.strokeWidth / 2
        bgPaint.color = watchedIconBackground()
        rect.set(0f, 0f, w, h)
        val neon = AppTheme.isNeon
        if (neon) canvas.drawRect(rect, bgPaint) else canvas.drawOval(rect, bgPaint)
        rect.set(half, half, w - half, h - half)
        if (neon) canvas.drawRect(rect, edgePaint) else canvas.drawOval(rect, edgePaint)
        DrawableCompat.setTint(check, watchedIconColor())
        check.setBounds(paddingPx, paddingPx, width - paddingPx, height - paddingPx)
        check.draw(canvas)
    }
}

/** `FavoriteIndicator`: the Font Awesome heart in light red ([sizeSp] 20sp, 16sp on banner cards) */
@SuppressLint("ViewConstructor")
class FavoriteView(
    context: Context,
    sizeSp: Float = 20f,
) : AppCompatTextView(context) {
    init {
        setText(R.string.fa_heart)
        setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_light))
        maxLines = 1
        setSize(sizeSp)
    }

    fun setSize(sizeSp: Float) {
        val spec = CardTextStyles.bodyLargeAt(sizeSp)
        applySpec(spec)
        typeface = AppFonts.icons(context)
        applyLineHeight(context.spPx(spec.lineHeightSp).roundToInt())
    }
}

/**
 * The watched-progress bar along a card's bottom edge. WeaselTV: a 3dp bar in [accent] with an 8dp glow fading up from
 * it (`neonProgress`); other themes: a 6dp bar in `tertiary`. Lay it out full width at the bottom with [heightPx] (it
 * draws only the played fraction).
 */
class CardProgressView(
    context: Context,
) : View(context) {
    private val painter = NeonRulePainter()
    private val paint = Paint()
    private var fraction = 0f
    private var accent = NeonColors.VOLT

    fun bind(
        percent: Double,
        accent: Int,
    ) {
        fraction = (percent / 100.0).toFloat().coerceIn(0f, 1f)
        this.accent = accent
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightPx(context))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width * fraction
        if (w <= 0f) return
        val h = height.toFloat()
        if (AppTheme.isNeon) {
            painter.draw(canvas, w, h, accent, context.dpF(3f), context.dpF(8f), .56f)
        } else {
            paint.color = AppTheme.palette.tertiary
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    companion object {
        /** Neon: the 8dp glow box ending in the 3dp bar; otherwise the 6dp bar */
        fun heightPx(context: Context): Int = if (AppTheme.isNeon) context.dp(8) else context.dp(CardDimens.PLAYED_PERCENT_HEIGHT_DP)
    }
}
