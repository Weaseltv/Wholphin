package com.github.damontecres.wholphin.util.mpv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.damontecres.wholphin.mpv.MPVLib

/**
 * Compose adapter kept for the old Compose subtitle settings (`ui/preferences/subtitle/SubtitleSettings.kt`), which
 * pass a Compose [Color]. Compose-only: delete once that file no longer calls it.
 */
fun MPVLib.setPropertyColor(
    property: String,
    color: Color,
) = setPropertyColor(property, color.toArgb())
