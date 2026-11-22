package com.github.damontecres.wholphin.leanback.library

import android.content.Context
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import coil3.load
import coil3.svg.SvgDecoder
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.streamingProviderAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.logoUrlFor
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.components.itemKindLabel
import com.github.damontecres.wholphin.ui.components.providerWordmark
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.EnumSet

/**
 * A vertical [LinearLayout] whose children can take a fraction of its width (Compose `fillMaxWidth(fraction)`); other
 * children keep their own layout params.
 */
internal open class FractionColumn(
    context: Context,
) : LinearLayout(context) {
    private val fractions = HashMap<View, Float>()

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    fun addFraction(
        view: View,
        fraction: Float,
        params: LayoutParams = LayoutParams(0, WRAP),
    ) {
        fractions[view] = fraction
        addView(view, params)
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        fractions.forEach { (view, fraction) ->
            val lp = view.layoutParams ?: return@forEach
            val w = (width * fraction).toInt().coerceAtLeast(0)
            if (lp.width != w) lp.width = w
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/**
 * The header above Home-style rows and library grids with details (`HomePageHeader`), for the focused [BaseItem]:
 * the eyebrow ("MOVIE · Drama · Thriller" in the item's accent, WeaselTV), the title or logo, the episode name, the
 * quick details and the overview (three lines, two for episodes; an empty 48dp + 12dp gap without one). [compactDetails]
 * is the first Recommended row's compact form (20sp title or a 28dp logo, one overview line); [compactLogo] limits the
 * logo to 40dp. A streaming service collection shows [StreamingProviderHeaderView] instead (WeaselTV).
 */
class LibraryItemHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val normal = FractionColumn(context)
    private val compact = FractionColumn(context)
    private val provider = StreamingProviderHeaderView(context)

    // Normal form
    private val eyebrow = TextView(context)
    private val titleOrLogo = TitleOrLogoView(context)
    private val titleBox = MaxHeightScaleFrame(context).apply { addView(titleOrLogo, LayoutParams(MATCH, WRAP)) }
    private val details = FractionColumn(context)
    private val episodeName = episodeNameView(context)
    private val quickDetails = QuickDetailsView(context)
    private val overview = TextView(context)
    private val overviewSpace = Space(context)

    // Compact form
    private val compactEyebrow = TextView(context)
    private val compactRow = LinearLayout(context)
    private val compactLogoView = TitleOrLogoView(context)
    private val compactTitle = TextView(context)
    private val compactEpisode = episodeNameView(context)
    private val compactQuick = QuickDetailsView(context)
    private val compactOverview = TextView(context)

    init {
        clipChildren = false
        clipToPadding = false
        // Normal: Column(spacing 4) { eyebrow; TitleOrLogo (75 %); Column(spacing 8, 92 % / 60 %) { … } }
        normal.addView(eyebrow, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = context.dp(4) })
        normal.addFraction(titleBox, .75f, LinearLayout.LayoutParams(0, WRAP).apply { topMargin = context.dp(4) })
        details.addView(episodeName, LinearLayout.LayoutParams(MATCH, WRAP))
        details.addView(quickDetails, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        details.addView(overview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        details.addView(overviewSpace, LinearLayout.LayoutParams(MATCH, 0).apply { topMargin = context.dp(8) })
        normal.addFraction(
            details,
            if (AppTheme.isNeon) .92f else .6f,
            LinearLayout.LayoutParams(0, WRAP).apply { topMargin = context.dp(4) },
        )
        addView(normal, LayoutParams(MATCH, WRAP))

        // Compact: Column(spacing 2) { eyebrow; Row(spacing 12, 92 %) { logo / title; episode }; QuickDetails; overview (92 %) }
        compact.addView(compactEyebrow, LinearLayout.LayoutParams(WRAP, WRAP))
        compactRow.orientation = LinearLayout.HORIZONTAL
        compactRow.gravity = Gravity.CENTER_VERTICAL
        compactLogoView.small = true
        compactRow.addView(compactLogoView, LinearLayout.LayoutParams(context.dp(160), context.dp(28)).apply { marginEnd = context.dp(12) })
        compactRow.addView(compactTitle, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = context.dp(12) })
        compactRow.addView(compactEpisode, LinearLayout.LayoutParams(0, WRAP, 1f))
        compact.addFraction(compactRow, .92f, LinearLayout.LayoutParams(0, WRAP).apply { topMargin = context.dp(2) })
        compact.addView(compactQuick, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(2) })
        compact.addFraction(compactOverview, .92f, LinearLayout.LayoutParams(0, WRAP).apply { topMargin = context.dp(2) })
        addView(compact, LayoutParams(MATCH, WRAP))

        addView(provider, LayoutParams(MATCH, WRAP))
        provider.visibility = GONE
        compact.visibility = GONE
    }

    /**
     * Show [item] (null: an empty header of the same size). [style] gives the accent (`neonAccentFor(item)`);
     * [toggles] the quick details pieces enabled in the settings.
     */
    fun bind(
        item: BaseItem?,
        style: PageStyle,
        showLogo: Boolean,
        compactLogo: Boolean = false,
        compactDetails: Boolean = false,
        toggles: Set<DisplayToggle> = EnumSet.allOf(DisplayToggle::class.java),
    ) {
        val neon = AppTheme.isNeon
        if (neon && item != null && StreamingCollections.isStreamingCollection(item.data)) {
            normal.visibility = GONE
            compact.visibility = GONE
            provider.visibility = VISIBLE
            provider.bind(item, style, toggles)
            return
        }
        provider.visibility = GONE
        val dto = item?.data
        val isEpisode = item?.type == BaseItemKind.EPISODE
        val eyebrowText =
            item?.let {
                listOfNotNull(
                    itemKindLabel(context, it.type),
                    dto
                        ?.genres
                        ?.take(2)
                        ?.joinToString(" · ")
                        ?.takeIf { g -> g.isNotBlank() },
                ).joinToString(" · ")
            }
        val accent = style.accentFor(item)
        val subtitle = if (isEpisode) dto?.name else null
        val logoUrl = logoUrlFor(item, CardImages.imageUrlService(context))
        val palette = AppTheme.palette
        if (compactDetails) {
            normal.visibility = GONE
            compact.visibility = VISIBLE
            compactEyebrow.visibility = if (eyebrowText != null) VISIBLE else GONE
            eyebrowText?.let { compactEyebrow.applyNeonEyebrow(it, accent) }
            // spacedBy(2): no gap above the title row without the eyebrow
            (compactRow.layoutParams as LinearLayout.LayoutParams).topMargin = if (eyebrowText != null) context.dp(2) else 0
            if (showLogo && logoUrl != null) {
                compactLogoView.visibility = VISIBLE
                compactTitle.visibility = GONE
                compactLogoView.bind(item?.title, logoUrl, showLogo = true)
            } else {
                compactLogoView.visibility = GONE
                compactTitle.visibility = VISIBLE
                compactTitle.applyTextStyle(NeonType.hero(20f))
                compactTitle.setTextColor(palette.onSurface)
                compactTitle.maxLines = 1
                compactTitle.ellipsize = android.text.TextUtils.TruncateAt.END
                compactTitle.text = item?.title.orEmpty().uppercase()
            }
            compactEpisode.visibility = if (subtitle != null) VISIBLE else GONE
            compactEpisode.text = subtitle.orEmpty()
            // spacedBy(12) only between shown pieces
            (compactTitle.layoutParams as LinearLayout.LayoutParams).marginEnd = if (subtitle != null) context.dp(12) else 0
            (compactLogoView.layoutParams as LinearLayout.LayoutParams).marginEnd = if (subtitle != null) context.dp(12) else 0
            compactQuick.bind(item?.ui?.quickDetailsText, item?.timeRemainingOrRuntime, dto?.endDate, toggles)
            val overviewText = dto?.overview
            compactOverview.visibility = if (overviewText.isNotNullOrBlank()) VISIBLE else GONE
            compactOverview.applyTextStyle(NeonType.body())
            compactOverview.setTextColor(0xFFFFFFFF.toInt())
            compactOverview.maxLines = 1
            compactOverview.ellipsize = android.text.TextUtils.TruncateAt.END
            compactOverview.text = overviewText.orEmpty()
            return
        }
        compact.visibility = GONE
        normal.visibility = VISIBLE
        eyebrow.visibility = if (neon && eyebrowText != null) VISIBLE else GONE
        eyebrowText?.let { eyebrow.applyNeonEyebrow(it, accent) }
        // spacedBy(4): no gap above the title without the eyebrow
        (titleBox.layoutParams as LinearLayout.LayoutParams).topMargin = if (eyebrow.visibility == VISIBLE) context.dp(4) else 0
        titleOrLogo.bind(item?.title, logoUrl, showLogo)
        titleBox.maxHeightPx = if (compactLogo && showLogo && logoUrl != null) context.dp(40) else null
        episodeName.visibility = if (subtitle != null) VISIBLE else GONE
        episodeName.text = subtitle.orEmpty()
        (quickDetails.layoutParams as LinearLayout.LayoutParams).topMargin = if (subtitle != null) context.dp(8) else 0
        quickDetails.bind(item?.ui?.quickDetailsText, item?.timeRemainingOrRuntime, dto?.endDate, toggles)
        val overviewText = dto?.overview
        if (overviewText.isNotNullOrBlank()) {
            overview.visibility = VISIBLE
            overviewSpace.visibility = GONE
            if (neon) overview.applyTextStyle(NeonType.body()) else overview.applyTextRole(TextRole.BODY_MEDIUM)
            overview.setTextColor(if (neon) 0xFFFFFFFF.toInt() else palette.onSurface)
            overview.maxLines = if (isEpisode) 2 else 3
            overview.ellipsize = android.text.TextUtils.TruncateAt.END
            overview.text = overviewText
            overview.layoutParams =
                (overview.layoutParams as LinearLayout.LayoutParams).apply { width = if (neon) MATCH else context.dp(400) }
        } else {
            overview.visibility = GONE
            overviewSpace.visibility = VISIBLE
            overviewSpace.layoutParams =
                (overviewSpace.layoutParams as LinearLayout.LayoutParams).apply {
                    height = context.dp(48f + if (!isEpisode) 12f else 0f)
                    width = if (neon) MATCH else context.dp(400)
                }
        }
        requestLayout()
    }

    private companion object {
        /** `EpisodeName`: headlineSmall at 20sp, onSurface, one line */
        fun episodeNameView(context: Context): TextView =
            TextView(context).apply {
                applyTextRole(TextRole.HEADLINE_SMALL, sizeSp = 20f)
                setTextColor(AppTheme.palette.onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
    }
}

/**
 * `StreamingProviderHeader`: a streaming service collection's header with its bundled wordmark at the end (56 % wide,
 * 120dp tall, 64dp from the end) and, in the first 42 %, the COLLECTION eyebrow, the title, the quick details and
 * "Everything on <service> in one place" (two lines).
 */
class StreamingProviderHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val wordmark = StreamingProviderWordmarkView(context)
    private val column = FractionColumn(context)
    private val eyebrow = TextView(context)
    private val title = TitleOrLogoView(context)
    private val quickDetails = QuickDetailsView(context)
    private val description = TextView(context)

    init {
        clipChildren = false
        val wordmarkBox = FractionColumn(context).apply { gravity = Gravity.END }
        wordmarkBox.addFraction(wordmark, .56f, LinearLayout.LayoutParams(0, context.dp(120)).apply { marginEnd = context.dp(64) })
        addView(wordmarkBox, LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        val inner =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
        inner.addView(eyebrow, LinearLayout.LayoutParams(WRAP, WRAP))
        inner.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        inner.addView(quickDetails, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        inner.addView(description, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(8) })
        column.addFraction(inner, .42f)
        addView(column, LayoutParams(MATCH, WRAP))
    }

    fun bind(
        item: BaseItem,
        style: PageStyle,
        toggles: Set<DisplayToggle>,
    ) {
        eyebrow.applyNeonEyebrow(context.getString(R.string.collection), NeonSections.Collections.glow)
        title.bind(item.title, null, showLogo = false)
        quickDetails.bind(item.ui.quickDetailsText, item.timeRemainingOrRuntime, item.data.endDate, toggles)
        description.applyTextStyle(NeonType.body())
        description.setTextColor(0xFFFFFFFF.toInt())
        description.maxLines = 2
        description.ellipsize = android.text.TextUtils.TruncateAt.END
        description.text = context.getString(R.string.streaming_provider_description, item.name.orEmpty())
        wordmark.bind(item.name, style.sectionAccent)
    }
}

/**
 * `StreamingProviderWordmark`: a streaming service's bundled wordmark (assets/provider-wordmarks), fitted, tinted like
 * its Home row and collection cards (Peacock keeps its colours; Netflix, Hulu, Prime Video, AMC+, MGM+, BritBox,
 * Crunchyroll and Hallmark take the provider colour; the rest white). Nothing for services without one.
 */
class StreamingProviderWordmarkView(
    context: Context,
) : ImageView(context) {
    init {
        scaleType = ScaleType.FIT_CENTER
    }

    /** @param fallbackAccent the accent when the provider has no colour (`LocalNeonAccent`) */
    fun bind(
        name: String?,
        fallbackAccent: Int,
    ) {
        val file = providerWordmark(name)
        if (file == null) {
            visibility = GONE
            setImageDrawable(null)
            return
        }
        visibility = VISIBLE
        val providerColor = streamingProviderAccent(name) ?: fallbackAccent
        when (file) {
            "peacock.svg" -> {
                clearColorFilter()
            }

            "netflix.svg", "hulu.svg", "prime_video.svg", "amc.png", "mgm.svg",
            "britbox.svg", "crunchyroll.svg", "hallmark.svg",
            -> {
                setColorFilter(providerColor, PorterDuff.Mode.SRC_IN)
            }

            else -> {
                setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            }
        }
        load("file:///android_asset/provider-wordmarks/$file") {
            if (file.endsWith(".svg")) decoderFactory(SvgDecoder.Factory())
        }
    }
}

/**
 * Lays out its child at its natural size and, when taller than [maxHeightPx], scales it down from the top-left corner
 * (the old `heightIn(max = 40.dp)` on a fitted logo, which shrinks it keeping its shape).
 */
internal class MaxHeightScaleFrame(
    context: Context,
) : FrameLayout(context) {
    var maxHeightPx: Int? = null
        set(value) {
            field = value
            requestLayout()
        }
    private var scale = 1f

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val child = if (childCount > 0) getChildAt(0) else null
        val max = maxHeightPx
        val natural = child?.measuredHeight ?: 0
        scale = if (max != null && natural > max) max.toFloat() / natural else 1f
        setMeasuredDimension(measuredWidth, (natural * scale).toInt() + paddingTop + paddingBottom)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val child = if (childCount > 0) getChildAt(0) else return
        child.layout(paddingLeft, paddingTop, paddingLeft + child.measuredWidth, paddingTop + child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
    }
}
