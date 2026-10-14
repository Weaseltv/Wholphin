package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import org.jellyfin.sdk.model.api.BaseItemKind

object HeaderUtils {
    val topPadding = 48.dp
    val bottomPadding = 32.dp
    val startPadding = 8.dp

    val padding = PaddingValues(top = topPadding, bottom = bottomPadding, start = startPadding)

    val height = 180.dp

    @Composable
    fun detailTopPadding() = if (isWeaselTv()) 16.dp else topPadding

    /**
     * WeaselPlex: the details under a detail page's title are 20% larger than the theme's text.
     * The page shrinks the whole header to fit above the buttons, so this keeps them readable.
     */
    @Composable
    fun detailInfoTextScale() = if (isWeaselTv()) 1.2f else 1f

    val logoHeight = 60.dp

    val modifier =
        Modifier
            .padding(padding)
            .height(height)

    /**
     * The Home / Recommended header. Neon Board: 27dp overscan top, 12dp below, so the first
     * row's cards and captions fit under the header without being cut at the bottom.
     */
    @Composable
    fun homeModifier(): Modifier =
        if (isWeaselTv()) {
            Modifier
                .padding(top = 27.dp, bottom = 12.dp, start = startPadding)
        } else {
            modifier
        }

    /** Library tabs supply the top inset; measure copy naturally so fitting never scales empty space. */
    @Composable
    fun recommendedModifier(): Modifier =
        if (isWeaselTv()) {
            Modifier
                .padding(top = 4.dp, bottom = 12.dp, start = startPadding)
        } else {
            homeModifier()
        }
}

/** This style with its font size and line height multiplied by [scale]. */
fun TextStyle.scaled(scale: Float) =
    if (scale == 1f) {
        this
    } else {
        copy(
            fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
            lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
        )
    }

/** A short, localized label for an item kind, used by eyebrows and card badges. */
fun itemKindLabel(
    context: Context,
    kind: BaseItemKind?,
): String? =
    when (kind) {
        BaseItemKind.MOVIE -> context.getString(R.string.kind_movie)
        BaseItemKind.SERIES -> context.getString(R.string.show)
        BaseItemKind.SEASON -> context.getString(R.string.kind_season)
        BaseItemKind.EPISODE -> context.getString(R.string.kind_episode)
        BaseItemKind.TV_CHANNEL, BaseItemKind.LIVE_TV_CHANNEL -> context.getString(R.string.kind_live)
        BaseItemKind.TV_PROGRAM, BaseItemKind.LIVE_TV_PROGRAM, BaseItemKind.PROGRAM -> context.getString(R.string.live_tv)
        BaseItemKind.RECORDING -> context.getString(R.string.recordings)
        BaseItemKind.MUSIC_ALBUM -> context.getString(R.string.album)
        BaseItemKind.MUSIC_ARTIST -> context.getString(R.string.artist)
        BaseItemKind.AUDIO -> context.getString(R.string.kind_song)
        BaseItemKind.BOX_SET -> context.getString(R.string.collection)
        BaseItemKind.PLAYLIST -> context.getString(R.string.playlist)
        else -> null
    }
