package com.github.damontecres.wholphin.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonLoadingArtwork
import com.github.damontecres.wholphin.ui.theme.rememberLoadingArtworks
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Sidebar layouts use the real nav drawer; the other two also constrain the loading content. */
enum class LoadingArtworkPreviewLayout(
    @param:StringRes val title: Int,
) {
    FULL_SCREEN(R.string.loading_preview_full_screen),
    SIDEBAR(R.string.loading_preview_sidebar),
    BELOW_DETAILS(R.string.loading_preview_below_details),
    NARROW_PANEL(R.string.loading_preview_narrow_panel),
}

@HiltViewModel
class LoadingArtworkPreviewViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        val screensaverService: ScreensaverService,
    ) : ViewModel()

@Composable
fun LoadingArtworkPreviewPage(
    destination: Destination.LoadingArtworkPreview,
    modifier: Modifier = Modifier,
    viewModel: LoadingArtworkPreviewViewModel = hiltViewModel(),
) {
    DisposableEffect(viewModel) {
        viewModel.screensaverService.keepScreenOn(true)
        onDispose { viewModel.screensaverService.keepScreenOn(false) }
    }
    LoadingArtworkPreviewContent(
        destination = destination,
        onLayoutChange = { layout, index ->
            viewModel.navigationManager.replaceTop(Destination.LoadingArtworkPreview(layout, index))
        },
        modifier = modifier,
    )
}

/** No timer, network requests, or rotation writes: keep an artwork visible until the owner moves. */
@Composable
internal fun LoadingArtworkPreviewContent(
    destination: Destination.LoadingArtworkPreview,
    onLayoutChange: (LoadingArtworkPreviewLayout, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artworks = rememberLoadingArtworks()
    val titles = stringArrayResource(R.array.loading_artwork_titles)
    var index by rememberSaveable { mutableIntStateOf(destination.artworkIndex.mod(artworks.size.coerceAtLeast(1))) }
    var showControls by rememberSaveable { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown && artworks.isNotEmpty()) {
                                index = (index + if (event.key == Key.DirectionRight) 1 else -1).mod(artworks.size)
                            }
                            true
                        }

                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val layouts = LoadingArtworkPreviewLayout.entries
                                val next = (destination.layout.ordinal + if (event.key == Key.DirectionDown) 1 else -1).mod(layouts.size)
                                onLayoutChange(layouts[next], index)
                            }
                            true
                        }

                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyDown) showControls = !showControls
                            true
                        }

                        else -> {
                            false
                        } // Back returns to Settings through the normal navigation stack.
                    }
                }.focusRequester(focusRequester)
                .focusable(),
    ) {
        val artwork = artworks.getOrNull(index)
        if (artwork != null) {
            val painter = painterResource(artwork)
            when (destination.layout) {
                LoadingArtworkPreviewLayout.FULL_SCREEN,
                LoadingArtworkPreviewLayout.SIDEBAR,
                -> {
                    NeonLoadingArtwork(painter)
                }

                LoadingArtworkPreviewLayout.BELOW_DETAILS -> {
                    Column(Modifier.fillMaxSize()) {
                        PreviewDetails(
                            title = stringResource(R.string.loading_preview_below_details),
                            modifier = Modifier.fillMaxWidth().weight(.4f),
                        )
                        NeonLoadingArtwork(painter, Modifier.fillMaxWidth().weight(.6f))
                    }
                }

                LoadingArtworkPreviewLayout.NARROW_PANEL -> {
                    Row(Modifier.fillMaxSize()) {
                        PreviewDetails(
                            title = stringResource(R.string.loading_preview_narrow_panel),
                            modifier = Modifier.weight(.6f),
                        )
                        NeonLoadingArtwork(painter, Modifier.weight(.4f))
                    }
                }
            }
        }
        if (showControls) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = .85f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.loading_preview_artwork_number,
                            index + 1,
                            artworks.size,
                            titles.getOrElse(index) { "" },
                        ),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.loading_preview_layout, stringResource(destination.layout.title)),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.loading_preview_controls),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PreviewDetails(
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(24.dp),
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.loading_preview_details_description),
            color = Color.LightGray,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
