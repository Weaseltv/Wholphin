package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import org.jellyfin.sdk.model.api.MediaStreamType

/** The name of a stream type for "Choose %s" titles (moved out of Dialogs.kt, no Compose) */
@StringRes
fun resourceFor(type: MediaStreamType): Int =
    when (type) {
        MediaStreamType.AUDIO -> R.string.audio
        MediaStreamType.VIDEO -> R.string.video
        MediaStreamType.SUBTITLE -> R.string.subtitles
        MediaStreamType.EMBEDDED_IMAGE -> 0
        MediaStreamType.DATA -> 0
        MediaStreamType.LYRIC -> 0
    }
