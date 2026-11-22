package com.github.damontecres.wholphin.ui.playback

import androidx.compose.ui.layout.ContentScale
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PrefContentScale

// Compose-only (old player): the View player uses leanback.playback.VideoScale. Delete with the old Compose UI.

val playbackScaleOptions =
    mapOf(
        ContentScale.Fit to R.string.content_scale_fit,
        ContentScale.None to R.string.none,
        ContentScale.Crop to R.string.content_scale_crop,
//        ContentScale.Inside to "Inside",
        ContentScale.FillBounds to R.string.content_scale_fill,
        ContentScale.FillWidth to R.string.content_scale_fill_width,
        ContentScale.FillHeight to R.string.content_scale_fill_height,
    )

val PrefContentScale.scale: ContentScale
    get() =
        when (this) {
            PrefContentScale.FIT -> ContentScale.Fit
            PrefContentScale.NONE -> ContentScale.None
            PrefContentScale.CROP -> ContentScale.Crop
            PrefContentScale.FILL -> ContentScale.FillBounds
            PrefContentScale.Fill_WIDTH -> ContentScale.FillWidth
            PrefContentScale.FILL_HEIGHT -> ContentScale.FillHeight
            PrefContentScale.UNRECOGNIZED -> ContentScale.Fit
        }
