package com.github.damontecres.wholphin.leanback.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import coil3.load
import coil3.svg.SvgDecoder
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.streamingProviderAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardImages
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.QuickDetailsView
import com.github.damontecres.wholphin.leanback.widget.text.TitleOrLogoView
import com.github.damontecres.wholphin.leanback.widget.text.logoUrlFor
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.services.StreamingCollections
import com.github.damontecres.wholphin.ui.components.itemKindLabel
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.EnumSet
import java.util.Locale
import kotlin.math.min

/**
 * What [HomeRowsView] shows above its rows for the focused item (the old `headerComposable`). [view] is laid out
 * at its natural height and scaled down to fit when the rows need the room.
 */
interface HomeRowsHeader {
    val view: View

    fun bind(item: BaseItem?)
}

/**
 * `HomePageHeader`: the focused item's eyebrow (WeaselTV: KIND · GENRES in the item's accent), title or logo, episode
 * name, quick details (with time remaining / ends at) and overview, inside the Home header box
 * ([HeaderDimens.home]: WeaselTV 27dp overscan top, 12dp below, natural height; other themes 48 / 32dp and 180dp
 * tall). On WeaselTV a streaming-service collection shows the provider header instead (`StreamingProviderHeader`).
 *
 * [compactDetails] is the compact variant (the first Recommended row / grid details: one-line identity and overview),
 * [compactLogo] limits a logo to 40dp tall. [box] overrides the padding box (null: the Home box).
 */
@SuppressLint("ViewConstructor")
class HomeHeaderView(
    context: Context,
    var showLogo: Boolean,
    private val compactLogo: Boolean = false,
    private val compactDetails: Boolean = false,
    box: HeaderDimens.HeaderBox? = HeaderDimens.home(),
) : FrameLayout(context),
    HomeRowsHeader {
    override val view: View get() = this

    /** The page's style: the eyebrow's accent comes from it (`neonAccentFor(item)`) */
    var pageStyle: PageStyle = PageStyle()

    /** The display toggles of the quick details (`LocalInterfaceCustomization`) */
    var displayToggles: Set<DisplayToggle> = EnumSet.allOf(DisplayToggle::class.java)

    private val neon = AppTheme.isNeon
    private val imageUrlService = CardImages.imageUrlService(context)

    private val standard = LinearLayout(context)
    private val eyebrow = AppCompatTextView(context)
    private val titleOrLogo = TitleOrLogoView(context)
    private val titleBox = MaxHeightFrame(context)
    private val compactTitle = AppCompatTextView(context)
    private val compactTitleRow = LinearLayout(context)
    private val details = FractionLinearLayout(context, if (neon) .92f else .6f)
    private val subtitle = AppCompatTextView(context)
    private val quickDetails = QuickDetailsView(context)
    private val overview = AppCompatTextView(context)
    private val overviewSpacer = View(context)
    private val streaming = StreamingProviderHeaderView(context)

    init {
        clipChildren = false
        box?.let { HeaderDimens.apply(this, it) }
        if (box?.height != null) {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(box.height) + context.dp(box.top) + context.dp(box.bottom))
        }
        standard.orientation = LinearLayout.VERTICAL
        standard.clipChildren = false
        addView(standard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(streaming, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        streaming.visibility = GONE

        eyebrow.maxLines = 1
        eyebrow.ellipsize = TextUtils.TruncateAt.END
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        overview.ellipsize = TextUtils.TruncateAt.END
        compactTitle.maxLines = 1
        compactTitle.ellipsize = TextUtils.TruncateAt.END

        if (compactDetails) {
            buildCompact()
        } else {
            buildStandard()
        }
    }

    /** `Column(spacedBy(4))`: eyebrow, title or logo (75 % wide), then the details column (spaced 8) */
    private fun buildStandard() {
        val gap = context.dp(4)
        // NeonEyebrow with 4dp below it, plus the column's 4dp spacing
        standard.addView(eyebrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap * 2 })
        titleBox.maxHeightPx = if (compactLogo) context.dp(40) else Int.MAX_VALUE
        titleBox.addView(titleOrLogo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        standard.addView(FractionFrame(context, .75f).apply { addView(titleBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) })
        details.orientation = LinearLayout.VERTICAL
        details.clipChildren = false
        standard.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        details.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        details.addView(quickDetails, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Overview: WeaselTV fills the details column, other themes are 400dp wide
        val overviewWidth = if (neon) LinearLayout.LayoutParams.MATCH_PARENT else context.dp(400)
        details.addView(overview, LinearLayout.LayoutParams(overviewWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        details.addView(overviewSpacer, LinearLayout.LayoutParams(overviewWidth, 0))
    }

    /** The compact header (`compactDetails`): eyebrow, one row of title (or a 160 × 28dp logo) and episode name, details, one line of overview */
    private fun buildCompact() {
        val gap = context.dp(2)
        standard.addView(eyebrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        compactTitleRow.orientation = LinearLayout.HORIZONTAL
        compactTitleRow.gravity = Gravity.CENTER_VERTICAL
        titleOrLogo.small = true
        compactTitleRow.addView(titleOrLogo, LinearLayout.LayoutParams(context.dp(160), context.dp(28)))
        compactTitleRow.addView(compactTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        compactTitleRow.addView(subtitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
        standard.addView(FractionFrame(context, .92f).apply { addView(compactTitleRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        standard.addView(quickDetails, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        standard.addView(FractionFrame(context, .92f).apply { addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
    }

    override fun bind(item: BaseItem?) {
        if (neon && item != null && StreamingCollections.isStreamingCollection(item.data)) {
            standard.visibility = GONE
            streaming.visibility = VISIBLE
            streaming.bind(item, displayToggles, pageStyle.sectionAccent)
            return
        }
        streaming.visibility = GONE
        standard.visibility = VISIBLE
        val palette = AppTheme.palette
        val isEpisode = item?.type == BaseItemKind.EPISODE
        val dto = item?.data
        // Neon Board: eyebrow "KIND · GENRES" in the item's type accent above the title
        val eyebrowText =
            item?.let {
                listOfNotNull(
                    itemKindLabel(context, it.type),
                    dto?.genres?.take(2)?.joinToString(" · ")?.takeIf { g -> g.isNotBlank() },
                ).joinToString(" · ")
            }
        val accent = pageStyle.accentFor(item)
        val showEyebrow = eyebrowText != null && (neon || compactDetails)
        eyebrow.visibility = if (showEyebrow) VISIBLE else GONE
        if (showEyebrow) eyebrow.applyNeonEyebrow(eyebrowText!!, accent)

        val subtitleText = if (isEpisode) dto?.name else null
        subtitle.visibility = if (subtitleText != null) VISIBLE else GONE
        // EpisodeName: headlineSmall at 20sp, one line
        subtitle.applyTextStyle(ControlTextStyle.of(TextRole.HEADLINE_SMALL, sizeSp = 20f))
        subtitle.setTextColor(palette.onSurface)
        subtitle.text = subtitleText.orEmpty()

        quickDetails.setTextColor(palette.onSurface)
        quickDetails.bind(item?.ui?.quickDetailsText, item?.timeRemainingOrRuntime, item?.data?.endDate, displayToggles)

        val overviewText = dto?.overview?.takeIf { it.isNotBlank() }
        val logoUrl = logoUrlFor(item, imageUrlService)
        if (compactDetails) {
            val logo = showLogo && logoUrl != null
            titleOrLogo.visibility = if (logo) VISIBLE else GONE
            compactTitle.visibility = if (logo) GONE else VISIBLE
            if (logo) titleOrLogo.bind(item?.title, logoUrl, true)
            compactTitle.applyTextStyle(NeonType.hero(20f))
            compactTitle.setTextColor(palette.onSurface)
            compactTitle.text = item?.title.orEmpty().uppercase()
            (quickDetails.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(2)
            overview.visibility = if (overviewText != null) VISIBLE else GONE
            overview.maxLines = 1
            overview.applyTextStyle(NeonType.body())
            overview.setTextColor(0xFFFFFFFF.toInt())
            overview.text = overviewText.orEmpty()
            return
        }

        titleOrLogo.bind(item?.title, logoUrl, showLogo)
        val spacing = context.dp(8)
        (quickDetails.layoutParams as LinearLayout.LayoutParams).topMargin = if (subtitleText != null) spacing else 0
        val twoLines = isEpisode
        if (overviewText != null) {
            overview.visibility = VISIBLE
            overviewSpacer.visibility = GONE
            overview.maxLines = if (twoLines) 2 else 3
            if (neon) overview.applyTextStyle(NeonType.body()) else overview.applyTextStyle(ControlTextStyle.of(TextRole.BODY_MEDIUM))
            overview.setTextColor(if (neon) 0xFFFFFFFF.toInt() else palette.onSurface)
            overview.text = overviewText
            (overview.layoutParams as LinearLayout.LayoutParams).topMargin = spacing
        } else {
            overview.visibility = GONE
            overviewSpacer.visibility = VISIBLE
            (overviewSpacer.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = spacing
                height = context.dp(48 + if (!twoLines) 12 else 0)
            }
        }
        details.requestLayout()
    }
}

/**
 * `StreamingProviderHeader` (WeaselTV): the provider's bundled wordmark on the right (56 % wide, 120dp tall, 64dp from
 * the end, tinted like its row), and on the left (42 % wide) the COLLECTION eyebrow, the title, quick details and the
 * provider description. A private copy of LIBRARY's header (runbooks/no-compose/CONTRACT.md section 1).
 */
private class StreamingProviderHeaderView(
    context: Context,
) : FrameLayout(context) {
    private val wordmark =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    private val column = FractionLinearLayout(context, .42f)
    private val eyebrow = AppCompatTextView(context)
    private val title = TitleOrLogoView(context)
    private val quickDetails = QuickDetailsView(context)
    private val description = AppCompatTextView(context)
    private var shownWordmark: String? = null

    init {
        clipChildren = false
        val wordmarkBox = FractionFrame(context, .56f)
        wordmarkBox.setPadding(0, 0, context.dp(64), 0)
        wordmarkBox.addView(wordmark, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(120)))
        addView(wordmarkBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
        column.orientation = LinearLayout.VERTICAL
        val gap = context.dp(8)
        column.addView(eyebrow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        column.addView(quickDetails, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        description.maxLines = 2
        description.ellipsize = TextUtils.TruncateAt.END
        column.addView(description, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        item: BaseItem,
        toggles: Set<DisplayToggle>,
        sectionAccent: Int,
    ) {
        eyebrow.applyNeonEyebrow(context.getString(R.string.collection), NeonSections.Collections.glow)
        title.bind(item.title, null, false)
        quickDetails.setTextColor(AppTheme.palette.onSurface)
        quickDetails.bind(item.ui.quickDetailsText, item.timeRemainingOrRuntime, item.data.endDate, toggles)
        description.applyTextStyle(NeonType.body())
        description.setTextColor(0xFFFFFFFF.toInt())
        description.text = context.getString(R.string.streaming_provider_description, item.name.orEmpty())
        val file = providerWordmark(item.name)
        wordmark.visibility = if (file != null) VISIBLE else GONE
        if (file != null && file != shownWordmark) {
            shownWordmark = file
            val providerColor = streamingProviderAccent(item.name) ?: sectionAccent
            when (file) {
                "peacock.svg" -> {
                    wordmark.clearColorFilter()
                }

                "netflix.svg", "hulu.svg", "prime_video.svg", "amc.png", "mgm.svg", "britbox.svg", "crunchyroll.svg", "hallmark.svg" -> {
                    wordmark.setColorFilter(providerColor, PorterDuff.Mode.SRC_IN)
                }

                else -> {
                    wordmark.setColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
                }
            }
            wordmark.load("file:///android_asset/provider-wordmarks/$file") {
                if (file.endsWith(".svg")) decoderFactory(SvgDecoder.Factory())
            }
        }
    }
}

/** The bundled wordmark file of a streaming provider (`providerWordmark`) */
private fun providerWordmark(name: String?): String? =
    when (name?.trim()?.lowercase(Locale.ROOT)) {
        "netflix" -> "netflix.svg"
        "disney+", "disney plus" -> "disney.svg"
        "hulu" -> "hulu.svg"
        "max", "hbo", "hbo max", "max (hbo)" -> "max.svg"
        "prime video", "amazon prime video" -> "prime_video.svg"
        "paramount+", "paramount plus" -> "paramount.svg"
        "peacock" -> "peacock.svg"
        "apple tv", "apple tv+", "apple tv plus" -> "apple_tv.svg"
        "amc+", "amc plus" -> "amc.png"
        "mgm+", "mgm plus" -> "mgm.svg"
        "starz" -> "starz.svg"
        "britbox", "brit box" -> "britbox.svg"
        "crunchyroll" -> "crunchyroll.svg"
        "hallmark", "hallmark+", "hallmark plus" -> "hallmark.svg"
        "angel", "angel studios" -> "angel.svg"
        else -> null
    }

/** A FrameLayout exactly [fraction] of the width it is offered (`fillMaxWidth(fraction)`) */
internal class FractionFrame(
    context: Context,
    private val fraction: Float,
) : FrameLayout(context) {
    init {
        clipChildren = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(fractionSpec(widthMeasureSpec, fraction), heightMeasureSpec)
    }
}

/** A LinearLayout exactly [fraction] of the width it is offered (`fillMaxWidth(fraction)`) */
internal class FractionLinearLayout(
    context: Context,
    private val fraction: Float,
) : LinearLayout(context) {
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(fractionSpec(widthMeasureSpec, fraction), heightMeasureSpec)
    }
}

private fun fractionSpec(
    spec: Int,
    fraction: Float,
): Int {
    if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.UNSPECIFIED) return spec
    return View.MeasureSpec.makeMeasureSpec((View.MeasureSpec.getSize(spec) * fraction).toInt(), View.MeasureSpec.EXACTLY)
}

/** A FrameLayout at most [maxHeightPx] tall (`heightIn(max = …)`) */
internal class MaxHeightFrame(
    context: Context,
) : FrameLayout(context) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (maxHeightPx == Int.MAX_VALUE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limited =
            when (View.MeasureSpec.getMode(heightMeasureSpec)) {
                View.MeasureSpec.UNSPECIFIED -> View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
                else -> View.MeasureSpec.makeMeasureSpec(min(size, maxHeightPx), View.MeasureSpec.getMode(heightMeasureSpec))
            }
        super.onMeasure(widthMeasureSpec, limited)
    }
}

/**
 * `FitHeaderToHeight`: lays its child out at its natural height (unbounded) and scales it down from the top-left
 * corner when that is more than [maxHeightPx]. With [fillHeight] it is exactly [maxHeightPx] tall, so shorter copy
 * never lifts what is below it.
 */
internal class HeaderFitLayout(
    context: Context,
) : FrameLayout(context) {
    /** Set during the parent's measure pass (no layout request): the parent measures this view right after */
    var maxHeightPx: Int = Int.MAX_VALUE

    var fillHeight: Boolean = false

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val child = getChildAt(0)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val available =
            if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = min(maxHeightPx, available).coerceAtLeast(1)
        if (child == null || child.visibility == GONE) {
            setMeasuredDimension(width, if (fillHeight && limit != Int.MAX_VALUE) limit else 0)
            return
        }
        val lp = child.layoutParams
        val childWidthSpec = ViewGroup.getChildMeasureSpec(widthMeasureSpec, 0, lp.width)
        val childHeightSpec =
            if (lp.height > 0) {
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
            } else {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            }
        child.measure(childWidthSpec, childHeightSpec)
        val natural = child.measuredHeight
        val scale = if (natural > limit) limit.toFloat() / natural else 1f
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        val height = if (fillHeight && limit != Int.MAX_VALUE) limit else kotlin.math.ceil(natural * scale).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}
