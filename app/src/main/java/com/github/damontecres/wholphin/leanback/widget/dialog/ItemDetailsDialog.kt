package com.github.damontecres.wholphin.leanback.widget.dialog

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.text.WrapWeightRow
import com.github.damontecres.wholphin.ui.data.ItemDetailsDialogInfo
import com.github.damontecres.wholphin.ui.data.buildAudioStreamInfo
import com.github.damontecres.wholphin.ui.data.buildSubtitleStreamInfo
import com.github.damontecres.wholphin.ui.data.buildVideoStreamInfo
import com.github.damontecres.wholphin.ui.data.buildVideoStreamInfoAdditional
import com.github.damontecres.wholphin.ui.data.titleIndex
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.formatDate
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.extensions.ticks

/**
 * The item information dialog (`ItemDetailsDialog`, "Media information"): a [ScrollableDialog] 680dp wide and at most
 * 440dp tall with the title (headlineSmall), studios, genres, overview and last played date, then for each file with
 * streams a full-width divider, its General section (container, path and id for administrators, size, bitrate,
 * runtime), each video stream (with a second column of details), and the audio and subtitle streams three to a row.
 *
 * Focus: the content area; D-pad UP / DOWN scroll it.
 */
class ItemDetailsDialog(
    context: Context,
    info: ItemDetailsDialogInfo,
    showFilePath: Boolean,
    accent: Int? = null,
    opener: View? = null,
) : ScrollableDialog(context, widthDp = 680f, maxHeightDp = 440f, itemSpacingDp = 8f, accent = accent, opener = opener) {
    private val onSurface = AppTheme.palette.onSurface

    init {
        val resources = context.resources
        content.clipToPadding = false
        content.addView(header(info))
        info.files.forEachIndexed { index, source ->
            val mediaStreams = source.mediaStreams.orEmpty()
            if (mediaStreams.isEmpty()) return@forEachIndexed
            // Spacer, then a divider through the dialog's 16dp padding to the panel edges
            content.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(View(context), LinearLayout.LayoutParams(1, context.dp(8)))
                    addView(
                        View(context).apply { setBackgroundColor(AppTheme.palette.borderVariant) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)),
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = -context.dp(16)
                    marginEnd = -context.dp(16)
                },
            )
            val unknown = resources.getString(R.string.unknown)
            content.addView(
                section(
                    titleIndex(resources.getString(R.string.general), index, info.files.size),
                    buildList {
                        source.container?.let { add(resources.getString(R.string.container) to it) }
                        if (showFilePath) {
                            source.path?.let { add(resources.getString(R.string.path) to it) }
                            add("ID" to (source.id ?: unknown))
                        }
                        source.size?.let { add(resources.getString(R.string.file_size) to formatBytes(it)) }
                        source.bitrate?.let { add(resources.getString(R.string.bitrate) to formatBitrate(it)) }
                        source.runTimeTicks?.let { add(resources.getString(R.string.runtime_sort) to resources.formatDuration(it.ticks)) }
                    },
                ),
            )
            val videoStreams = mediaStreams.filter { it.type == MediaStreamType.VIDEO }
            videoStreams.forEachIndexed { i, stream ->
                content.addView(
                    section(
                        titleIndex(resources.getString(R.string.video), i, videoStreams.size),
                        buildVideoStreamInfo(resources, stream),
                        buildVideoStreamInfoAdditional(resources, stream),
                    ),
                )
            }
            val audioStreams = mediaStreams.filter { it.type == MediaStreamType.AUDIO }
            audioStreams.chunked(3).forEachIndexed { groupIndex, group ->
                content.addView(
                    threeColumns(
                        group.mapIndexed { i, stream ->
                            section(
                                titleIndex(resources.getString(R.string.audio), groupIndex * 3 + i, audioStreams.size),
                                buildAudioStreamInfo(resources, stream),
                            )
                        },
                    ),
                )
            }
            val subtitleStreams = mediaStreams.filter { it.type == MediaStreamType.SUBTITLE }
            subtitleStreams.chunked(3).forEachIndexed { groupIndex, group ->
                content.addView(
                    threeColumns(
                        group.mapIndexed { i, stream ->
                            section(
                                titleIndex(resources.getString(R.string.subtitle), groupIndex * 3 + i, subtitleStreams.size),
                                buildSubtitleStreamInfo(resources, stream, showFilePath),
                            )
                        },
                    ),
                )
            }
            if (index != info.files.lastIndex) {
                content.addView(View(context), LinearLayout.LayoutParams(1, context.dp(8)))
            }
        }
    }

    private fun text(
        value: CharSequence,
        role: TextRole,
        color: Int = onSurface,
    ) = TextView(context).apply {
        text = value
        applyTextRole(role)
        setTextColor(color)
    }

    private fun header(info: ItemDetailsDialogInfo): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val gap = context.dp(16)

            fun add(view: View) {
                addView(
                    view,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (childCount > 0) topMargin = gap
                    },
                )
            }
            add(text(info.title, TextRole.HEADLINE_SMALL))
            if (info.studios.isNotEmpty()) add(text(info.studios.joinToString(", "), TextRole.TITLE_MEDIUM))
            if (info.genres.isNotEmpty()) add(text(info.genres.joinToString(", "), TextRole.TITLE_SMALL))
            if (info.overview.isNotNullOrBlank()) add(text(info.overview!!, TextRole.BODY_MEDIUM))
            info.lastPlayed?.let { formatDate(it) }?.let { lastPlayed ->
                add(text("${context.getString(R.string.last_played)}: $lastPlayed", TextRole.BODY_MEDIUM))
            }
        }

    /** `MediaInfoSection`: a titleLarge title over label/value rows (and an optional second column), 12dp inset */
    private fun section(
        title: String,
        items: List<Pair<String, String>>,
        additional: List<Pair<String, String>> = emptyList(),
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(4))
            addView(text(title, TextRole.TITLE_LARGE))
            val columns =
                WrapWeightRow(context, spacingDp = 24f).apply {
                    setPaddingRelative(context.dp(12), context.dp(2), 0, 0)
                    addView(pairs(items))
                    if (additional.isNotEmpty()) addView(pairs(additional))
                }
            addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun pairs(items: List<Pair<String, String>>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            items.forEachIndexed { i, (label, value) ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(text("$label: ", TextRole.BODY_MEDIUM, withAlpha(onSurface, ((onSurface ushr 24) / 255f) * .7f)))
                        addView(text(value, TextRole.BODY_MEDIUM))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (i > 0) topMargin = context.dp(2)
                    },
                )
            }
        }

    /** Three equal columns 8dp apart; missing ones stay empty */
    private fun threeColumns(sections: List<View>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 3) {
                val view = sections.getOrNull(i) ?: View(context)
                addView(
                    view,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = context.dp(8)
                    },
                )
            }
        }
}
