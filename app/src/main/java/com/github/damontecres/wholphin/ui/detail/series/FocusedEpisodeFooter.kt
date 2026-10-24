package com.github.damontecres.wholphin.ui.detail.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ExpandablePlayButtons
import com.github.damontecres.wholphin.ui.data.ChooseVersionParams
import com.github.damontecres.wholphin.ui.detail.KidAction
import com.github.damontecres.wholphin.ui.detail.KidTitleButtons
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FocusedEpisodeFooter(
    preferences: UserPreferences,
    ep: BaseItem,
    chosenStreams: ChosenStreams?,
    playOnClick: (Duration) -> Unit,
    moreOnClick: () -> Unit,
    watchOnClick: () -> Unit,
    favoriteOnClick: () -> Unit,
    canDelete: Boolean,
    onConfirmDelete: () -> Unit,
    onChooseVersion: (MediaSourceInfo) -> Unit,
    modifier: Modifier = Modifier,
    buttonOnFocusChanged: (FocusState) -> Unit = {},
    /** The kid buttons (owner 2026-10-23): Play / Keep watching, Start over when started, Watchlist */
    kid: Boolean = false,
) {
    val dto = ep.data
    val resumePosition = dto.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO
    val firstFocus = remember { FocusRequester() }
    if (kid) {
        val play = remember(ep) { KidTitlePage.episodePlay(dto) }
        KidTitleButtons(
            play = play,
            actions = KidTitlePage.movieActions(play),
            onWatchlist = dto.userData?.isFavorite ?: false,
            onPlay = { playOnClick(it.positionMs.milliseconds) },
            onAction = { action ->
                when (action) {
                    KidAction.START_OVER -> {
                        playOnClick(Duration.ZERO)
                    }

                    KidAction.WATCHLIST -> {
                        favoriteOnClick()
                    }

                    KidAction.SHUFFLE -> {}
                }
            },
            onFocusChanged = buttonOnFocusChanged,
            firstFocus = firstFocus,
            modifier = modifier,
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ExpandablePlayButtons(
            title = ep.title ?: "",
            resumePosition = resumePosition,
            watched = dto.userData?.played ?: false,
            favorite = dto.userData?.isFavorite ?: false,
            playOnClick = playOnClick,
            moreOnClick = moreOnClick,
            watchOnClick = watchOnClick,
            favoriteOnClick = favoriteOnClick,
            buttonOnFocusChanged = buttonOnFocusChanged,
            trailers = null,
            trailerOnClick = {},
            canDelete = canDelete,
            onConfirmDelete = onConfirmDelete,
            chooseVersionParams =
                remember(chosenStreams, ep, onChooseVersion) {
                    ChooseVersionParams(
                        chosenStreams = chosenStreams,
                        mediaSources = ep.data.mediaSources.orEmpty(),
                        onChooseVersion = onChooseVersion,
                    )
                },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
