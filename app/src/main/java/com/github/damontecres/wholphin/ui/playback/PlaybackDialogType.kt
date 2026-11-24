package com.github.damontecres.wholphin.ui.playback

/** Which player dialog is open (Compose-free, shared by the old Compose player and the View player) */
enum class PlaybackDialogType {
    DEBUG,
    CAPTIONS,
    SETTINGS,
    AUDIO,
    PLAYBACK_SPEED,
    VIDEO_SCALE,
    SUBTITLE_DELAY,
}
