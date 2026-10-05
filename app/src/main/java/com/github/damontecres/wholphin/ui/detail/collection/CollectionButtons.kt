package com.github.damontecres.wholphin.ui.detail.collection

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.DeleteButton
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.ExpandablePlayButton
import com.github.damontecres.wholphin.ui.components.FilterByButton
import com.github.damontecres.wholphin.ui.components.SortByButton
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.theme.neonOutlineColors
import com.github.damontecres.wholphin.ui.theme.neonPrimaryBorder
import com.github.damontecres.wholphin.ui.theme.neonPrimaryColors
import com.github.damontecres.wholphin.ui.theme.neonPrimaryGlow
import com.github.damontecres.wholphin.ui.theme.neonSurfaceBorder
import com.github.damontecres.wholphin.ui.theme.neonSurfaceGlow
import com.github.damontecres.wholphin.ui.theme.typeAccent
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.time.Duration

@Composable
fun CollectionButtons(
    state: CollectionState,
    onSortChange: (SortAndDirection) -> Unit,
    onClickPlayAll: (Boolean) -> Unit,
    onFilterChange: (GetItemsFilter) -> Unit,
    getPossibleFilterValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    onClickViewOptions: () -> Unit,
    favoriteOnClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    canDelete: Boolean,
    moreOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortOptions = MovieSortOptions
    val filterOptions = DefaultFilterOptions
    val firstFocus = remember { FocusRequester() }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(8.dp),
            modifier =
                Modifier
                    .focusGroup()
                    .focusRestorer(firstFocus),
        ) {
            if (state.isStreaming) {
                listOf(
                    R.string.streaming_all to null,
                    R.string.movies_title to listOf(BaseItemKind.MOVIE),
                    R.string.streaming_shows to listOf(BaseItemKind.SERIES),
                ).forEachIndexed { index, (title, types) ->
                    item(title) {
                        val selected = state.itemFilter.includeItemTypes == types
                        val accent = typeAccent(types?.singleOrNull())
                        Button(
                            onClick = { onFilterChange(state.itemFilter.copy(includeItemTypes = types)) },
                            colors = if (selected) neonPrimaryColors(accent) else neonOutlineColors(accent),
                            border =
                                if (selected) {
                                    neonPrimaryBorder()
                                } else {
                                    neonSurfaceBorder(
                                        accent = accent,
                                        restWidth = 1.dp,
                                        restColor = accent,
                                    )
                                },
                            glow = if (selected) neonPrimaryGlow(accent) else neonSurfaceGlow(accent),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier =
                                (if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .semantics { this.selected = selected },
                        ) {
                            Text(
                                text = stringResource(title).uppercase(),
                                color = if (selected) Color.Unspecified else accent,
                            )
                        }
                    }
                }
            }
            item {
                ExpandablePlayButton(
                    title = R.string.play,
                    resume = Duration.ZERO,
                    icon = Icons.Default.PlayArrow,
                    onClick = { onClickPlayAll.invoke(false) },
                    modifier = if (state.isStreaming) Modifier else Modifier.focusRequester(firstFocus),
                )
            }
            item {
                ExpandableFaButton(
                    title = R.string.shuffle,
                    iconStringRes = R.string.fa_shuffle,
                    onClick = { onClickPlayAll.invoke(true) },
                )
            }

            item("favorite") {
                val favorite = remember(state.collection) { state.collection?.favorite == true }
                ExpandableFaButton(
                    title = if (favorite) R.string.remove_favorite else R.string.add_favorite,
                    iconStringRes = R.string.fa_heart,
                    onClick = favoriteOnClick,
                    iconColor = if (favorite) Color.Red else Color.Unspecified,
                    modifier = Modifier,
                )
            }
            if (canDelete) {
                item("delete") {
                    DeleteButton(
                        title = state.collection?.title ?: "",
                        onConfirmDelete = onConfirmDelete,
                        modifier = Modifier,
                    )
                }
            }

            if (!state.isStreaming) {
                item {
                    ExpandableFaButton(
                        title = R.string.view_options,
                        iconStringRes = R.string.fa_sliders,
                        onClick = onClickViewOptions,
                        modifier = Modifier,
                    )
                }
            }

            // More button
            item("more") {
                ExpandablePlayButton(
                    title = R.string.more,
                    resume = Duration.ZERO,
                    icon = Icons.Default.MoreVert,
                    onClick = { moreOnClick.invoke() },
                    modifier = Modifier,
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(8.dp),
            modifier =
                Modifier
                    .focusGroup(),
        ) {
            item {
                SortByButton(
                    sortOptions = sortOptions,
                    current = state.sortAndDirection,
                    onSortChange = onSortChange,
                    modifier = Modifier,
                )
            }
            item {
                FilterByButton(
                    filterOptions = filterOptions,
                    current = state.itemFilter,
                    onFilterChange = onFilterChange,
                    getPossibleValues = getPossibleFilterValues,
                    modifier = Modifier,
                )
            }
        }
    }
}
