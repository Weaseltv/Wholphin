package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.CardContainer
import com.github.damontecres.wholphin.leanback.theme.CardDimens
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.cardFocusLook
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.typeAccent
import com.github.damontecres.wholphin.ui.AspectRatios
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.roundToInt

/*
 * Poster cards (no-Compose UI): GridCard, SeasonCard, BannerCard / BannerCardWithTitle and DiscoverItemCard as Views.
 * Sizes, image URLs, badges and focus follow the old composables exactly; see runbooks/no-compose/WIDGETS-CARDS.md.
 */

/**
 * `GridCard`: the card of the library / search / collection grids. [bind]'s width is the grid column width; the image
 * is `width / imageAspectRatio` tall (ratio at least 2:3) on surfaceVariant, with the full overlay (type badge, versions,
 * watched, unwatched count, favourite, progress), and the poster caption below when `showTitle`.
 */
class GridCardView(
    context: Context,
) : BaseCardView(context) {
    val image = ItemImageView(context)
    val caption = PosterCaptionView(context)

    init {
        frame.content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { caption.setCardFocused(it) }
    }

    /**
     * @param widthPx the card width (the grid's column width, `GridItemDetails.widthPx`)
     * @param fillWidth / [fillHeight] image request size; the grids pass the column width
     */
    fun bind(
        item: BaseItem?,
        style: PageStyle,
        widthPx: Int,
        accent: Int = style.accentFor(item),
        imageAspectRatio: Float = AspectRatios.TALL,
        imageScale: CardImageScale = CardImageScale.FIT,
        imageType: ImageType = ImageType.PRIMARY,
        showTitle: Boolean = true,
        fillWidth: Int? = widthPx,
        fillHeight: Int? = null,
    ) {
        val ratio = imageAspectRatio.coerceAtLeast(AspectRatios.MIN)
        setFrameSize(widthPx, (widthPx / ratio).roundToInt())
        layoutParams?.let { if (it.width != widthPx) it.width = widthPx }
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.TRANSPARENT))
        frame.contentDescription = item?.title
        image.setBackgroundColor(AppTheme.palette.surfaceVariant)
        val dto = item?.data
        val url =
            if (item != null && ((fillWidth ?: 0) > 0 || (fillHeight ?: 0) > 0)) {
                CardImages.imageUrlService(context).getItemImageUrl(item, imageType, fillWidth = fillWidth, fillHeight = fillHeight)
            } else {
                null
            }
        image.bind(
            ItemImageState(
                imageUrl = url,
                name = item?.name,
                showOverlay = true,
                favorite = dto?.userData?.isFavorite ?: false,
                watched = dto?.userData?.played ?: false,
                unwatchedCount = item?.countBadge ?: -1,
                watchedPercent = dto?.userData?.playedPercentage,
                numberOfVersions = dto?.mediaSourceCount ?: 0,
                accent = accent,
                progressAccent = typeAccent(item?.type),
                typeBadge = itemTypeBadge(context, item),
                useFallbackText = false,
                scale = imageScale,
            ),
            style,
        )
        caption.visibility = if (showTitle) View.VISIBLE else View.GONE
        caption.bind(item?.title, item?.subtitle)
        caption.setCardFocused(frame.isFocused, animate = false)
    }

    override fun recycle() = image.recycle()

    companion object {
        /**
         * `gridCardHeight`: the height of a grid card at [widthPx] with a [titleLines]-line title and a subtitle, known
         * before items load (for headers sized to the first grid row).
         */
        fun heightPx(
            context: Context,
            widthPx: Int,
            imageAspectRatio: Float,
            showTitle: Boolean,
            titleLines: Int = 2,
        ): Int {
            val imageHeight = (widthPx / imageAspectRatio.coerceAtLeast(AspectRatios.MIN)).roundToInt()
            if (!showTitle) return imageHeight
            return imageHeight + context.dp(CardDimens.CAPTION_GAP_DP) + PosterCaptionView.heightPx(context, titleLines)
        }
    }
}

/**
 * `SeasonCard`: a poster [imageHeightDp] tall and `height × aspectRatio` wide (or [imageWidthDp] wide and
 * `width / aspectRatio` tall) with the poster caption, the image fitted on a transparent card. The overlay is off
 * unless `showImageOverlay` (extras turn it on). Used for seasons, extras, people's credits, search results…
 */
class SeasonCardView(
    context: Context,
) : BaseCardView(context) {
    val image = ItemImageView(context)
    val caption = PosterCaptionView(context)

    init {
        frame.content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { caption.setCardFocused(it) }
    }

    /** `SeasonCard(item = …)`: image URL from the item's primary image at the card size */
    fun bind(
        item: BaseItem?,
        style: PageStyle,
        imageHeightDp: Float?,
        imageWidthDp: Float? = null,
        accent: Int = style.accentFor(item),
        showImageOverlay: Boolean = false,
        aspectRatio: Float = item?.aspectRatio ?: AspectRatios.TALL,
    ) {
        val url =
            item?.let {
                CardImages.imageUrlService(context).getItemImageUrl(
                    it,
                    ImageType.PRIMARY,
                    fillWidth = imageWidthDp?.let { w -> context.dpRound(w) },
                    fillHeight = imageHeightDp?.let { h -> context.dpRound(h) },
                )
            }
        bind(
            title = item?.title,
            subtitle = item?.subtitle,
            name = item?.name,
            imageUrl = url,
            isFavorite = item?.data?.userData?.isFavorite ?: false,
            isPlayed = item?.data?.userData?.played ?: false,
            unplayedItemCount = item?.countBadge ?: 0,
            playedPercentage = item?.data?.userData?.playedPercentage ?: 0.0,
            numberOfVersions = item?.data?.mediaSourceCount ?: 0,
            style = style,
            imageHeightDp = imageHeightDp,
            imageWidthDp = imageWidthDp,
            accent = accent,
            showImageOverlay = showImageOverlay,
            aspectRatio = aspectRatio,
            contentTitle = item?.title,
        )
    }

    /** `SeasonCard(title = …, imageUrl = …)`: [accent] defaults to the page accent (`LocalNeonAccent`) */
    fun bind(
        title: String?,
        subtitle: String?,
        name: String?,
        imageUrl: String?,
        isFavorite: Boolean,
        isPlayed: Boolean,
        unplayedItemCount: Int,
        playedPercentage: Double,
        numberOfVersions: Int,
        style: PageStyle,
        imageHeightDp: Float?,
        imageWidthDp: Float? = null,
        accent: Int = style.sectionAccent,
        showImageOverlay: Boolean = false,
        aspectRatio: Float = AspectRatios.TALL,
        contentTitle: String? = title,
    ) {
        val ratio = aspectRatio.coerceAtLeast(AspectRatios.MIN)
        val (w, h) =
            when {
                imageHeightDp != null -> context.dpRound(imageHeightDp * ratio) to context.dpRound(imageHeightDp)
                imageWidthDp != null -> context.dpRound(imageWidthDp) to context.dpRound(imageWidthDp / ratio)
                else -> context.dpRound(CardDimens.HEIGHT_2X3_DP * ratio) to context.dpRound(CardDimens.HEIGHT_2X3_DP.toFloat())
            }
        setFrameSize(w, h)
        layoutParams?.let { if (it.width != w) it.width = w }
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.TRANSPARENT))
        frame.contentDescription = contentTitle
        image.bind(
            ItemImageState(
                imageUrl = imageUrl,
                name = name,
                showOverlay = showImageOverlay,
                favorite = isFavorite,
                watched = isPlayed,
                unwatchedCount = unplayedItemCount,
                watchedPercent = playedPercentage,
                numberOfVersions = numberOfVersions,
                accent = accent,
                progressAccent = accent,
                typeBadge = null,
                useFallbackText = false,
                scale = CardImageScale.FIT,
            ),
            style,
        )
        caption.bind(title, subtitle)
        caption.setCardFocused(frame.isFocused, animate = false)
    }

    override fun recycle() = image.recycle()
}

/**
 * `BannerCard` (Home rows): an image card `cardHeight × aspectRatio`, surfaceVariant behind the image, the name centred
 * when there is no image, the type badge top-left (WeaselTV), watched check and corner text (episode number or count)
 * top-right, favourite bottom-right and the progress bar. With `withTitle` it is `BannerCardWithTitle`: the poster
 * caption below and no name fallback. Collections shown by a non-primary image fall back to their poster.
 */
class BannerCardView(
    context: Context,
) : BaseCardView(context) {
    val image = CardImageView(context)
    private val fallbackText =
        AppCompatTextView(context).apply {
            gravity = Gravity.CENTER
            val pad = context.dp(16)
            setPadding(pad, pad, pad, pad)
        }
    private val typeBadge = NeonBadgeView(context)
    private val topEnd = LinearLayout(context)
    private val watched = WatchedIconView(context)
    private val cornerText = CountBadgeView(context)
    private val favorite = FavoriteView(context, 16f)
    private val progress = CardProgressView(context)
    val caption = PosterCaptionView(context)

    private var item: BaseItem? = null
    private var imageUrl: String? = null
    private var collectionPosterUrl: String? = null
    private var useSeries = true
    private var useCollectionPoster = false
    private var imageError = false

    init {
        val content = frame.content
        content.clipChildren = false
        content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(fallbackText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        content.addView(typeBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        topEnd.orientation = LinearLayout.HORIZONTAL
        topEnd.gravity = Gravity.CENTER_VERTICAL
        topEnd.addView(watched, LinearLayout.LayoutParams(WRAP, WRAP))
        topEnd.addView(cornerText, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(topEnd, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        content.addView(favorite, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
        content.addView(progress, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM or Gravity.START))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { caption.setCardFocused(it) }
    }

    /**
     * @param name shown centred when there is no image (Home passes `seriesName ?: name`); ignored with [withTitle]
     * @param withTitle `BannerCardWithTitle`: [title] / [subtitle] below the card
     */
    fun bind(
        item: BaseItem?,
        style: PageStyle,
        name: String? = null,
        cardHeightDp: Float = 120f,
        aspectRatio: Float = AspectRatios.WIDE,
        accent: Int = style.accentFor(item),
        cornerText: String? = null,
        played: Boolean = false,
        favorite: Boolean = false,
        playPercent: Double = 0.0,
        imageType: ImageType = ImageType.PRIMARY,
        imageScale: CardImageScale = CardImageScale.FILL_BOUNDS,
        useSeriesForPrimary: Boolean = true,
        cornerTextScale: Float = 1f,
        typeBadge: String? = null,
        withTitle: Boolean = false,
        title: String? = null,
        subtitle: String? = null,
    ) {
        val displayName = if (withTitle) null else name
        val cardHeight = context.dpRound(cardHeightDp)
        // BannerCard sizes the card by the raw ratio; BannerCardWithTitle's column uses the ratio clamped to 2:3
        setFrameSize(context.dpRound(cardHeightDp * aspectRatio), cardHeight)
        val columnWidth =
            if (withTitle) {
                context.dpRound(
                    cardHeightDp * aspectRatio.coerceAtLeast(AspectRatios.MIN),
                )
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        layoutParams?.let { if (it.width != columnWidth) it.width = columnWidth }
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.SURFACE_VARIANT))
        frame.contentDescription =
            (displayName ?: item?.title)?.let {
                if (item?.type == BaseItemKind.EPISODE) listOfNotNull(it, item.subtitle).joinToString(", ") else it
            }

        // Image: the override (streaming services' posters) or the item image at the card height
        val service = CardImages.imageUrlService(context)
        val newUrl = item?.let { it.imageUrlOverride ?: service.getItemImageUrl(it, imageType, null, cardHeight, useSeriesForPrimary) }
        val newCollectionUrl =
            if (item?.type == BaseItemKind.BOX_SET && imageType != ImageType.PRIMARY) {
                service.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = cardHeight, useSeriesForPrimary = false)
            } else {
                null
            }
        // Retry after image-setting changes, even when a collection's URL stays the same
        val reset = item != this.item || newUrl != imageUrl || useSeriesForPrimary != useSeries
        this.item = item
        imageUrl = newUrl
        collectionPosterUrl = newCollectionUrl
        useSeries = useSeriesForPrimary
        image.imageScale = imageScale
        if (reset) {
            useCollectionPoster = false
            imageError = false
            loadDisplayed()
        }
        fallbackText.applySpec(roleSpec(TextRole.TITLE_MEDIUM))
        fallbackText.setTextColor(AppTheme.palette.onSurfaceVariant)
        fallbackText.text = displayName.orEmpty()
        showImageOrFallback()

        // Badges: LocalPosterCountAppearance ?: LocalHomeCardAppearance
        val appearance = style.posterCountAppearance.takeIf { AppTheme.isNeon } ?: style.homeCardAppearance
        val insetX = appearance.badgeHorizontalInsetDp?.let { context.dp(it) } ?: context.dpRound(4f * cornerTextScale)
        val insetY = appearance.badgeVerticalInsetDp?.let { context.dp(it) } ?: context.dpRound(4f * cornerTextScale)
        val padX = appearance.badgeHorizontalPaddingDp?.let { context.dp(it) } ?: context.dpRound(4f * cornerTextScale)
        val padY = appearance.badgeVerticalPaddingDp?.let { context.dp(it) } ?: context.dpRound(4f * cornerTextScale)

        val showType = typeBadge != null && AppTheme.isNeon
        this.typeBadge.visibility = if (showType) View.VISIBLE else View.GONE
        if (showType) this.typeBadge.bind(typeBadge!!, accent, style.posterCountAppearance)
        (this.typeBadge.layoutParams as FrameLayout.LayoutParams).setMargins(insetX, insetY, insetX, insetY)

        val showWatched = played && (playPercent <= 0 || playPercent >= 100)
        watched.visibility = if (showWatched) View.VISIBLE else View.GONE
        this.cornerText.visibility = if (cornerText != null) View.VISIBLE else View.GONE
        if (cornerText != null) {
            val baseSize = roleSpec(TextRole.BODY_SMALL).sizeSp
            this.cornerText.bind(
                cornerText,
                roleSpec(TextRole.BODY_SMALL, sizeSp = appearance.badgeTextSizeSp?.toFloat() ?: (baseSize * cornerTextScale)),
                appearance.badgeTextOpacityPercent / 100f,
                ((appearance.badgeBackgroundOpacityPercent / 100f).coerceIn(0f, 1f) * 255).toInt() shl 24,
                appearance.badgeCornerPercent,
                padX,
                padY,
            )
        }
        topEnd.visibility = if (played || cornerText != null) View.VISIBLE else View.GONE
        topEnd.spaceChildren(context.dp(4))
        (topEnd.layoutParams as FrameLayout.LayoutParams).setMargins(insetX, insetY, insetX, insetY)

        this.favorite.visibility = if (favorite) View.VISIBLE else View.GONE
        (this.favorite.layoutParams as FrameLayout.LayoutParams).apply {
            marginEnd = insetX
            bottomMargin = insetY
        }
        val showProgress = playPercent > 0 && playPercent < 100
        progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) progress.bind(playPercent, typeAccent(item?.type))
        frame.content.requestLayout()

        caption.visibility = if (withTitle) View.VISIBLE else View.GONE
        if (withTitle) {
            caption.bind(title, subtitle)
            caption.setCardFocused(frame.isFocused, animate = false)
        }
    }

    private fun displayedUrl(): String? = if (useCollectionPoster) collectionPosterUrl else imageUrl

    private fun loadDisplayed() {
        val url = displayedUrl()
        if (url == null) {
            image.clear()
            return
        }
        image.load(url, onError = {
            if (displayedUrl() != url) return@load
            val collection = collectionPosterUrl
            if (!useCollectionPoster && collection != null && collection != url) {
                useCollectionPoster = true
                loadDisplayed()
            } else {
                imageError = true
            }
            showImageOrFallback()
        })
    }

    private fun showImageOrFallback() {
        val showImage = !imageError && displayedUrl() != null
        image.visibility = if (showImage) View.VISIBLE else View.INVISIBLE
        fallbackText.visibility = if (showImage) View.GONE else View.VISIBLE
    }

    override fun recycle() {
        image.clear()
        item = null
        imageUrl = null
        collectionPosterUrl = null
    }
}

/**
 * `DiscoverItemCard` (Requests / Seerr): a 2:3 poster [widthPx] wide (default `height2x3 × 2:3`), the availability
 * dot top-right, the kind badge top-left (WeaselTV: Neon badge; others: a coloured pill), and the title and release
 * year below.
 */
class DiscoverItemCardView(
    context: Context,
) : BaseCardView(context) {
    val image = ItemImageView(context)
    private val availability = AvailabilityIndicatorView(context)
    private val neonBadge = NeonBadgeView(context)
    private val kindPill = PillLabelView(context)
    val caption = PosterCaptionView(context)

    init {
        gravity = Gravity.CENTER_HORIZONTAL
        val content = frame.content
        content.clipChildren = false
        content.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(availability, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        content.addView(neonBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        content.addView(kindPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(CardDimens.CAPTION_GAP_DP) })
        frame.addFocusListener { caption.setCardFocused(it) }
    }

    /**
     * @param widthPx card width; null for the default `Cards.height2x3 × 2:3` (grids pass their column width)
     * @param accent default: the type accent of movies and shows on WeaselTV, else the page accent (`LocalNeonAccent`)
     */
    fun bind(
        item: DiscoverItem?,
        style: PageStyle,
        widthPx: Int? = null,
        showOverlay: Boolean = true,
        accent: Int = defaultAccent(item, style),
    ) {
        val w = widthPx ?: context.dpRound(CardDimens.HEIGHT_2X3_DP * AspectRatios.TALL)
        setFrameSize(w, (w / AspectRatios.TALL).roundToInt())
        layoutParams?.let { if (it.width != w) it.width = w }
        frame.setLook(cardFocusLook(context, style, accent, container = CardContainer.TRANSPARENT))
        frame.contentDescription = item?.title
        image.bind(
            ItemImageState(
                imageUrl = item?.posterUrl,
                name = item?.title,
                showOverlay = false,
                favorite = false,
                watched = false,
                unwatchedCount = 0,
                watchedPercent = null,
                numberOfVersions = -1,
                accent = accent,
                useFallbackText = false,
                scale = CardImageScale.FILL_BOUNDS,
            ),
            style,
        )
        availability.bind(item?.availability)
        val neon = AppTheme.isNeon
        val kind = item?.type?.baseItemKind
        val showNeon = showOverlay && neon && !style.hidePosterTypeBadge && kind != null
        neonBadge.visibility = if (showNeon) View.VISIBLE else View.GONE
        if (showNeon) {
            val appearance = style.posterCountAppearance
            val label = itemKindLabel(context, kind) ?: context.resources.getQuantityString(R.plurals.people, 1)
            neonBadge.bind(label, typeAccent(kind), appearance)
            (neonBadge.layoutParams as FrameLayout.LayoutParams).setMargins(
                context.dp(appearance?.badgeHorizontalInsetDp ?: 4),
                context.dp(appearance?.badgeVerticalInsetDp ?: 4),
                0,
                0,
            )
        }
        val pill =
            when (item?.type) {
                SeerrItemType.MOVIE -> R.plurals.movies to DISCOVER_BLUE
                SeerrItemType.TV -> R.plurals.tv_shows to DISCOVER_PURPLE
                SeerrItemType.PERSON -> R.plurals.people to DISCOVER_GREEN
                SeerrItemType.UNKNOWN, null -> null
            }
        val showPill = showOverlay && !neon && pill != null
        kindPill.visibility = if (showPill) View.VISIBLE else View.GONE
        if (showPill) kindPill.bind(context.resources.getQuantityString(pill!!.first, 1), (pill.second and 0x00FFFFFF) or (0xCC shl 24))
        caption.bind(item?.title, item?.releaseDate?.year?.toString())
        caption.setCardFocused(frame.isFocused, animate = false)
    }

    override fun recycle() = image.recycle()

    companion object {
        /** `AppColors.Discover` */
        const val DISCOVER_BLUE = 0xFF2563EB.toInt()
        const val DISCOVER_PURPLE = 0xFF9333EA.toInt()
        const val DISCOVER_GREEN = 0xFF4ADE80.toInt()
        const val DISCOVER_YELLOW = 0xFFEAB308.toInt()
        const val DISCOVER_RED = 0xFFCC2626.toInt()

        fun defaultAccent(
            item: DiscoverItem?,
            style: PageStyle,
        ): Int =
            if (AppTheme.isNeon && (item?.type == SeerrItemType.MOVIE || item?.type == SeerrItemType.TV)) {
                typeAccent(item.type.baseItemKind)
            } else {
                style.sectionAccent
            }

        /** Map [SeerrAvailability] to the indicator shown (BLOCKLISTED / UNKNOWN / DELETED show none) */
        internal fun indicatorFor(availability: SeerrAvailability?): AvailabilityIndicatorView.Kind? =
            when (availability) {
                SeerrAvailability.PENDING, SeerrAvailability.PROCESSING -> AvailabilityIndicatorView.Kind.PENDING
                SeerrAvailability.PARTIALLY_AVAILABLE -> AvailabilityIndicatorView.Kind.PARTIAL
                SeerrAvailability.AVAILABLE -> AvailabilityIndicatorView.Kind.AVAILABLE
                SeerrAvailability.BLOCKLISTED, SeerrAvailability.UNKNOWN, SeerrAvailability.DELETED, null -> null
            }
    }
}

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
