package com.github.damontecres.wholphin.ui.detail.collection

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.filter.DefaultFilterOptions
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.ItemFilterBy
import com.github.damontecres.wholphin.data.model.GetItemsFilter
import com.github.damontecres.wholphin.services.StreamingQuickList
import com.github.damontecres.wholphin.ui.components.DeleteButton
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.ExpandablePlayButton
import com.github.damontecres.wholphin.ui.components.FilterByButton
import com.github.damontecres.wholphin.ui.components.FocusSafeLazyRow
import com.github.damontecres.wholphin.ui.components.QuickListButton
import com.github.damontecres.wholphin.ui.components.SortByButton
import com.github.damontecres.wholphin.ui.components.StreamingTypeFilters
import com.github.damontecres.wholphin.ui.components.TypeFilterButton
import com.github.damontecres.wholphin.ui.components.reportDetailActionHeight
import com.github.damontecres.wholphin.ui.data.MovieSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.LocalNeonOutlineRest
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.streamingProviderAccent
import kotlin.time.Duration

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
    val sortOptions = MovieSortOptions
    val filterOptions = DefaultFilterOptions
    val firstFocus = remember { FocusRequester() }
    // Streaming collection buttons use the service's colour for their labels, icons, outlines and
    // glow, as ALL / MOVIES / SHOWS use theirs
    val serviceColor =
        if (state.isStreaming) streamingProviderAccent(state.collection?.name) ?: Color.Unspecified else Color.Unspecified
    // Labels and icons stay white in every state, so the service colour reads as the outline and glow
    val buttonTextColor = if (state.isStreaming) NeonBoard.Text else Color.Unspecified
    CompositionLocalProvider(
        LocalNeonAccent provides serviceColor.takeOrElse { LocalNeonAccent.current },
        LocalNeonOutlineRest provides serviceColor.takeIf { it.isSpecified },
    ) {
        if (state.isStreaming) {
            StreamingCollectionButtons(
                state = state,
                onSortChange = onSortChange,
                onClickRandom = onClickRandom,
                onQuickListChange = onQuickListChange,
                onFilterChange = onFilterChange,
                getPossibleFilterValues = getPossibleFilterValues,
                firstFocus = firstFocus,
                modifier = Modifier.reportDetailActionHeight().then(modifier),
            )
            return@CompositionLocalProvider
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.reportDetailActionHeight().then(modifier),
        ) {
            // Streaming collections have more buttons, so they sit closer to fit on one line, with
            // less room between the two groups so a focused button can expand without crowding
            val buttonSpacing = if (state.isStreaming) 8.dp else 16.dp
            val groupGap = if (state.isStreaming) 8.dp else BUTTON_ROW_INSET
            FocusSafeLazyRow(
                horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(start = BUTTON_ROW_INSET, end = groupGap, top = 8.dp, bottom = 8.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        // Line the first button up with the posters below while keeping the inset as
                        // room for its focus glow
                        .outdentStart(BUTTON_ROW_INSET)
                        .focusGroup()
                        .focusRestorer(firstFocus),
            ) {
                if (state.isStreaming) {
                    StreamingTypeFilters.forEachIndexed { index, option ->
                        item(option.title) {
                            TypeFilterButton(
                                option = option,
                                selected = state.itemFilter.includeItemTypes == option.types,
                                onClick = { onFilterChange(state.itemFilter.copy(includeItemTypes = option.types)) },
                                compact = true,
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                    StreamingQuickList.entries.forEach { quickList ->
                        item(quickList.name) {
                            QuickListButton(
                                title = quickList.title,
                                selected = state.quickList == quickList,
                                loading = state.quickListLoading == quickList,
                                onClick = { onQuickListChange(quickList) },
                                compact = true,
                            )
                        }
                    }
                } else {
                    item("random") {
                        ExpandableFaButton(
                            title = R.string.sort_by_random,
                            iconStringRes = R.string.fa_dice,
                            onClick = onClickRandom,
                            modifier = Modifier.focusRequester(firstFocus),
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

                    item {
                        ExpandableFaButton(
                            title = R.string.view_options,
                            iconStringRes = R.string.fa_sliders,
                            onClick = onClickViewOptions,
                            modifier = Modifier,
                        )
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
            }
            FocusSafeLazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (state.isStreaming) 16.dp else buttonSpacing),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(start = groupGap, end = 24.dp, top = 8.dp, bottom = 8.dp),
                modifier =
                    Modifier
                        .focusGroup(),
            ) {
                // Streaming collections keep Random with the other expanding buttons on the right
                if (state.isStreaming) {
                    item("random") {
                        ExpandableFaButton(
                            title = R.string.sort_by_random,
                            iconStringRes = R.string.fa_dice,
                            onClick = onClickRandom,
                            contentColor = buttonTextColor,
                            collapsed = true,
                        )
                    }
                }
                item {
                    SortByButton(
                        sortOptions = sortOptions,
                        current = state.sortAndDirection,
                        onSortChange = onSortChange,
                        collapsed = state.isStreaming,
                        contentColor = buttonTextColor,
                        compact = state.isStreaming,
                        modifier = Modifier,
                    )
                }
                item {
                    FilterByButton(
                        filterOptions = filterOptions,
                        current = state.itemFilter,
                        onFilterChange = onFilterChange,
                        getPossibleValues = getPossibleFilterValues,
                        contentColor = buttonTextColor,
                        compact = state.isStreaming,
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

/**
 * A streaming collection's buttons, laid out like the Watchlist's: ALL / MOVIES / SHOWS on the
 * first row, then sort, filter and the quick lists, with Random on the right, at the Watchlist's
 * sizes and spacing.
 */
@Composable
private fun StreamingCollectionButtons(
    state: CollectionState,
    onSortChange: (SortAndDirection) -> Unit,
    onClickRandom: () -> Unit,
    onQuickListChange: (StreamingQuickList) -> Unit,
    onFilterChange: (GetItemsFilter) -> Unit,
    getPossibleFilterValues: suspend (ItemFilterBy<*>) -> List<FilterValueOption>,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
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
        // Grey outlines at rest like the Watchlist's buttons; the service colour shows on focus
        CompositionLocalProvider(LocalNeonOutlineRest provides null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp, bottom = 8.dp),
            ) {
                StreamingQuickList.entries.forEach { quickList ->
                    QuickListButton(
                        title = quickList.title,
                        selected = state.quickList == quickList,
                        loading = state.quickListLoading == quickList,
                        onClick = { onQuickListChange(quickList) },
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
                )
            }
        }
    }
}

private val BUTTON_ROW_INSET = 24.dp

/** Extend this element [inset] past its start edge, keeping its end edge where it was. */
private fun Modifier.outdentStart(inset: Dp): Modifier =
    layout { measurable, constraints ->
        val px = inset.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.minWidth + px,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + px else constraints.maxWidth,
                ),
            )
        layout(placeable.width - px, placeable.height) {
            placeable.placeRelative(-px, 0)
        }
    }
