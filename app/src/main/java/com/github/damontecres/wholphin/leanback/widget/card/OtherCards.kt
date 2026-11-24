package com.github.damontecres.wholphin.leanback.widget.card

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ImageViewCompat
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardContainer
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.CardShape
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.cardFocusLook
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.idColor
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.roundSeconds
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Person, genre, studio, View more and chapter cards (no-Compose UI); see runbooks/no-compose/WIDGETS-CARDS.md.
 */

/**
 * `PersonCard`: a round portrait [widthPx] wide (rows: 108dp), cropped to the circle with the focus ring following it,
 * the user glyph when there is no picture, and the name (one line, scrolls after a second of focus) and role (up to
 * two lines) below in small type.
 */
class PersonCardView(
    context: Context,
) : BaseCardView(context) {
    val image = ItemImageView(context)
    private val caption = CardCaptionView(context)
    private val name = captionText(context, maxLines = 1)
    private val role = captionText(context, maxLines = 2)
    private val startMarquee =
        Runnable {
            // Compose: basicMarquee once the card has been focused for a second
            name.ellipsize = TextUtils.TruncateAt.MARQUEE
            name.marqueeRepeatLimit = 3
            name.isSelected = true
        }

    init {
        frame.content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        caption.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        caption.addView(role, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { focused ->
            caption.setCardFocused(focused)
            removeCallbacks(startMarquee)
            if (focused) {
                postDelayed(startMarquee, MARQUEE_DELAY_MS)
            } else {
                stopMarquee()
            }
        }
    }

    fun bind(
        person: Person,
        style: PageStyle,
        widthPx: Int = context.dp(CardDimens.PERSON_CARD_WIDTH_DP),
        accent: Int = style.sectionAccent,
    ) = bind(person.name, person.role, person.imageUrl, person.favorite, style, widthPx, accent)

    /** [accent] defaults to the page accent (`LocalNeonAccent`) */
    fun bind(
        name: String?,
        role: String?,
        imageUrl: String?,
        favorite: Boolean,
        style: PageStyle,
        widthPx: Int = context.dp(CardDimens.PERSON_CARD_WIDTH_DP),
        accent: Int = style.sectionAccent,
    ) {
        setFrameSize(widthPx, widthPx)
        layoutParams?.let { if (it.width != widthPx) it.width = widthPx }
        frame.setLook(cardFocusLook(context, style, accent, shape = CardShape.CIRCLE, container = CardContainer.TRANSPARENT))
        frame.contentDescription = name
        image.bind(
            ItemImageState(
                imageUrl = imageUrl,
                name = name,
                showOverlay = false,
                favorite = favorite,
                watched = false,
                unwatchedCount = -1,
                watchedPercent = null,
                numberOfVersions = -1,
                accent = accent,
                useFallbackText = true,
                scale = CardImageScale.CROP,
                fallback = ItemImageFallback.PERSON,
            ),
            style,
        )
        // Small enough that most names and roles fit the card's width without being cut off
        this.name.applySpec(CardTextStyles.bodySmall(12f, 16f, 600))
        this.role.applySpec(CardTextStyles.bodySmall(11f, 15f, 400))
        val color = AppTheme.palette.onSurface
        this.name.setTextColor(color)
        this.role.setTextColor(color)
        this.name.text = name.orEmpty()
        this.role.text = role.orEmpty()
        this.role.visibility = if (role != null) View.VISIBLE else View.GONE
        stopMarquee()
        caption.setCardFocused(frame.isFocused, animate = false)
        if (frame.isFocused) postDelayed(startMarquee, MARQUEE_DELAY_MS)
    }

    private fun stopMarquee() {
        name.isSelected = false
        name.ellipsize = TextUtils.TruncateAt.END
    }

    override fun recycle() {
        removeCallbacks(startMarquee)
        image.recycle()
    }

    private companion object {
        const val MARQUEE_DELAY_MS = 1_000L
    }
}

/**
 * `GenreCard`: a 16:9 tile with 8dp corners: the genre's image at 75 % (crossfaded), the genre's own colour at 60 % over
 * it and the fitted name. Give it a height (Home rows: the row height) or a width (grids: the column width).
 */
class GenreCardView(
    context: Context,
) : BaseCardView(context) {
    val image = CardImageView(context)
    private val tint = View(context)
    private val label = FittedLabelView(context)

    init {
        image.alpha = .75f
        image.imageScale = CardImageScale.FILL_BOUNDS
        frame.content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.content.addView(tint, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.content.addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Exactly one of [widthPx] / [heightPx]; the other follows 16:9. [accent] defaults to the page accent. */
    fun bind(
        id: UUID?,
        name: String?,
        imageUrl: String?,
        style: PageStyle,
        widthPx: Int? = null,
        heightPx: Int? = null,
        accent: Int = style.sectionAccent,
    ) {
        val (w, h) = wideSize(widthPx, heightPx)
        setFrameSize(w, h)
        frame.setLook(cardFocusLook(context, style, accent, shape = CardShape.ROUNDED_8, container = CardContainer.TRANSPARENT))
        frame.contentDescription = name
        image.visibility = if (imageUrl != null) View.VISIBLE else View.GONE
        if (imageUrl != image.url) image.load(imageUrl, crossfade = true)
        tint.setBackgroundColor(idColor(id).let { withAlpha(it, .6f) })
        label.bind(name.orEmpty())
    }

    private fun wideSize(
        widthPx: Int?,
        heightPx: Int?,
    ): Pair<Int, Int> =
        when {
            widthPx != null -> widthPx to (widthPx / AspectRatios.WIDE).roundToInt()
            heightPx != null -> (heightPx * AspectRatios.WIDE).roundToInt() to heightPx
            else -> error("GenreCardView needs a width or a height")
        }

    override fun recycle() = image.clear()
}

/**
 * `StudioCard`: a 16:9 tile, content clipped to 8dp corners inside the card shape: the studio's image at 75 %
 * (crossfaded), or when there is none or it fails, the studio's colour at 40 % with the fitted name.
 */
class StudioCardView(
    context: Context,
) : BaseCardView(context) {
    private val inner = FrameLayout(context)
    val image = CardImageView(context)
    private val tint = FrameLayout(context)
    private val label = FittedLabelView(context)
    private var error = false

    init {
        val radius = context.dpF(8f)
        inner.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        inner.clipToOutline = true
        image.alpha = .75f
        image.imageScale = CardImageScale.FILL_BOUNDS
        inner.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        tint.addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
        inner.addView(tint, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.content.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Exactly one of [widthPx] / [heightPx]; the other follows 16:9. [accent] defaults to the page accent. */
    fun bind(
        id: UUID?,
        name: String?,
        imageUrl: String?,
        style: PageStyle,
        widthPx: Int? = null,
        heightPx: Int? = null,
        accent: Int = style.sectionAccent,
    ) {
        val w = widthPx ?: ((heightPx ?: error("StudioCardView needs a width or a height")) * AspectRatios.WIDE).roundToInt()
        val h = heightPx ?: (w / AspectRatios.WIDE).roundToInt()
        setFrameSize(w, h)
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.TRANSPARENT))
        frame.contentDescription = name
        if (imageUrl != image.url) {
            error = false
            image.load(imageUrl, crossfade = true, onError = {
                error = true
                show(imageUrl)
            })
        }
        tint.setBackgroundColor(withAlpha(idColor(id), .4f))
        label.bind(name.orEmpty())
        show(imageUrl)
    }

    private fun show(imageUrl: String?) {
        val showImage = imageUrl != null && !error
        image.visibility = if (showImage) View.VISIBLE else View.GONE
        tint.visibility = if (showImage) View.GONE else View.VISIBLE
    }

    override fun recycle() {
        image.clear()
        error = false
    }
}

/**
 * `ViewMoreCard`: an arrow on the elevated surface colour, sized like the row's cards, with "View more" below when
 * the row shows titles (scrolls after half a second of focus).
 */
class ViewMoreCardView(
    context: Context,
) : BaseCardView(context) {
    private val icon =
        AppCompatImageView(context).apply {
            setImageResource(R.drawable.lb_widget_card_arrow_forward)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.view_more)
        }
    private val caption = CardCaptionView(context)
    private val text = captionText(context, maxLines = 1)
    private val startMarquee =
        Runnable {
            text.ellipsize = TextUtils.TruncateAt.MARQUEE
            text.marqueeRepeatLimit = 3
            text.isSelected = true
        }

    init {
        frame.content.addView(icon, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        text.setText(R.string.view_more)
        caption.addView(text, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { focused ->
            caption.setCardFocused(focused)
            removeCallbacks(startMarquee)
            if (focused) {
                postDelayed(startMarquee, 500L)
            } else {
                text.isSelected = false
                text.ellipsize = null
            }
        }
    }

    /**
     * @param aspectRatio the row's card ratio; the card is [heightPx] tall (default `Cards.height2x3`) and
     * `height × ratio` wide unless [widthPx] is given. [accent] defaults to the page accent (`LocalNeonAccent`).
     */
    fun bind(
        style: PageStyle,
        aspectRatio: Float = AspectRatios.TALL,
        heightPx: Int = context.dpRound(CardDimens.HEIGHT_2X3_DP.toFloat()),
        widthPx: Int? = null,
        showTitle: Boolean = true,
        accent: Int = style.sectionAccent,
    ) {
        val w = widthPx ?: (heightPx * aspectRatio).roundToInt()
        setFrameSize(w, heightPx)
        layoutParams?.let { if (it.width != w) it.width = w }
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.ELEVATED))
        frame.contentDescription = context.getString(R.string.view_more)
        (icon.layoutParams as FrameLayout.LayoutParams).apply {
            width = (w * .66f).roundToInt()
            height = (heightPx * .66f).roundToInt()
        }
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(AppTheme.palette.onSurface))
        caption.visibility = if (showTitle) View.VISIBLE else View.GONE
        text.applySpec(roleSpec(TextRole.BODY_MEDIUM, weight = 600))
        text.setTextColor(AppTheme.palette.onSurface)
        text.ellipsize = null
        caption.setCardFocused(frame.isFocused, animate = false)
    }

    override fun recycle() {
        removeCallbacks(startMarquee)
    }
}

/**
 * `ChapterCard`: a 16:9 chapter image [cardHeightDp] tall (default 120dp) on surfaceVariant, with the chapter name (up
 * to two lines) and start time on a half-black band along the bottom.
 */
class ChapterCardView(
    context: Context,
) : BaseCardView(context) {
    val image = CardImageView(context)
    private val band = LinearLayout(context)
    private val name = AppCompatTextView(context)
    private val time = AppCompatTextView(context)

    init {
        image.imageScale = CardImageScale.FIT
        frame.content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        band.orientation = LinearLayout.VERTICAL
        band.setBackgroundColor(0x80000000.toInt())
        val pad = context.dp(4)
        band.setPadding(pad, pad, pad, pad)
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        band.addView(name, LinearLayout.LayoutParams(WRAP, WRAP))
        band.addView(time, LinearLayout.LayoutParams(WRAP, WRAP))
        frame.content.addView(band, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.START))
    }

    /** [accent] defaults to the page accent (`LocalNeonAccent`; details pages pass the item's type accent) */
    fun bind(
        chapter: Chapter,
        style: PageStyle,
        cardHeightDp: Float = 120f,
        accent: Int = style.sectionAccent,
    ) {
        val h = context.dpRound(cardHeightDp)
        setFrameSize(context.dpRound(AspectRatios.WIDE * cardHeightDp), h)
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.SURFACE_VARIANT))
        val url =
            CardImages.imageUrlService(context).getItemImageUrl(
                itemId = chapter.itemId,
                imageType = ImageType.CHAPTER,
                tag = chapter.tag,
                imageIndex = chapter.index,
                fillHeight = h,
            )
        if (url != image.url) image.load(url)
        // Text inside the card takes its content colour, contentColorFor(surfaceVariant)
        val color = AppTheme.palette.onSurfaceVariant
        name.applySpec(roleSpec(TextRole.BODY_MEDIUM, weight = 600))
        time.applySpec(roleSpec(TextRole.BODY_SMALL, weight = 400))
        name.setTextColor(color)
        time.setTextColor(color)
        name.visibility = if (chapter.name != null) View.VISIBLE else View.GONE
        name.text = chapter.name.orEmpty()
        time.text = context.resources.formatDuration(chapter.position.roundSeconds)
        frame.contentDescription = listOfNotNull(chapter.name, time.text).joinToString(", ")
    }

    override fun recycle() = image.clear()
}

/**
 * `FittedCardLabel`: a genre / studio name centred in the tile with 16dp padding. WeaselTV: white titleLarge, shrunk
 * from 22sp (down to 10sp) until it fits two lines without breaking a word; other themes: bold titleLarge in
 * onSurfaceVariant, any number of lines.
 */
class FittedLabelView(
    context: Context,
) : AppCompatTextView(context) {
    private var fittedKey: String? = null

    init {
        gravity = Gravity.CENTER
        val pad = context.dp(16)
        setPadding(pad, pad, pad, pad)
        ellipsize = TextUtils.TruncateAt.END
    }

    fun bind(label: String) {
        val neon = AppTheme.isNeon
        text = label
        fittedKey = null
        if (neon) {
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2
        } else {
            setTextColor(AppTheme.palette.onSurfaceVariant)
            maxLines = Int.MAX_VALUE
            applySpec(roleSpec(TextRole.TITLE_LARGE, weight = 700))
        }
        requestLayout()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (AppTheme.isNeon) {
            val availW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val availH = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            val key = "$text/$availW/$availH"
            if (key != fittedKey && availW > 0) {
                fittedKey = key
                applySpec(fit(text.toString(), availW, availH))
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** The largest size from titleLarge's down to 10sp whose two lines neither overflow nor split a word */
    private fun fit(
        label: String,
        availW: Int,
        availH: Int,
    ): TextSpec {
        val base = roleSpec(TextRole.TITLE_LARGE, weight = 400)
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = if (base.display) AppFonts.display(context) else AppFonts.body(context, 400)
        paint.letterSpacing = base.trackingEm
        val words = label.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (size in base.sizeSp.toInt() downTo 10) {
            val spec = base.copy(sizeSp = size.toFloat(), lineHeightSp = size * 1.2f)
            paint.textSize = context.spPx(size.toFloat())
            val longestWord = words.maxOfOrNull { paint.measureText(it) } ?: 0f
            if (longestWord > availW) continue
            val layout =
                StaticLayout.Builder
                    .obtain(label, 0, label.length, paint, availW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
            val height = layout.lineCount * context.spPx(spec.lineHeightSp)
            if (layout.lineCount <= 2 && (availH <= 0 || height <= availH)) return spec
        }
        return base.copy(sizeSp = 10f, lineHeightSp = 12f)
    }
}

/**
 * The Requests availability dot (`PendingIndicator`, `PartiallyAvailableIndicator`, `AvailableIndicator`): a 16dp
 * circle 4dp in from the corner. Pending: yellow bell on white; partial: white bar on green; available: white check on
 * green.
 */
class AvailabilityIndicatorView(
    context: Context,
) : View(context) {
    enum class Kind { PENDING, PARTIAL, AVAILABLE }

    private var kind: Kind? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = AppFonts.icons(context)
        }
    private val rect = RectF()

    fun bind(availability: SeerrAvailability?) {
        kind = DiscoverItemCardView.indicatorFor(availability)
        visibility = if (kind == null) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val size = context.dp(24)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val kind = kind ?: return
        val pad = context.dpF(4f)
        val size = context.dpF(16f)
        val cx = pad + size / 2
        val cy = pad + size / 2
        val green = withAlpha(DiscoverItemCardView.DISCOVER_GREEN, .85f)
        fill.color = if (kind == Kind.PENDING) withAlpha(0xFFFFFFFF.toInt(), .85f) else green
        canvas.drawCircle(cx, cy, size / 2, fill)
        edge.strokeWidth = context.dpF(.5f)
        edge.color = if (kind == Kind.PENDING) DiscoverItemCardView.DISCOVER_YELLOW else 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, size / 2 - edge.strokeWidth / 2, edge)
        when (kind) {
            Kind.PARTIAL -> {
                fill.color = 0xFFFFFFFF.toInt()
                val w = context.dpF(10f)
                val h = context.dpF(2f)
                rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                canvas.drawRoundRect(rect, h / 2, h / 2, fill)
            }

            Kind.PENDING, Kind.AVAILABLE -> {
                glyph.textSize = context.spPx(10f)
                glyph.color = if (kind == Kind.PENDING) DiscoverItemCardView.DISCOVER_YELLOW else 0xFFFFFFFF.toInt()
                val text = context.getString(if (kind == Kind.PENDING) R.string.fa_bell else R.string.fa_check)
                val fm = glyph.fontMetrics
                canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, glyph)
            }
        }
    }
}

/** The kind pill of Requests posters on themes other than WeaselTV: 10sp text on a coloured pill, 4dp in */
@SuppressLint("ViewConstructor")
internal class PillLabelView(
    context: Context,
) : AppCompatTextView(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val margin = context.dp(4)

    init {
        maxLines = 1
    }

    fun bind(
        label: String,
        color: Int,
    ) {
        bg.color = color
        val pad = context.dp(4)
        setPadding(margin + pad, 0, margin + pad, 0)
        val spec = roleSpec(TextRole.BODY_SMALL).let { it.copy(sizeSp = 10f, trackingEm = it.trackingEm * it.sizeSp / 10f) }
        applySpec(spec)
        applyLineHeight(context.spPx(roleLineHeightSp(TextRole.BODY_SMALL)).roundToInt())
        setPadding(paddingLeft, paddingTop + margin + pad, paddingRight, paddingBottom + margin + pad)
        text = label
        setTextColor(AppTheme.palette.onSurface)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(margin.toFloat(), margin.toFloat(), (width - margin).toFloat(), (height - margin).toFloat())
        val r = min(rect.width(), rect.height()) / 2
        canvas.drawRoundRect(rect, max(r, 0f), max(r, 0f), bg)
        super.onDraw(canvas)
    }
}
