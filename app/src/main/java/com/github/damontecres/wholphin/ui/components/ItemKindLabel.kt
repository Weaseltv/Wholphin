package com.github.damontecres.wholphin.ui.components

import android.content.Context
import com.github.damontecres.wholphin.R
import org.jellyfin.sdk.model.api.BaseItemKind

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
        BaseItemKind.BOX_SET -> context.getString(R.string.collection)
        BaseItemKind.PLAYLIST -> context.getString(R.string.playlist)
        else -> null
    }
