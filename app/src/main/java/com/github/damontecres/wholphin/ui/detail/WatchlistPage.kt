package com.github.damontecres.wholphin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.components.GridTitle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.github.damontecres.wholphin.data.filter.DefaultForFavoritesFilterOptions
import com.github.damontecres.wholphin.data.model.CollectionFolderFilter
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.CollectionFolderViewContent
import com.github.damontecres.wholphin.ui.components.GridClickActions
import com.github.damontecres.wholphin.ui.components.TypeFilterButton
import com.github.damontecres.wholphin.ui.components.ViewOptionsPoster
import com.github.damontecres.wholphin.ui.components.WatchlistTypeFilters
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.data.MovieSortOptions

/**
 * The WeaselPlex Watchlist: ALL / MOVIES / SHOWS buttons where the per-type tabs were,
 * above the usual sort, filter and grid.
 */
@Composable
fun WatchlistPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel(),
) {
    LifecycleStartEffect(Unit) {
        viewModel.refresh()
        onStopOrDispose { }
    }
    val state by viewModel.state.collectAsState()
    val libraries by viewModel.libraries.collectAsState()
    var showFilters by rememberSaveable { mutableStateOf(true) }
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val actions =
        remember {
            GridClickActions(
                onClickItem = { _, item -> viewModel.navigateTo(item.destination()) },
                onLongClickItem = contextMenu::showContextMenu,
            )
        }
    val filterFocusRequesters = remember { WatchlistTypeFilters.map { FocusRequester() } }
    val selectedIndex =
        WatchlistTypeFilters
            .indexOfFirst { it.types == state.filter.includeItemTypes }
            .coerceAtLeast(0)
    // Same clearance the tab row it replaces left for the clock
    val endPadding = if (preferences.appPreferences.interfacePreferences.showClock) 184.dp else 0.dp

    Column(modifier = modifier) {
        AnimatedVisibility(
            showFilters,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                if (isWeaselTv()) {
                    GridTitle(
                        title = stringResource(R.string.watchlist),
                        collectionsOptions = ApprovedHomeLayout.apply(HomeRowViewOptions()),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            // Room for the focus glow inside the animation's clip
                            .padding(start = if (isWeaselTv()) 0.dp else 16.dp, top = 16.dp, bottom = 16.dp, end = endPadding)
                            .focusGroup()
                            .focusRestorer(filterFocusRequesters[selectedIndex]),
                ) {
                    WatchlistTypeFilters.forEachIndexed { index, option ->
                        TypeFilterButton(
                            option = option,
                            selected = index == selectedIndex,
                            onClick = {
                                viewModel.onFilterChange(
                                    state.filter.copy(includeItemTypes = option.types),
                                    recursive = true,
                                )
                            },
                            modifier = Modifier.focusRequester(filterFocusRequesters[index]),
                        )
                    }
                }
            }
        }
        CollectionFolderViewContent(
            preferences = preferences,
            state = state,
            savedPosition = 0,
            itemId = WatchlistViewModel.DISPLAY_ID,
            initialFilter = CollectionFolderFilter(),
            recursive = true,
            actions = actions,
            sortOptions = MovieSortOptions,
            playEnabled = false,
            defaultViewOptions = ViewOptionsPoster,
            viewActions = viewModel,
            provider = viewModel,
            showTitle = false,
            positionCallback = { columns, index -> showFilters = index < columns },
            focusRequesterOnEmpty = filterFocusRequesters[selectedIndex],
            filterOptions = DefaultForFavoritesFilterOptions,
            watchlistLibraries = libraries,
        )
    }
    contextMenu.Compose()
}
