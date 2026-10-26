package com.github.damontecres.wholphin.ui.walkthrough

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.WalkthroughService
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setup.UpdatePrompt
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * The update prompt and the tour offer for the signed-in [current] (guided-walkthrough plan 4.2). The offer waits for
 * every gate (Home on top, no update prompt or pending install, no screensaver or dim, the window focused) and shows
 * about 1.5 s after they all clear. The update prompt goes first; it is held while the offer or the tour page shows.
 */
@Composable
fun WalkthroughOfferHost(
    current: CurrentUser,
    walkthroughService: WalkthroughService,
    updateChecker: UpdateChecker,
    screensaverService: ScreensaverService,
    navigationManager: NavigationManager,
) {
    val ws by walkthroughService.state.collectAsState()
    val release by updateChecker.prompt.collectAsState()
    val resumeInstall by updateChecker.resumeInstall.collectAsState()
    val ss by screensaverService.state.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val latestWindowFocused by rememberUpdatedState(windowFocused)
    val top = navigationManager.backStack.lastOrNull()
    // The generation latched for showing; reset only by an answer
    var latched by rememberSaveable { mutableStateOf<String?>(null) }

    // Derived: no state writes during composition
    val shownOffer = ws.owed?.takeIf { ws.ready && it.generation == latched && it.matches(current) }
    val restoredLatchPending = latched != null && !ws.ready

    val eligible =
        ws.ready && ws.owed?.matches(current) == true && shownOffer == null &&
            top is Destination.Home && release == null && !resumeInstall && !ss.show && !ss.showDim && windowFocused
    LaunchedEffect(eligible, ws.owed?.generation) {
        if (!eligible) return@LaunchedEffect
        val gen = ws.owed?.generation ?: return@LaunchedEffect
        delay(1_500)
        val o = walkthroughService.state.value.owed ?: return@LaunchedEffect
        if (o.generation == gen &&
            navigationManager.backStack.lastOrNull() is Destination.Home &&
            updateChecker.prompt.value == null &&
            !updateChecker.resumeInstall.value &&
            screensaverService.state.value.let { !it.show && !it.showDim } &&
            latestWindowFocused &&
            walkthroughService.canLatch(o, current)
        ) {
            latched = o.generation
        }
    }

    val tourBlocksUpdate = shownOffer != null || restoredLatchPending || top is Destination.Walkthrough
    UpdatePrompt(updateChecker, navigationManager, blocked = tourBlocksUpdate)

    shownOffer?.let { o ->
        val isKid = current.user.profileKind == ProfileKind.KID
        WalkthroughOfferDialog(
            name = current.user.name,
            // Kids have no Settings, so no "Settings → Help" line
            showFootnote = !isKid,
            onStart = {
                Timber.i("Tour offer answered: start")
                // Navigate first, so the update prompt stays held through the switch
                navigationManager.navigateTo(Destination.Walkthrough())
                walkthroughService.clear(o.generation)
                latched = null
            },
            onNotNow = {
                Timber.i("Tour offer answered: not now")
                walkthroughService.clear(o.generation)
                latched = null
            },
            onDontAsk = {
                Timber.i("Tour offer answered: don't ask again")
                walkthroughService.optOut(o.userId)
                walkthroughService.clear(o.generation)
                latched = null
            },
        )
    }
}

/**
 * "WEASELPLEX TOUR / Welcome, Sam!" with Start the tour (focused), Not now and Don't ask again. Back counts as Not now.
 */
@Composable
fun WalkthroughOfferDialog(
    name: String?,
    showFootnote: Boolean,
    onStart: () -> Unit,
    onNotNow: () -> Unit,
    onDontAsk: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BasicDialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 6.dp,
        tallyColor = NeonBoard.Volt,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .width(560.dp)
                    .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.tour_offer_eyebrow).uppercase(),
                style = NeonType.eyebrow(),
                color = NeonBoard.Volt,
            )
            val firstName = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
            Text(
                text =
                    if (firstName != null) {
                        stringResource(R.string.tour_offer_title_named, firstName)
                    } else {
                        stringResource(R.string.tour_offer_title)
                    },
                style = NeonType.dialogTitle(),
                color = NeonBoard.Text,
            )
            TourBody(stringResource(R.string.tour_offer_body))
            OfferButton(R.string.tour_offer_start, onStart, Modifier.focusRequester(focusRequester))
            OfferButton(R.string.not_now, onNotNow)
            OfferButton(R.string.tour_offer_dont_ask, onDontAsk)
            if (showFootnote) {
                Text(
                    text = stringResource(R.string.tour_offer_footnote),
                    style = NeonType.rowSubtitle(),
                    color = NeonBoard.Mid,
                )
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
}

/** Full width and no focus growth, like the update prompt's buttons */
@Composable
private fun OfferButton(
    @StringRes stringRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = Button(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    contentHeight = 32.dp,
) {
    Text(text = stringResource(stringRes))
}
