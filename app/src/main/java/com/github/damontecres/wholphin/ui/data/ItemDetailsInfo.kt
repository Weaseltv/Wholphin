package com.github.damontecres.wholphin.ui.data

import android.content.res.Resources
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.studioNames
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatAudioCodec
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatSubtitleCodec
import com.github.damontecres.wholphin.util.languageName
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.time.LocalDateTime
import java.util.Locale

/*
 * The item information dialog's data and the label/value lists it shows for each stream, without Compose (moved
 * out of ItemDetailsDialogInfo.kt for the View dialog `leanback.widget.dialog.ItemDetailsDialog`).
 */

/** What the item information ("Media information") dialog shows */
data class ItemDetailsDialogInfo(
    val title: String,
    val overview: String?,
    val genres: List<String>,
    val files: List<MediaSourceInfo>,
    val studios: List<String> = emptyList(),
    val lastPlayed: LocalDateTime? = null,
) {
    constructor(item: BaseItem) : this(
        title = item.name ?: "",
        overview = item.data.overview,
        genres = item.data.genres.orEmpty(),
        files = item.data.mediaSources.orEmpty(),
        studios = item.studioNames,
        lastPlayed = item.data.userData?.lastPlayedDate,
    )
}

fun titleIndex(
    title: String,
    index: Int,
    total: Int,
) = if (total > 1) {
    "$title (${index + 1})"
} else {
    title
}

fun buildVideoStreamInfo(
    resources: Resources,
    stream: MediaStream,
): List<Pair<String, String>> =
    buildList {
        val yesStr = resources.getString(R.string.yes)
        val noStr = resources.getString(R.string.no)

        val titleLabel = resources.getString(R.string.title)
        val codecLabel = resources.getString(R.string.codec)
        val resolutionLabel = resources.getString(R.string.resolution)
        val aspectRatioLabel = resources.getString(R.string.aspect_ratio)
        val framerateLabel = resources.getString(R.string.framerate)
        val bitrateLabel = resources.getString(R.string.bitrate)
        val profileLabel = resources.getString(R.string.profile)
        val levelLabel = resources.getString(R.string.level)
        val interlacedLabel = resources.getString(R.string.interlaced)
        val videoRangeLabel = resources.getString(R.string.video_range)
        val sdrStr = resources.getString(R.string.sdr)
        val hdrStr = resources.getString(R.string.hdr)

        stream.title?.let { add(titleLabel to it) }
        stream.codec?.let { add(codecLabel to it.uppercase()) }
        if (stream.width != null && stream.height != null) {
            add(resolutionLabel to "${stream.width}x${stream.height}")
        }
        if (stream.width != null && stream.height != null) {
            val aspectRatio = calculateAspectRatio(stream.width!!, stream.height!!)
            add(aspectRatioLabel to aspectRatio)
        }
        stream.bitRate?.let { add(bitrateLabel to formatBitrate(it)) }
        stream.averageFrameRate?.let {
            add(framerateLabel to String.format(Locale.getDefault(), "%.3f", it))
        }

        stream.videoRange.let {
            val rangeStr =
                when (it) {
                    VideoRange.SDR -> sdrStr
                    VideoRange.HDR -> hdrStr
                    VideoRange.UNKNOWN -> null
                }
            rangeStr?.let { add(videoRangeLabel to it) }
        }
        stream.profile?.let { add(profileLabel to it) }
        stream.level?.let { add(levelLabel to it.toString()) }
        stream.isInterlaced.let { add(interlacedLabel to if (it) yesStr else noStr) }
    }

fun buildVideoStreamInfoAdditional(
    resources: Resources,
    stream: MediaStream,
): List<Pair<String, String>> =
    buildList {
        val yesStr = resources.getString(R.string.yes)
        val noStr = resources.getString(R.string.no)

        val avcLabel = resources.getString(R.string.avc)
        val anamorphicLabel = resources.getString(R.string.anamorphic)
        val bitDepthLabel = resources.getString(R.string.bit_depth)

        val videoRangeTypeLabel = resources.getString(R.string.video_range_type)
        val colorSpaceLabel = resources.getString(R.string.color_space)
        val colorTransferLabel = resources.getString(R.string.color_transfer)
        val colorPrimariesLabel = resources.getString(R.string.color_primaries)
        val pixelFormatLabel = resources.getString(R.string.pixel_format)
        val refFramesLabel = resources.getString(R.string.ref_frames)
        val nalLabel = resources.getString(R.string.nal)
        val dolbyVisionLabel = resources.getString(R.string.dolby_vision)

        val sdrStr = resources.getString(R.string.sdr)
        val hdr10Str = resources.getString(R.string.hdr10)
        val hdr10PlusStr = resources.getString(R.string.hdr10_plus)
        val hlgStr = resources.getString(R.string.hlg)
        val bitUnit = resources.getString(R.string.bit_unit)

        stream.isAvc?.let { add(avcLabel to if (it) yesStr else noStr) }
        stream.isAnamorphic?.let { add(anamorphicLabel to if (it) yesStr else noStr) }
        stream.bitDepth?.let { add(bitDepthLabel to "$it $bitUnit") }
        stream.videoRangeType?.let {
            val rangeTypeStr =
                when (it) {
                    VideoRangeType.SDR -> sdrStr

                    VideoRangeType.HDR10 -> hdr10Str

                    VideoRangeType.HDR10_PLUS -> hdr10PlusStr

                    VideoRangeType.HLG -> hlgStr

                    VideoRangeType.DOVI,
                    VideoRangeType.DOVI_WITH_HDR10,
                    VideoRangeType.DOVI_WITH_HLG,
                    VideoRangeType.DOVI_WITH_SDR,
                    -> resources.getString(R.string.dolby_vision)

                    VideoRangeType.UNKNOWN -> null

                    else -> null
                }
            rangeTypeStr?.let { add(videoRangeTypeLabel to it) }
        }
        stream.colorSpace?.let { add(colorSpaceLabel to it) }
        stream.colorTransfer?.let { add(colorTransferLabel to it) }
        stream.colorPrimaries?.let { add(colorPrimariesLabel to it) }
        stream.pixelFormat?.let { add(pixelFormatLabel to it) }
        stream.refFrames?.let { add(refFramesLabel to it.toString()) }
        stream.nalLengthSize?.let { add(nalLabel to it) }
        stream.videoDoViTitle?.let { add(dolbyVisionLabel to it) }
    }

fun buildAudioStreamInfo(
    resources: Resources,
    stream: MediaStream,
): List<Pair<String, String>> =
    buildList {
        val titleLabel = resources.getString(R.string.title)
        val languageLabel = resources.getString(R.string.language)
        val codecLabel = resources.getString(R.string.codec)
        val layoutLabel = resources.getString(R.string.layout)
        val channelsLabel = resources.getString(R.string.channels)
        val bitrateLabel = resources.getString(R.string.bitrate)
        val sampleRateLabel = resources.getString(R.string.sample_rate)
        val defaultLabel = resources.getString(R.string.default_track)
        val profileLabel = resources.getString(R.string.profile)
        val yesStr = resources.getString(R.string.yes)
        val noStr = resources.getString(R.string.no)
        val sampleRateUnit = resources.getString(R.string.sample_rate_unit)

        stream.title?.let { add(titleLabel to it) }
        stream.language?.let { add(languageLabel to languageName(it)) }
        stream.codec?.let {
            val formattedCodec = formatAudioCodec(resources, it, stream.profile) + " ($it)"
            add(codecLabel to formattedCodec)
        }
        stream.channelLayout?.let { add(layoutLabel to it) }
        stream.channels?.let { add(channelsLabel to it.toString()) }
        stream.profile?.let { add(profileLabel to it) }
        stream.bitRate?.let { add(bitrateLabel to formatBitrate(it)) }
        stream.sampleRate?.let { add(sampleRateLabel to "$it $sampleRateUnit") }
        stream.isDefault?.let { add(defaultLabel to if (it) yesStr else noStr) }
    }

fun buildSubtitleStreamInfo(
    resources: Resources,
    stream: MediaStream,
    showPath: Boolean,
): List<Pair<String, String>> =
    buildList {
        val titleLabel = resources.getString(R.string.title)
        val languageLabel = resources.getString(R.string.language)
        val codecLabel = resources.getString(R.string.codec)
        val defaultLabel = resources.getString(R.string.default_track)
        val forcedLabel = resources.getString(R.string.forced_track)
        val externalLabel = resources.getString(R.string.external_track)
        val yesStr = resources.getString(R.string.yes)
        val noStr = resources.getString(R.string.no)
        val pathLabel = resources.getString(R.string.path)

        stream.title?.let { add(titleLabel to it) }
        stream.language?.let { add(languageLabel to languageName(it)) }
        stream.codec?.let {
            val formattedCodec = formatSubtitleCodec(it) + " ($it)"
            add(codecLabel to formattedCodec)
        }
        stream.isDefault?.let { add(defaultLabel to if (it) yesStr else noStr) }
        stream.isForced?.let { add(forcedLabel to if (it) yesStr else noStr) }
        stream.isExternal?.let { add(externalLabel to if (it) yesStr else noStr) }
        stream.isHearingImpaired?.let {
            add((stream.localizedHearingImpaired ?: "SDH") to if (it) yesStr else noStr)
        }
        if (showPath) {
            stream.path?.let { add(pathLabel to it) }
        }
    }

internal fun calculateAspectRatio(
    width: Int,
    height: Int,
): String {
    val gcd = gcd(width, height)
    val w = width / gcd
    val h = height / gcd
    return "$w:$h"
}

private fun gcd(
    a: Int,
    b: Int,
): Int = if (b == 0) a else gcd(b, a % b)
