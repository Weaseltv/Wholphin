package com.github.damontecres.wholphin.ui.discover

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.filter.DiscoverFilter
import com.github.damontecres.wholphin.data.filter.DiscoverFilterBy
import com.github.damontecres.wholphin.data.filter.DiscoverSort
import com.github.damontecres.wholphin.data.filter.DiscoverSortAndDirection
import com.github.damontecres.wholphin.data.filter.FilterValueOption
import com.github.damontecres.wholphin.data.filter.discoverMovieFilters
import com.github.damontecres.wholphin.data.filter.discoverTvFilters
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.services.FilterOptionCache
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.DiscoverMovieRequestHandler
import com.github.damontecres.wholphin.util.DiscoverRequestPager
import com.github.damontecres.wholphin.util.DiscoverRequestType
import com.github.damontecres.wholphin.util.DiscoverTvRequestHandler
import com.github.damontecres.wholphin.util.TrendingRequestHandler
import com.github.damontecres.wholphin.util.UpcomingMovieRequestHandler
import com.github.damontecres.wholphin.util.UpcomingTvRequestHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

@Composable
fun DiscoverRequestGrid(
    destination: Destination.DiscoverMoreResult,
    showTitle: Boolean,
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModelKey: String? = null,
    viewModel: DiscoverRequestViewModel =
        hiltViewModel<DiscoverRequestViewModel, DiscoverRequestViewModel.Factory>(
            key = viewModelKey,
            creationCallback = {
                it.create(
                    destination.type,
                    destination.startIndex,
                    destination.initialFilter,
                )
            },
        ),
) {
    val state by viewModel.state.collectAsState()

    var showHeader by rememberSaveable { mutableStateOf(state.startIndex < 6) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        DiscoverGridHeader(
            showHeader = showHeader,
            showTitle = showTitle,
            title =
                destination.titleOverride?.getString()
                    ?: stringResource(destination.type.stringRes),
            sortAndDirection = state.sortAndDirection,
            onSortChange = viewModel::onSortChange,
            sortOptions = state.sortOptions,
            getPossibleFilterValues = viewModel::getPossibleFilterValues,
            onClickShowViewOptions = {},
            currentFilter = state.filter,
            filterOptions = state.filterOptions,
            onFilterChange = viewModel::onFilterChange,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state.loading) {
            DataLoadingState.Pending,
            DataLoadingState.Loading,
            -> {
                LoadingPage(Modifier)
            }

            is DataLoadingState.Error -> {
                ErrorMessage(s, Modifier)
            }

            is DataLoadingState.Success<List<DiscoverItem?>> -> {
                val gridFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }
                CardGrid(
                    initialPosition = state.startIndex,
                    pager = s.data,
                    onClickItem = { index, item ->
                        viewModel.navigationManager.navigateTo(item.destination)
                    },
                    onLongClickItem = { index, item -> },
                    onClickPlay = { _, _ -> },
                    letterPosition = { 0 },
                    gridFocusRequester = gridFocusRequester,
                    showJumpButtons = false,
                    showLetterButtons = false,
                    cardContent = { (item, index, onClick, onLongClick, widthPx, mod) ->
                        DiscoverItemCard(
                            item = item,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            modifier = mod,
                            width = Dp.Unspecified,
                        )
                    },
                    columns = 6,
                    positionCallback = { columns, index ->
                        showHeader = index < columns
                        positionCallback.invoke(columns, index)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
