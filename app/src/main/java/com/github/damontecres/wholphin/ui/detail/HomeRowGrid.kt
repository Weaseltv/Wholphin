package com.github.damontecres.wholphin.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.cards.GridCard
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.ExpandableFaButton
import com.github.damontecres.wholphin.ui.components.GenreCardGrid
import com.github.damontecres.wholphin.ui.components.GridTitle
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.StudioCardGrid
import com.github.damontecres.wholphin.ui.components.rememberContextMenu
import com.github.damontecres.wholphin.ui.main.homeRowAccent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.scale
import com.github.damontecres.wholphin.ui.theme.ProvideNeonSectionAccent
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import org.jellyfin.sdk.model.api.CollectionType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeRowGrid(
    preferences: UserPreferences,
    destination: Destination.MoreHomeRow,
    modifier: Modifier = Modifier,
    viewModel: HomeRowGridViewModel =
        hiltViewModel<HomeRowGridViewModel, HomeRowGridViewModel.Factory>(
            creationCallback = { it.create(destination.title, destination.config) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val navigation by viewModel.navigationLibraries.collectAsState()
    val contextMenu = rememberContextMenu(preferences, viewModel)
    val gridFocusRequester = remember { FocusRequester() }
    val viewOptions = destination.config.viewOptions

    ProvideNeonSectionAccent(homeRowAccent(destination.config, navigation.allLibraries)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier,
        ) {
            GridTitle(destination.title.getString(), modifier = Modifier.padding(top = 27.dp))

            when (val st = state.loading) {
                is HomeRowLoadingState.Error -> {
                    ErrorMessage(st.message, st.exception, Modifier.fillMaxSize())
                }

                is HomeRowLoadingState.Loading,
                is HomeRowLoadingState.Pending,
                -> {
                    LoadingPage(Modifier.fillMaxSize())
                }

                is HomeRowLoadingState.Success -> {
                    when (destination.config) {
                        is HomeRowConfig.Genres -> {
                            GenreCardGrid(
                                itemId = destination.config.parentId,
                                includeItemTypes = null,
                                modifier = Modifier.fillMaxSize(),
                                initialPosition = destination.initialPosition,
                                collectionType = CollectionType.UNKNOWN,
                            )
                        }

                        is HomeRowConfig.Studios -> {
                            StudioCardGrid(
                                itemId = destination.config.parentId,
                                includeItemTypes = null,
                                modifier = Modifier.fillMaxSize(),
                                initialPosition = destination.initialPosition,
                            )
                        }

                        else -> {
                            val onClickItem =
                                remember {
                                    { index: Int, item: BaseItem ->
                                        viewModel.navigateTo(item.destination(index))
                                    }
                                }
                            LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                            ) {
                                ExpandableFaButton(
                                    title = R.string.sort_by_random,
                                    iconStringRes = R.string.fa_dice,
                                    onClick = viewModel::onClickRandom,
                                    enabled = st.items.isNotEmpty(),
                                )
                            }
                            CardGrid(
                                pager = st.items,
                                onClickItem = onClickItem,
                                onLongClickItem = contextMenu::showContextMenu,
                                onClickPlay = { _, _ -> },
                                letterPosition = { -1 },
                                gridFocusRequester = gridFocusRequester,
                                showJumpButtons = false,
                                showLetterButtons = false,
                                modifier = Modifier.fillMaxSize(),
                                initialPosition = destination.initialPosition,
                                positionCallback = { columns, newPosition ->
                                },
                                cardContent = { (item, index, onClick, onLongClick, widthPx, mod) ->
                                    GridCard(
                                        item = item,
                                        onClick = onClick,
                                        onLongClick = onLongClick,
                                        imageContentScale = viewOptions.contentScale.scale,
                                        imageAspectRatio = viewOptions.aspectRatio.ratio,
                                        imageType = viewOptions.imageType,
                                        showTitle = true, // viewOptions.showTitles,
                                        fillWidth = widthPx,
                                        modifier = mod,
                                    )
                                },
                                columns = if (viewOptions.aspectRatio.ratio > 1f) 4 else 6,
                                spacing = viewOptions.spacing.dp,
                                bringIntoViewSpec = LocalBringIntoViewSpec.current,
                            )
                        }
                    }
                }
            }
        }
        contextMenu.Compose()
    }
}
