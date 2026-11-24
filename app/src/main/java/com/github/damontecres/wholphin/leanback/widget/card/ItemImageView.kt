package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.ui.formatEpisodeNumber
import org.jellyfin.sdk.model.api.BaseItemKind

/** What [ItemImageView] shows: the parameters of the old `ItemCardImage` / `ItemCardImageOverlay` */
data class ItemImageState(
    val imageUrl: String?,
    /** Image content description and fallback text */
    val name: String?,
    val showOverlay: Boolean,
    val favorite: Boolean,
    val watched: Boolean,
    val unwatchedCount: Int,
    val watchedPercent: Double?,
    val numberOfVersions: Int,
    /** Card accent: the type badge's colour */
    val accent: Int,
    /** Colour of the WeaselTV progress bar (the item's type accent) */
    val progressAccent: Int = accent,
    val typeBadge: String? = null,
    val useFallbackText: Boolean = true,
    val scale: CardImageScale = CardImageScale.FIT,
    val fallback: ItemImageFallback = ItemImageFallback.DEFAULT,
)

/** What an [ItemImageView] shows without an image */
enum class ItemImageFallback {
    /** `ItemCardImageFallback`: the name (when asked for) or a video glyph on the empty poster well */
    DEFAULT,

    /** `PersonCard`: the Font Awesome user glyph, 64sp, on surfaceVariant */
    PERSON,
}

/**
 * `ItemCardImage`: an item's image filling the card, the fallback when there is none or it fails to load, and the
 * overlay of badges (type badge, versions, watched check, unwatched count), favourite heart and progress bar.
 *
 * Put it in a card's [CardFrameView.content]. Badge sizes follow the page's poster count appearance
 * ([PageStyle.posterCountAppearance], WeaselTV only), the type badge is hidden by [PageStyle.hidePosterTypeBadge].
 */
class ItemImageView(
    context: Context,
) : FrameLayout(context) {
    val image: CardImageView = CardImageView(context)
    private val fallback = FallbackView(context)
    private val overlay = Overlay(context)
    private var state: ItemImageState? = null
    private var imageError = false

    init {
        clipChildren = false
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        state: ItemImageState,
        style: PageStyle,
    ) {
        val urlChanged = state.imageUrl != this.state?.imageUrl
        this.state = state
        image.imageScale = state.scale
        image.contentDescription = state.name
        if (urlChanged) {
            imageError = false
            if (state.imageUrl.isNullOrBlank()) {
                image.clear()
            } else {
                image.load(state.imageUrl, onError = {
                    if (this.state?.imageUrl == state.imageUrl) {
                        imageError = true
                        showImageOrFallback()
                    }
                })
            }
        }
        fallback.bind(state)
        showImageOrFallback()
        overlay.visibility = if (state.showOverlay) View.VISIBLE else View.GONE
        if (state.showOverlay) overlay.bind(state, style)
    }

    private fun showImageOrFallback() {
        val showImage = !imageError && !state?.imageUrl.isNullOrBlank()
        image.visibility = if (showImage) View.VISIBLE else View.INVISIBLE
        fallback.visibility = if (showImage) View.GONE else View.VISIBLE
    }

    /** Cancel the image load (recycling) */
    fun recycle() {
        image.clear()
        state = null
        imageError = false
    }

    /** `ItemCardImageFallback` / the person glyph */
    private class FallbackView(
        context: Context,
    ) : ViewGroup(context) {
        private val text =
            AppCompatTextView(context).apply {
                gravity = Gravity.CENTER
                val pad = context.dp(8)
                setPadding(pad, 0, pad, 0)
            }
        private val icon =
            AppCompatImageView(context).apply {
                setImageResource(R.drawable.video_solid)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        private val edge = Paint().apply { style = Paint.Style.STROKE }
        private var mode = ItemImageFallback.DEFAULT
        private var showText = false

        init {
            setWillNotDraw(false)
            addView(text)
            addView(icon)
            edge.strokeWidth = context.dpF(1f)
        }

        fun bind(state: ItemImageState) {
            mode = state.fallback
            val palette = AppTheme.palette
            when (mode) {
                ItemImageFallback.DEFAULT -> {
                    // Neon Board: an empty poster well is `well` with a 1dp `line` edge
                    setBackgroundColor(if (AppTheme.isNeon) NeonColors.WELL else palette.surfaceVariant)
                    showText = state.useFallbackText && !state.name.isNullOrBlank()
                    if (showText) {
                        // A start-aligned Text centred in the well
                        text.gravity = Gravity.START
                        text.applySpec(CardTextStyles.bodyLargeAt(14f))
                        text.text = state.name
                    }
                    text.setTextColor(palette.onSurfaceVariant)
                    ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(palette.onSurfaceVariant))
                }

                ItemImageFallback.PERSON -> {
                    setBackgroundColor(palette.surfaceVariant)
                    showText = true
                    text.gravity = Gravity.CENTER_HORIZONTAL
                    val spec = CardTextStyles.bodyLargeAt(64f)
                    text.applySpec(spec)
                    text.typeface = AppFonts.icons(context)
                    text.applyLineHeight(context.spPx(spec.lineHeightSp).toInt())
                    text.setText(R.string.fa_user)
                    text.setTextColor(palette.onSurfaceVariant)
                }
            }
            text.visibility = if (showText) View.VISIBLE else View.GONE
            icon.visibility = if (showText) View.GONE else View.VISIBLE
            invalidate()
            requestLayout()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            text.measure(
                MeasureSpec.makeMeasureSpec(w, if (mode == ItemImageFallback.PERSON) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST),
            )
            icon.measure(
                MeasureSpec.makeMeasureSpec((w * .4f).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * .4f).toInt(), MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val w = r - l
            val h = b - t
            val tx = (h - text.measuredHeight) / 2
            val tl = (w - text.measuredWidth) / 2
            text.layout(tl, tx, tl + text.measuredWidth, tx + text.measuredHeight)
            val iw = icon.measuredWidth
            val ih = icon.measuredHeight
            icon.layout((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (mode == ItemImageFallback.DEFAULT && AppTheme.isNeon) {
                edge.color = NeonColors.LINE
                val half = edge.strokeWidth / 2
                canvas.drawRect(half, half, width - half, height - half, edge)
            }
        }
    }

    /** `ItemCardImageOverlay` */
    private class Overlay(
        context: Context,
    ) : FrameLayout(context) {
        private val topRow = LinearLayout(context)
        private val left = LinearLayout(context)
        private val right = LinearLayout(context)
        private val typeBadge = NeonBadgeView(context)
        private val versions = CountBadgeView(context)
        private val watched = WatchedIconView(context)
        private val unwatched = CountBadgeView(context)
        private val favorite = FavoriteView(context)
        private val progress = CardProgressView(context)

        init {
            clipChildren = false
            for (group in listOf(topRow, left, right)) {
                group.orientation = LinearLayout.HORIZONTAL
                group.gravity = Gravity.CENTER_VERTICAL
                group.clipChildren = false
                group.clipToPadding = false
            }
            left.addView(typeBadge, LinearLayout.LayoutParams(WRAP, WRAP))
            left.addView(versions, LinearLayout.LayoutParams(WRAP, WRAP))
            right.addView(watched, LinearLayout.LayoutParams(WRAP, WRAP))
            right.addView(unwatched, LinearLayout.LayoutParams(WRAP, WRAP))
            topRow.addView(left, LinearLayout.LayoutParams(WRAP, WRAP))
            topRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            topRow.addView(right, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, WRAP, Gravity.TOP or Gravity.START))
            addView(favorite, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
            addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, WRAP, Gravity.BOTTOM or Gravity.START))
        }

        fun bind(
            state: ItemImageState,
            style: PageStyle,
        ) {
            val neon = AppTheme.isNeon
            // LocalCollectionCardAppearance / LocalPosterCountAppearance only applied on the WeaselTV theme
            val countAppearance = style.posterCountAppearance.takeIf { neon }
            val insetH = context.dp(countAppearance?.badgeHorizontalInsetDp ?: 4)
            val insetV = context.dp(countAppearance?.badgeVerticalInsetDp ?: 4)
            topRow.setPaddingRelative(insetH, insetV, insetH, insetV)

            val showType = neon && state.typeBadge != null && !style.hidePosterTypeBadge
            typeBadge.visibility = if (showType) View.VISIBLE else View.GONE
            if (showType) typeBadge.bind(state.typeBadge!!, state.accent, style.posterCountAppearance)

            val countSpec =
                if (countAppearance != null) {
                    roleSpec(TextRole.BODY_SMALL, sizeSp = countAppearance.badgeTextSizeSp?.toFloat())
                } else {
                    roleSpec(TextRole.BODY_MEDIUM)
                }
            val textAlpha = (countAppearance?.badgeTextOpacityPercent ?: 100) / 100f
            val padH = context.dp(countAppearance?.badgeHorizontalPaddingDp ?: 4)
            val padV = context.dp(countAppearance?.badgeVerticalPaddingDp ?: 4)
            versions.visibility = if (state.numberOfVersions > 1) View.VISIBLE else View.GONE
            if (state.numberOfVersions > 1) {
                // AppColors.TransparentBlack50 in a 25 % rounded box
                versions.bind(state.numberOfVersions.toString(), countSpec, textAlpha, 0x80000000.toInt(), 25, padH, padV)
            }
            val percent = state.watchedPercent
            val showWatched = state.watched && (percent == null || percent <= 0.0 || percent >= 100.0)
            watched.visibility = if (showWatched) View.VISIBLE else View.GONE
            unwatched.visibility = if (state.unwatchedCount > 0) View.VISIBLE else View.GONE
            if (state.unwatchedCount > 0) {
                val bgAlpha = (countAppearance?.badgeBackgroundOpacityPercent ?: 50) / 100f
                unwatched.bind(
                    state.unwatchedCount.toString(),
                    countSpec,
                    textAlpha,
                    (((bgAlpha.coerceIn(0f, 1f) * 255).toInt()) shl 24),
                    countAppearance?.badgeCornerPercent ?: 25,
                    padH,
                    padV,
                )
            }
            val gap = context.dp(4)
            left.spaceChildren(gap)
            right.spaceChildren(gap)

            favorite.visibility = if (state.favorite) View.VISIBLE else View.GONE
            (favorite.layoutParams as LayoutParams).apply {
                marginEnd = insetH
                bottomMargin = insetV
            }
            favorite.requestLayout()

            val showProgress = percent != null && percent > 0 && percent < 100
            progress.visibility = if (showProgress) View.VISIBLE else View.GONE
            if (showProgress) progress.bind(percent!!, state.progressAccent)
        }
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/**
 * The type badge text of an item's poster (`ItemCardImage`): S2 · E4 for an episode with season and episode numbers,
 * otherwise its kind (MOVIE, SHOW, SEASON, EPISODE, COLLECTION, PLAYLIST) or null.
 */
fun itemTypeBadge(
    context: Context,
    item: BaseItem?,
): String? {
    if (item == null) return null
    if (item.type == BaseItemKind.EPISODE) {
        val season = item.data.parentIndexNumber
        val ep = item.data.indexNumber
        if (season != null && ep != null) return "S$season · E${formatEpisodeNumber(ep, item.data.indexNumberEnd)}"
    }
    return itemKindLabel(context, item.type)
}
