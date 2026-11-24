package com.github.damontecres.wholphin.ui.main.settings

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R

/** The non-library rows Home settings can add (Compose-free, moved from HomeSettingsAddRow.kt 2026-11-22) */
enum class MetaRowType(
    @param:StringRes val stringId: Int,
) {
    CONTINUE_WATCHING(R.string.continue_watching),
    NEXT_UP(R.string.next_up),
    COMBINED_CONTINUE_WATCHING(R.string.combine_continue_next),
    FAVORITES(R.string.favorites),
    DISCOVER(R.string.discover),
    COLLECTION(R.string.collection),
    PLAYLIST(R.string.playlist),
}
