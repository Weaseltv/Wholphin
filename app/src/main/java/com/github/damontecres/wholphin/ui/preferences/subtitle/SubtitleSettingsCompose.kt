package com.github.damontecres.wholphin.ui.preferences.subtitle

import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.applyToMpv
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize

/*
 * Compose adapters for the old Compose callers (playback page and view model, the old subtitle style page) while
 * [SubtitleSettings] takes plain floats (no-Compose rewrite, 2026-11-22). Compose-only: delete with those callers.
 */

/** [SubtitleSettings.calculateEdgeSize] for a Compose [Density] */
fun SubtitlePreferences.calculateEdgeSize(density: Density): Float = calculateEdgeSize(density.density)

/** [SubtitleSettings.applyToMpv] for a Compose [Density] (its density and font scale) */
fun SubtitlePreferences.applyToMpv(
    configuration: Configuration,
    density: Density,
) = applyToMpv(configuration, density.density, density.fontScale)
