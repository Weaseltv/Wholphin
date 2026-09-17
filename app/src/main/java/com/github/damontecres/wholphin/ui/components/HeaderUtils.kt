package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import org.jellyfin.sdk.model.api.BaseItemKind

object HeaderUtils {
    val topPadding = 48.dp
    val bottomPadding = 32.dp
    val startPadding = 8.dp

    val padding = PaddingValues(top = topPadding, bottom = bottomPadding, start = startPadding)

    val height = 180.dp

    val logoHeight = 60.dp

    val modifier =
        Modifier
            .padding(padding)
            .height(height)
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
