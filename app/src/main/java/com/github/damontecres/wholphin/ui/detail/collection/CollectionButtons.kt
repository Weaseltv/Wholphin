package com.github.damontecres.wholphin.ui.detail.collection

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.services.StreamingQuickList
import com.github.damontecres.wholphin.ui.components.DeleteButton
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.FilterByButton
import com.github.damontecres.wholphin.ui.components.QuickListButton
import com.github.damontecres.wholphin.ui.components.SortByButton
import com.github.damontecres.wholphin.ui.components.StreamingTypeFilters
import com.github.damontecres.wholphin.ui.components.TypeFilterButton
import com.github.damontecres.wholphin.ui.components.reportDetailActionHeight
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.LocalNeonOutlineRest
import com.github.damontecres.wholphin.ui.theme.streamingProviderAccent

/**
 * A collection's buttons, laid out like the Watchlist's and the same for every collection: ALL /
 * MOVIES / SHOWS on the first row; on the second, the collection's actions on the left (the
 * quick lists on streaming collections) and Random, sort and filter as expanding icons on the
 * right. Labels are uppercase in the button font.
 */
@Composable
fun CollectionButtons(
    state: CollectionState,
    onSortChange: (SortAndDirection) -> Unit,
    onClickRandom: () -> Unit,
    onQuickListChange: (StreamingQuickList) -> Unit,
    onFilterChange: (GetItemsFilter) -> Unit,
    getPossibleFilterValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    onClickViewOptions: () -> Unit,
    favoriteOnClick: () -> Unit,
    onConfirmDelete: () -> Unit,
    canDelete: Boolean,
    moreOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    // Streaming collections use the service's colour for focus borders and glow
    val serviceColor =
        if (state.isStreaming) streamingProviderAccent(state.collection?.name) ?: Color.Unspecified else Color.Unspecified
    CompositionLocalProvider(LocalNeonAccent provides serviceColor.takeOrElse { LocalNeonAccent.current }) {
        Column(
            modifier =
                Modifier
                    .reportDetailActionHeight()
                    .then(modifier)
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(firstFocus),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                // Room for the focus glow, as on the Watchlist
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            ) {
                StreamingTypeFilters.forEachIndexed { index, option ->
                    TypeFilterButton(
                        option = option,
                        selected = state.itemFilter.includeItemTypes == option.types,
                        onClick = { onFilterChange(state.itemFilter.copy(includeItemTypes = option.types)) },
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
            // Grey outlines at rest; the accent shows on focus
            CompositionLocalProvider(LocalNeonOutlineRest provides null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp, bottom = 8.dp),
                ) {
                    if (state.isStreaming) {
                        // Only services the server keeps JustWatch lists for; any others
                        // stay on their newest-first grid
                        val ranked = state.justWatch != null
                        StreamingQuickList.entries.filter { ranked }.forEach { quickList ->
                            QuickListButton(
                                title = quickList.title,
                                selected = state.quickList == quickList,
                                loading = state.quickListLoading == quickList,
                                onClick = { onQuickListChange(quickList) },
                            )
                        }
                    } else {
                        val favorite = remember(state.collection) { state.collection?.favorite == true }
                        ExpandableFaButton(
                            title = if (favorite) R.string.remove_favorite else R.string.add_favorite,
                            iconStringRes = R.string.fa_heart,
                            onClick = favoriteOnClick,
                            iconColor = if (favorite) Color.Red else Color.Unspecified,
                            allCaps = true,
                        )
                        if (canDelete) {
                            DeleteButton(
                                title = state.collection?.title ?: "",
                                onConfirmDelete = onConfirmDelete,
                                allCaps = true,
                            )
                        }
                        ExpandableFaButton(
                            title = R.string.view_options,
                            iconStringRes = R.string.fa_sliders,
                            onClick = onClickViewOptions,
                            allCaps = true,
                        )
                        ExpandableFaButton(
                            title = R.string.more,
                            iconStringRes = R.string.fa_ellipsis_vertical,
                            onClick = moreOnClick,
                            allCaps = true,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    ExpandableFaButton(
                        title = R.string.sort_by_random,
                        iconStringRes = R.string.fa_dice,
                        onClick = onClickRandom,
                        collapsed = true,
                    )
                    SortByButton(
                        sortOptions = MovieSortOptions,
                        current = state.sortAndDirection,
                        onSortChange = onSortChange,
                        collapsed = true,
                    )
                    FilterByButton(
                        filterOptions = DefaultFilterOptions,
                        current = state.itemFilter,
                        onFilterChange = onFilterChange,
                        getPossibleValues = getPossibleFilterValues,
                        allCaps = true,
                    )
                }
            }
        }
    }
}
