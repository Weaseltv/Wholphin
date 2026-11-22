package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.alphabetSortName
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.cards.GenreCard
import com.github.damontecres.wholphin.ui.detail.CardGrid
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.GetGenresRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.mayakapps.kache.InMemoryKache
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

/**
 * Show an optimized grid of genres for a library
 */
@Composable
fun GenreCardGrid(
    itemId: UUID,
    includeItemTypes: List<BaseItemKind>?,
    collectionType: CollectionType,
    modifier: Modifier = Modifier,
    initialPosition: Int = 0,
    viewModel: GenreViewModel =
        hiltViewModel<GenreViewModel, GenreViewModel.Factory>(
            creationCallback = { it.create(itemId, includeItemTypes) },
        ),
) {
    val columns = 4
    val spacing = 16.dp
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val cardWidthPx =
        remember(density) {
            with(density) {
                // Grid has 16dp padding on either side & 16dp spacing between 4 cards
                // This isn't exact though because it doesn't account for nav drawer or letters, but it's close and the calculation is much faster
                // E.g. on 1080p, this results in 440px versus 395px actual, so only minimal scaling down is required
                (configuration.screenWidthDp.dp - (2 * 16.dp + 3 * spacing))
                    .div(columns)
                    .roundToPx()
            }
        }
    OneTimeLaunchedEffect {
        viewModel.init(cardWidthPx)
    }
    val state by viewModel.state.collectAsState()

    val gridFocusRequester = remember { FocusRequester() }
    when (val st = state.item) {
        DataLoadingState.Pending,
        DataLoadingState.Loading,
        -> {
            LoadingPage(modifier.focusable())
        }

        is DataLoadingState.Error -> {
            ErrorMessage(st, modifier.focusable())
        }

        is DataLoadingState.Success<BaseItem> -> {
            Box(modifier = modifier) {
                LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }
                val item = st.data
                CardGrid(
                    pager = state.genres,
                    onClickItem = { _, genre ->
                        viewModel.navigationManager.navigateTo(
                            createGenreDestination(
                                genreId = genre.id,
                                genreName = genre.name,
                                parentId = itemId,
                                parentName = item.title,
                                includeItemTypes = includeItemTypes,
                                collectionType = collectionType,
                            ),
                        )
                    },
                    onLongClickItem = { _, _ -> },
                    onClickPlay = { _, _ -> },
                    letterPosition = { viewModel.positionOfLetter(it) },
                    gridFocusRequester = gridFocusRequester,
                    showJumpButtons = false,
                    showLetterButtons = true,
                    modifier = Modifier.fillMaxSize(),
                    initialPosition = initialPosition,
                    positionCallback = { columns, position ->
                    },
                    columns = columns,
                    spacing = spacing,
                    cardContent = { (item, index, onClick, onLongClick, widthPx, mod) ->
                        GenreCard(
                            genre = item,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            modifier = mod,
                        )
                    },
                )
            }
        }
    }
}
