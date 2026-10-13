package com.github.damontecres.wholphin.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.LocalNeonOutlineRest
import com.github.damontecres.wholphin.ui.theme.NeonBoard
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
    /** Smaller label and padding, for rows with many buttons. */
    compact: Boolean = false,
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
        contentPadding = PaddingValues(horizontal = if (compact) COMPACT_PADDING else 12.dp),
        contentHeight = if (compact) COMPACT_BUTTON_SIZE else MinButtonSize,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        Text(
            text = stringResource(option.title).uppercase(),
            color = accent,
            fontSize = if (compact) COMPACT_FONT_SIZE else TextUnit.Unspecified,
        )
    }
}

/**
 * A Popular / Trending / Top 10 quick list on a streaming collection, in the collection's accent
 * like its other buttons. It stays filled while its list is showing.
 */
@Composable
fun QuickListButton(
    @StringRes title: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The label colour, if fixed; by default white, and the accent while focused. */
    contentColor: Color = Color.Unspecified,
    compact: Boolean = false,
    /** Shows a small spinner while this list loads. */
    loading: Boolean = false,
) {
    val accent = LocalNeonAccent.current
    Button(
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) NeonBoard.chipOn(accent) else Color.Transparent,
                contentColor = NeonBoard.Text,
                focusedContainerColor = NeonBoard.chipOn(accent),
                focusedContentColor = accent,
                pressedContainerColor = NeonBoard.chipOn(accent),
                pressedContentColor = accent,
            ),
        // Grey at rest like other outline buttons; the active list keeps the accent so it's clear
        // which one is showing
        border =
            neonSurfaceBorder(
                accent = accent,
                restWidth = if (selected) 2.dp else 1.dp,
                restColor = if (selected) accent else LocalNeonOutlineRest.current ?: NeonBoard.Line2,
            ),
        glow = neonSurfaceGlow(accent),
        contentPadding = PaddingValues(horizontal = if (compact) COMPACT_PADDING else 12.dp),
        contentHeight = if (compact) COMPACT_BUTTON_SIZE else MinButtonSize,
        modifier = modifier.semantics { this.selected = selected },
    ) {
        if (loading) {
            CircularProgress(
                Modifier
                    .padding(end = 6.dp)
                    .size(14.dp),
            )
        }
        Text(
            text = stringResource(title).uppercase(),
            color = contentColor,
            fontSize = if (compact) COMPACT_FONT_SIZE else TextUnit.Unspecified,
        )
    }
}

/** Side padding for compact filter buttons, e.g. a streaming collection's row. */
internal val COMPACT_PADDING = 6.dp

/** Label size for compact buttons, so a streaming collection's row fits on one line. */
internal val COMPACT_FONT_SIZE = 15.sp

/** Height of compact buttons, and the size of compact icon buttons. */
internal val COMPACT_BUTTON_SIZE = 36.dp
