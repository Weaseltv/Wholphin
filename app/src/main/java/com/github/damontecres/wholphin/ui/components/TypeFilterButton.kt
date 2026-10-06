package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.theme.neonOutlineColors
import com.github.damontecres.wholphin.ui.theme.neonSurfaceBorder
import com.github.damontecres.wholphin.ui.theme.neonSurfaceGlow
import com.github.damontecres.wholphin.ui.theme.typeAccent
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * One of the outlined ALL / MOVIES / SHOWS filters. [types] is what the filter puts in
 * `includeItemTypes`; null means everything the page shows.
 */
data class TypeFilterOption(
    @param:StringRes val title: Int,
    val types: List<BaseItemKind>?,
) {
    /** The first type picks the colour, so SHOWS stays the series colour when it also covers seasons. */
    val accentKind: BaseItemKind? get() = types?.firstOrNull()
}

val StreamingTypeFilters =
    listOf(
        TypeFilterOption(R.string.streaming_all, null),
        TypeFilterOption(R.string.movies_title, listOf(BaseItemKind.MOVIE)),
        TypeFilterOption(R.string.streaming_shows, listOf(BaseItemKind.SERIES)),
    )

/** Seasons and episodes count as shows: the long-press menu can add either to the Watchlist. */
val WatchlistTypeFilters =
    listOf(
        TypeFilterOption(R.string.streaming_all, null),
        TypeFilterOption(R.string.movies_title, listOf(BaseItemKind.MOVIE)),
        TypeFilterOption(
            R.string.streaming_shows,
            listOf(BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE),
        ),
    )

@Composable
fun TypeFilterButton(
    option: TypeFilterOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = typeAccent(option.accentKind)
    Button(
        onClick = onClick,
        colors = neonOutlineColors(accent),
        border =
            neonSurfaceBorder(
                accent = accent,
                restWidth = 1.dp,
                restColor = accent,
            ),
        glow = neonSurfaceGlow(accent),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Text(
            text = stringResource(option.title).uppercase(),
            color = accent,
        )
    }
}
