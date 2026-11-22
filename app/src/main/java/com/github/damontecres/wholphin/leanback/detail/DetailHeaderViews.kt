package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.text.HeaderDimens
import com.github.damontecres.wholphin.leanback.widget.text.OverviewTextView
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.playback.audioStreamCount
import com.github.damontecres.wholphin.ui.playback.embeddedSubtitleCount
import com.github.damontecres.wholphin.ui.playback.externalSubtitlesCount
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.ui.util.StreamFormatting.concatWithSpace
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatAudioCodec
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatSubtitleCodec
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatVideoRange
import com.github.damontecres.wholphin.ui.util.StreamFormatting.resolutionString
import com.github.damontecres.wholphin.util.languageName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

/*
 * Text pieces of the details headers as Views: VideoStreamDetails.kt, SeriesComponents.kt and the kid metadata line
 * and episode details of KidTitleButtons.kt.
 */

/**
 * `VideoStreamDetails`: chips 4dp apart for the video (resolution and range, "+N" more versions), its codec, the audio
 * (language, codec, channels; at most 200dp) and the subtitles (at most 160dp; "None" when there are some but none is
 * chosen), each "+N" for the other streams. [fontSizeSp] is the chips' text size (12sp, scaled on the details pages).
 */
class StreamDetailsView(
    context: Context,
    private val fontSizeSp: Float = 12f,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = SpaceDrawable(context.dp(4))
    }

    fun bind(
        chosenStreams: ChosenStreams?,
        numberOfVersions: Int,
    ) = bind(chosenStreams?.source, chosenStreams?.videoStream, chosenStreams?.audioStream, chosenStreams?.subtitleStream, numberOfVersions)

    fun bind(
        source: MediaSourceInfo?,
        videoStream: MediaStream?,
        audioStream: MediaStream?,
        subtitleStream: MediaStream?,
        numberOfVersions: Int = 0,
    ) {
        removeAllViews()
        val resources = context.resources
        val video =
            videoStream?.let {
                val width = it.width
                val height = it.height
                val resName = if (width != null && height != null) resolutionString(width, height, it.isInterlaced) else null
                val range = formatVideoRange(resources, it.videoRange, it.videoRangeType, it.videoDoViTitle)
                resName.concatWithSpace(range)
            }
        video?.let { addLabel(it, count = numberOfVersions) }
        videoStream?.codec?.uppercase()?.let { addLabel(it) }

        val audioCount = source?.audioStreamCount ?: 0
        val audio =
            if (audioCount == 0 || audioStream == null) {
                resources.getString(R.string.none)
            } else {
                listOfNotNull(
                    languageName(audioStream.language),
                    formatAudioCodec(resources, audioStream.codec, audioStream.profile),
                    audioStream.channelLayout,
                ).joinToString(" ")
            }
        addLabel(audio, count = audioCount, icon = R.string.fa_volume_high, maxWidthDp = 200f)

        val subtitleCount = (source?.embeddedSubtitleCount ?: 0) + (source?.externalSubtitlesCount ?: 0)
        var disabled = false
        val subtitle =
            if (subtitleCount > 0 && subtitleStream == null) {
                disabled = true
                resources.getString(R.string.none)
            } else if (subtitleCount == 0 || subtitleStream == null) {
                null
            } else {
                listOfNotNull(
                    languageName(subtitleStream.language),
                    "SDH".takeIf { subtitleStream.isHearingImpaired },
                    formatSubtitleCodec(subtitleStream.codec),
                ).joinToString(" ")
            }
        subtitle?.let {
            addLabel(it, count = subtitleCount, icon = R.string.fa_closed_captioning, maxWidthDp = 160f, disabled = disabled)
        }
    }

    /** `StreamLabel`: secondaryContainer at 50 % with 4dp corners, 4 / 6dp padding, an optional glyph and the text */
    private fun addLabel(
        text: String,
        count: Int = 0,
        @StringRes icon: Int? = null,
        maxWidthDp: Float? = null,
        disabled: Boolean = false,
    ) {
        val label =
            object : LinearLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val max = maxWidthDp?.let { context.dp(it) }
                    if (max != null) {
                        val mode = MeasureSpec.getMode(widthMeasureSpec)
                        val size = MeasureSpec.getSize(widthMeasureSpec)
                        val spec =
                            if (mode == MeasureSpec.UNSPECIFIED) {
                                MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)
                            } else {
                                MeasureSpec.makeMeasureSpec(minOf(size, max), mode)
                            }
                        super.onMeasure(spec, heightMeasureSpec)
                    } else {
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
            }
        label.orientation = HORIZONTAL
        label.gravity = Gravity.CENTER_VERTICAL
        label.showDividers = SHOW_DIVIDER_MIDDLE
        label.dividerDrawable = SpaceDrawable(context.dp(2))
        label.background =
            GradientDrawable().apply {
                setColor(withAlpha(AppTheme.palette.secondaryContainer, .5f))
                cornerRadius = context.dpF(4f)
            }
        label.setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))
        if (icon != null) {
            label.addView(
                chipText(context.getString(icon)).apply { typeface = AppFonts.icons(context) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        val countToUse = if (disabled) count else count - 1
        val string = if (countToUse > 0) "$text (+$countToUse)" else text
        label.addView(
            chipText(string).apply {
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** The chips' text: SemiBold at [fontSizeSp] in the body font, with bodyLarge's line height (the inherited style) */
    private fun chipText(text: String): TextView =
        AppCompatTextView(context).apply {
            this.text = text
            typeface = AppFonts.body(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            setTextColor(AppTheme.palette.onSurface)
            includeFontPadding = false
            val body = TextRole.BODY_LARGE
            val lineHeightSp = if (AppTheme.useWeaselFonts) body.neonSp * body.neonLineHeight else body.stockLineHeightSp
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeightSp, resources.displayMetrics)
            setLineSpacing(px - paint.getFontMetricsInt(null), 1f)
        }
}

/** `SeriesName`: headlineMedium SemiBold, two lines at most */
fun seriesNameView(context: Context): TextView =
    AppCompatTextView(context).apply {
        applyTextStyle(ControlTextStyle.of(TextRole.HEADLINE_MEDIUM, weight = 600))
        setTextColor(AppTheme.palette.onSurface)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

/** `EpisodeName`: headlineSmall at 20sp, one line */
fun episodeNameView(context: Context): TextView =
    AppCompatTextView(context).apply {
        applyTextStyle(ControlTextStyle.of(TextRole.HEADLINE_SMALL, sizeSp = 20f))
        setTextColor(AppTheme.palette.onSurface)
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

/** `EpisodeName(episode)`: the episode's title, else its name */
fun episodeName(dto: BaseItemDto?): String = dto?.episodeTitle ?: dto?.name ?: ""

/** A tagline: bodyLarge (scaled) in italics */
fun taglineView(
    context: Context,
    scale: Float = 1f,
): TextView =
    detailText(context, scaledRole(TextRole.BODY_LARGE, scale)).apply {
        setTypeface(typeface, Typeface.ITALIC)
    }

/** "Directed by …": the directors with a name, or null */
fun directorNames(people: List<BaseItemPerson>?): String? =
    people
        ?.filter { it.type == PersonKind.DIRECTOR && it.name.isNotNullOrBlank() }
        ?.joinToString(", ") { it.name!! }
        ?.takeIf { it.isNotNullOrBlank() }

/**
 * `KidMetadataLine`: "2024 • 1h 38m • [PG]": the parts 12dp apart with dots at 55 %, then the rating in a 2dp outlined
 * badge (6dp corners, ExtraBold at 88 %), SemiBold in the given style, always the Neon text colour.
 */
class KidMetadataLineView(
    context: Context,
    private val style: ControlTextStyle,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = SpaceDrawable(context.dp(12))
    }

    fun bind(
        parts: List<String>,
        rating: String?,
    ) {
        removeAllViews()
        visibility = if (parts.isEmpty() && rating.isNullOrBlank()) View.GONE else View.VISIBLE
        val semi = style.copy(weight = 600, fallbackWeight = 600)
        parts.forEachIndexed { index, part ->
            if (index > 0) addView(text("•", semi, withAlpha(NeonColors.TEXT, .55f)))
            addView(text(part, semi, NeonColors.TEXT))
        }
        if (!rating.isNullOrBlank()) {
            if (parts.isNotEmpty()) addView(text("•", semi, withAlpha(NeonColors.TEXT, .55f)))
            val size = if (semi.fallback != null) (semi.fallbackSizeSp ?: defaultSize(semi.fallback)) else semi.sizeSp
            val badge =
                text(rating, semi.withSize(size * .88f).copy(weight = 800, fallbackWeight = 800), NeonColors.TEXT).apply {
                    background =
                        GradientDrawable().apply {
                            setStroke(context.dp(2), NeonColors.TEXT)
                            cornerRadius = context.dpF(6f)
                        }
                    setPadding(context.dp(8), context.dp(1), context.dp(8), context.dp(1))
                }
            addView(badge)
        }
    }

    private fun defaultSize(role: TextRole): Float = if (AppTheme.useWeaselFonts) role.neonSp else role.stockSp

    private fun text(
        value: String,
        style: ControlTextStyle,
        color: Int,
    ): TextView =
        AppCompatTextView(context).apply {
            text = value
            applyTextStyle(style)
            setTextColor(color)
            maxLines = 1
            isSingleLine = true
        }
}

/** The runtime of a kid page's metadata line ("1h 38m"), or null */
fun kidRuntime(
    context: Context,
    runTimeTicks: Long?,
): String? =
    runTimeTicks
        ?.ticks
        ?.roundMinutes
        ?.takeIf { it > Duration.ZERO }
        ?.let { context.resources.formatDuration(it) }

/**
 * `KidEpisodeInfo`: "S1 E9 • 23m • [rating]" (the episode's rating, else [seriesRating]) and the episode's description
 * up to [KidTitlePage.overviewLines], 4dp apart.
 */
class KidEpisodeInfoView(
    context: Context,
    textScale: Float = 1f,
) : FractionColumn(context, 1f, 4f) {
    private val line = KidMetadataLineView(context, scaledRole(TextRole.TITLE_SMALL, textScale))
    val overview =
        OverviewTextView(context).apply {
            maxLines = KidTitlePage.overviewLines(compact = false)
            compactMaxLines = KidTitlePage.overviewLines(compact = true)
            setTextStyle(scaledRole(TextRole.BODY_MEDIUM, textScale))
        }

    init {
        addPadded(line)
        addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    var compact: Boolean
        get() = overview.compact
        set(value) {
            overview.compact = value
        }

    fun bind(
        episode: BaseItemDto,
        seriesRating: String?,
    ) {
        line.bind(
            listOfNotNull(KidTitlePage.episodeCode(episode), kidRuntime(context, episode.runTimeTicks)),
            KidTitlePage.episodeRating(episode, seriesRating),
        )
        val text = episode.overview?.takeIf { it.isNotBlank() }
        overview.visibility = if (text != null) View.VISIBLE else View.GONE
        overview.setText(text)
    }
}

/** The start padding of header texts (`HeaderUtils.startPadding`) in px */
internal fun Context.headerStartPx(): Int = dp(HeaderDimens.START_PADDING)
