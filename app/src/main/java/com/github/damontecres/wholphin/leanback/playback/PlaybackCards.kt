package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import coil3.load
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.roundSeconds

/*
 * Private card views of the player (no-Compose UI, 2026-11-22): the chapter card, the queue's season card and the
 * next up card, with the tv `Card` focus recipe (`neonCard*` on the WeaselTV theme). The CARDS area owns the shared
 * versions; these copy only what the player showed.
 */

/**
 * A card frame: the outer view is focusable and draws the glow and container behind and the border in front; the
 * inner [content] is clipped to the card's corners.
 */
internal open class CardFrame(
    context: Context,
    style: SurfaceStyle,
    focusScale: Float,
) : FrameLayout(context) {
    val content = FrameLayout(context)

    private var corner = 0f

    init {
        isFocusable = true
        isClickable = true
        content.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) {
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
            }
        content.isDuplicateParentStateEnabled = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyStyle(style)
        bindFocus(focusScale)
    }

    /** Glow and container behind, border in front, content clipped to the corners */
    fun applyStyle(style: SurfaceStyle) {
        background = SurfaceDrawable(style.copy(border = null, focusedBorder = null, disabledBorder = null, focusedDisabledBorder = null))
        foreground =
            SurfaceDrawable(
                style.copy(
                    container = Color.TRANSPARENT,
                    focusedContainer = Color.TRANSPARENT,
                    disabledContainer = Color.TRANSPARENT,
                    selectedContainer = Color.TRANSPARENT,
                    focusedSelectedContainer = Color.TRANSPARENT,
                    glow = null,
                ),
            )
        corner = if (style.corner < 0) 0f else style.corner
        content.clipToOutline = corner > 0f
        content.invalidateOutline()
    }
}

/** `ChapterCard`: 120dp tall, 16:9, the chapter image with its name and time on a 50 % black strip */
internal class ChapterCardView(
    context: Context,
    appearance: HomeCardAppearance,
    accent: Int,
) : CardFrame(
        context,
        CardFocus.style(context, appearance, accent, AppTheme.palette.surfaceVariant),
        CardFocus.scale(appearance),
    ) {
    private val image = ImageView(context)
    private val name: TextView
    private val time: TextView
    private var request: Disposable? = null

    init {
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        content.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val strip =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PlaybackColors.BLACK_50)
                val pad = context.dp(4)
                setPadding(pad, pad, pad, pad)
            }
        val color = AppTheme.palette.onSurfaceVariant
        name = context.textView(TextRole.BODY_MEDIUM, color, maxLines = 2, ellipsize = TextUtils.TruncateAt.END)
        name.applyWeight(600)
        time = context.textView(TextRole.BODY_SMALL, color)
        time.applyWeight(400)
        strip.addView(name)
        strip.addView(time)
        content.addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun bind(
        chapter: Chapter,
        imageUrl: String?,
    ) {
        name.text = chapter.name
        name.isVisible = chapter.name != null
        time.text = resources.formatDuration(chapter.position.roundSeconds)
        request?.dispose()
        request = image.load(imageUrl)
    }

    companion object {
        const val HEIGHT_DP = 120f
        val WIDTH_DP = AspectRatios.WIDE * HEIGHT_DP
    }
}

/** Set a font weight without changing the role's size (`fontWeight = …` overrides in the old code) */
internal fun TextView.applyWeight(weight: Int) {
    typeface =
        com.github.damontecres.wholphin.leanback.theme.AppFonts
            .body(context, weight)
}

/**
 * The queue's `SeasonCard` (image height 140dp, no badges): the poster in the item's accent recipe, then the title
 * and subtitle that slide down 8dp when focused (`SlidingCardText`).
 */
internal class QueueCardView(
    context: Context,
    private val appearance: HomeCardAppearance,
    private val imageHeightDp: Int,
) : LinearLayout(context) {
    val card =
        CardFrame(
            context,
            CardFocus.style(context, appearance, NeonColors.VOLT, Color.TRANSPARENT),
            CardFocus.scale(appearance),
        )
    private val image = ImageView(context)
    private val fallback = FrameLayout(context)
    private val glyph = ImageView(context)
    private val textBlock = LinearLayout(context)
    private val title: TextView
    private val subtitle: TextView
    private val neon = AppTheme.isNeon
    private var request: Disposable? = null
    var onCardFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        noClip()
        addView(card, LayoutParams(0, context.dp(imageHeightDp)))
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        card.content.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // ItemCardImageFallback: `well` with a 1dp `line` edge (surfaceVariant elsewhere) and the video glyph at 40 %
        fallback.background =
            if (neon) {
                GradientDrawable().apply {
                    setColor(NeonColors.WELL)
                    setStroke(context.dp(1), NeonColors.LINE)
                }
            } else {
                ColorDrawable(AppTheme.palette.surfaceVariant)
            }
        glyph.setImageResource(R.drawable.video_solid)
        glyph.imageTintList =
            android.content.res.ColorStateList
                .valueOf(AppTheme.palette.onSurfaceVariant)
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        fallback.addView(glyph, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        fallback.visibility = GONE
        card.content.addView(
            fallback,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        textBlock.orientation = VERTICAL
        title =
            context.textView(maxLines = 2, ellipsize = TextUtils.TruncateAt.END).apply {
                PlaybackText.cardTitle(this)
                gravity = Gravity.CENTER_HORIZONTAL
                isSingleLine = false
                setPadding(context.dp(4), 0, context.dp(4), 0)
            }
        subtitle =
            context.textView(maxLines = 1, ellipsize = TextUtils.TruncateAt.END).apply {
                PlaybackText.cardSubtitle(this)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(context.dp(4), 0, context.dp(4), 0)
                if (neon) setTextColor(NeonColors.LOW)
            }
        textBlock.addView(title)
        textBlock.addView(subtitle)
        // 4dp column spacing, then SlidingCardText: 12dp total, 0 above / 12 below at rest, 8 above / 4 below focused
        addView(textBlock, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
        textBlock.setPadding(0, 0, 0, context.dp(12))
        setFocused(false, animate = false)
        val prior = card.onFocusChangeListener
        card.setOnFocusChangeListener { v, f ->
            prior?.onFocusChange(v, f)
            setFocused(f, animate = true)
            onCardFocus?.invoke(f)
        }
    }

    private fun setFocused(
        focused: Boolean,
        animate: Boolean,
    ) {
        val p = AppTheme.palette
        title.setTextColor(if (neon) (if (focused) NeonColors.TEXT else NeonColors.MID) else p.onSurface)
        if (!neon) subtitle.setTextColor(p.onSurface)
        val shift = if (focused) context.dpF(8f) else 0f
        if (animate) {
            textBlock
                .animate()
                .translationY(shift)
                .setDuration(NeonColors.FOCUS_MS)
                .start()
        } else {
            textBlock.translationY = shift
        }
    }

    /** Bind an item: its accent, aspect ratio (at least 2:3), image and texts */
    fun bind(
        item: BaseItem,
        accent: Int,
        imageUrl: String?,
    ) {
        card.applyStyle(CardFocus.style(context, appearance, accent, Color.TRANSPARENT))
        val ratio = (item.aspectRatio ?: AspectRatios.TALL).coerceAtLeast(AspectRatios.MIN)
        val h = context.dp(imageHeightDp)
        val w = (h * ratio).toInt()
        (card.layoutParams as LayoutParams).width = w
        (textBlock.layoutParams as LayoutParams).width = w
        (glyph.layoutParams as FrameLayout.LayoutParams).apply {
            width = (w * .4f).toInt()
            height = (h * .4f).toInt()
        }
        requestLayout()
        title.text = item.title.orEmpty()
        subtitle.text = item.subtitle
        subtitle.isVisible = !item.subtitle.isNullOrBlank()
        card.contentDescription = item.title
        request?.dispose()
        if (imageUrl.isNotNullOrBlank()) {
            image.isVisible = true
            fallback.isVisible = false
            request =
                image.load(imageUrl) {
                    listener(
                        onError = { _: ImageRequest, _ ->
                            image.isVisible = false
                            fallback.isVisible = true
                        },
                    )
                }
        } else {
            image.setImageDrawable(null)
            image.isVisible = false
            fallback.isVisible = true
        }
    }
}
