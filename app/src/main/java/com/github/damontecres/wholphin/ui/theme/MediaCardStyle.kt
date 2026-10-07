package com.github.damontecres.wholphin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.ui.main.settings.Library
import java.util.UUID

val LocalHomeMediaSettings = compositionLocalOf { HomePageResolvedSettings.EMPTY }
val LocalPosterCountAppearance = compositionLocalOf<HomeCardAppearance?> { null }
val LocalHidePosterTypeBadge = compositionLocalOf { false }

/** Library pages use the same saved focus treatment as their Recently Added Home row. */
fun homeMediaAppearance(settings: HomePageResolvedSettings, libraryId: UUID? = null): HomeCardAppearance {
    val matched = libraryId?.let { id -> settings.rows.firstOrNull {
        when (val row = it.config) {
            is HomeRowConfig.RecentlyAdded -> row.parentId == id
            is HomeRowConfig.RecentlyReleased -> row.parentId == id
            else -> false
        }
    } }
    val standard = settings.rows.firstOrNull {
        it.config is HomeRowConfig.ContinueWatchingCombined || it.config is HomeRowConfig.ContinueWatching
    } ?: settings.rows.firstOrNull { it.config is HomeRowConfig.RecentlyAdded }
    return (matched ?: standard)?.config?.viewOptions?.cardAppearance
        ?: ApprovedCollectionCardAppearance.copy(borderOpacityPercent = 80)
}

@Composable
fun WatchlistCardStyle(item: BaseItem?, libraries: List<Library>, content: @Composable () -> Unit) {
    if (!isWeaselTv()) {
        content()
        return
    }
    val library = libraries.firstOrNull { it.itemId == item?.libraryId }
    val accent = library?.let { libraryAccent(it.name, it.collectionType) } ?: typeAccent(item?.type)
    CompositionLocalProvider(
        LocalHomeCardAppearance provides homeMediaAppearance(LocalHomeMediaSettings.current, item?.libraryId).copy(accentIndex = 0),
        LocalHomeRowAccent provides accent,
        LocalHomeCardBorderAccent provides null,
        content = content,
    )
}
