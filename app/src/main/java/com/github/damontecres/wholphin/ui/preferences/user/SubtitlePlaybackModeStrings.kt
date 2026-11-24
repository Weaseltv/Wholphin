package com.github.damontecres.wholphin.ui.preferences.user

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode

/** The name of the server profile's subtitle mode; Compose-free (moved from UserProfilePreferencesPage.kt, 2026-11-22) */
val SubtitlePlaybackMode.stringRes: Int
    @StringRes get() =
        when (this) {
            SubtitlePlaybackMode.DEFAULT -> R.string.subtitle_mode_default
            SubtitlePlaybackMode.ALWAYS -> R.string.subtitle_mode_always
            SubtitlePlaybackMode.ONLY_FORCED -> R.string.subtitle_mode_only_forced
            SubtitlePlaybackMode.NONE -> R.string.subtitle_mode_none
            SubtitlePlaybackMode.SMART -> R.string.subtitle_mode_smart
        }
