package com.github.damontecres.wholphin.ui.walkthrough

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.WalkthroughService
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class WalkthroughViewModel
    @Inject
    constructor(
        navDrawerService: NavDrawerService,
        kidSessionGate: KidSessionGate,
        val navigationManager: NavigationManager,
        private val walkthroughService: WalkthroughService,
    ) : ViewModel() {
        val navState = navDrawerService.state
        val isKid = kidSessionGate.isKidSession
        val railFocus = walkthroughService.railFocus

        fun setLiveMenuStepActive(active: Boolean) = walkthroughService.setLiveMenuStepActive(active)

        /** Back out of the tour page(s): one page for BACK, Done and a chapter; all of them for Leave tour */
        fun leave(all: Boolean) {
            val stack = navigationManager.backStack
            if (stack.lastOrNull() !is Destination.Walkthrough) return
            navigationManager.goBack()
            while (all && stack.size > 1 && stack.lastOrNull() is Destination.Walkthrough) {
                navigationManager.goBack()
            }
        }

        fun openChapter(chapter: TourChapter?) {
            navigationManager.navigateTo(Destination.Walkthrough(chapter))
        }
    }

/**
 * The WeaselPlex tour (guided-walkthrough plan 3.3): a normal page beside the real menu. Text on the left, an example
 * on the right, then Previous · Next · Leave tour. Remote BACK leaves at once.
 */
@Composable
fun WalkthroughPage(
    chapter: TourChapter?,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: WalkthroughViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val nav by viewModel.navState.collectAsState()
    val isKid by viewModel.isKid.collectAsState()
    val ctx = remember(nav, isKid, preferences) { TourContext.build(context, nav, isKid, preferences) }

    ProvideNeonAccent(NeonBoard.Volt) {
        if (chapter == TourChapter.PICKER) {
            TourPicker(ctx, viewModel, modifier)
        } else {
            TourCardsPage(chapter, ctx, viewModel, modifier)
        }
    }
}

@Composable
private fun TourCardsPage(
    chapter: TourChapter?,
    ctx: TourContext,
    viewModel: WalkthroughViewModel,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val cards = TourCards.visible(chapter, ctx)
    var cardId by rememberSaveable { mutableStateOf(cards.firstOrNull()?.id) }
    // A card that went away (its feature isn't there any more) falls back to the first one
    val index = cards.indexOfFirst { it.id == cardId }.takeIf { it >= 0 } ?: 0
    val card = cards.getOrNull(index) ?: return
    val isLast = index == cards.lastIndex
    // The sign-in tour (not one opened from Settings → Help) reminds where to find it when left early
    val signInTour = chapter == null && viewModel.navigationManager.backStack.let { it.getOrNull(it.lastIndex - 1) !is Destination.Walkthrough }

    val next = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }

    fun leave(all: Boolean) {
        if (!isLast) {
            Timber.i("Tour left at card %s", card.id)
            if (signInTour && ctx.hasSettings) {
                Toast.makeText(context, R.string.tour_left_toast, Toast.LENGTH_LONG).show()
            }
        }
        viewModel.leave(all)
    }

    LaunchedEffect(card.id) {
        Timber.i("Tour card %s", card.id)
        next.tryRequestFocus()
    }

    // Card 2's live menu step (plan 4.4): armed once Next has focus, done after the menu had focus and the page has it again
    var armed by remember(card.id) { mutableStateOf(false) }
    var sawRail by remember(card.id) { mutableStateOf(false) }
    var menuStepDone by rememberSaveable(card.id) { mutableStateOf(false) }
    val railFocus by viewModel.railFocus.collectAsState()
    if (card.liveMenuStep) {
        DisposableEffect(Unit) {
            viewModel.setLiveMenuStepActive(true)
            onDispose { viewModel.setLiveMenuStepActive(false) }
        }
        LaunchedEffect(railFocus, armed) { if (armed && railFocus != null) sawRail = true }
        LaunchedEffect(sawRail, pageHasFocus) { if (sawRail && pageHasFocus) menuStepDone = true }
    }
    val cardState = TourCardState(railFocus = if (card.liveMenuStep && armed) railFocus else null, menuStepDone = menuStepDone)

    BackHandler(enabled = pageHasFocus) { leave(all = false) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        modifier =
            modifier
                .padding(top = NeonBoard.Size.OverscanY, bottom = NeonBoard.Size.OverscanY, end = NeonBoard.Size.OverscanX)
                .onFocusChanged { pageHasFocus = it.hasFocus }
                .focusGroup()
                .focusProperties { onEnter = { next.tryRequestFocus() } },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .weight(if (card.illustration != null) .55f else 1f)
                    .fillMaxHeight(),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.tour_eyebrow,
                        stringResource(if (chapter == null) card.chapter.label(ctx) else chapter.label(ctx)),
                        index + 1,
                        cards.size,
                    ).uppercase(),
                style = NeonType.eyebrow(),
                color = NeonBoard.Volt,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = card.title(ctx),
                    style = NeonType.dialogTitle(),
                    color = NeonBoard.Text,
                    modifier = Modifier.semantics { heading() },
                )
                card.content(ctx, cardState)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Previous is hidden on the first card and on the live menu step, so Next is leftmost there
                if (index > 0 && !card.liveMenuStep) {
                    TourButton(stringResource(R.string.tour_previous), onClick = { cardId = cards[index - 1].id })
                }
                TourButton(
                    text = stringResource(if (isLast) R.string.tour_done else R.string.tour_next),
                    onClick = {
                        if (isLast) {
                            Timber.i("Tour finished (%s)", chapter ?: "essentials")
                            leave(all = false)
                        } else {
                            cardId = cards[index + 1].id
                        }
                    },
                    modifier =
                        Modifier
                            .focusRequester(next)
                            .onFocusChanged { if (it.isFocused && card.liveMenuStep) armed = true },
                )
                if (!isLast) {
                    TourButton(stringResource(R.string.tour_leave), onClick = { leave(all = true) })
                }
            }
        }
        card.illustration?.let { illustration ->
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(.45f)
                        .fillMaxHeight(),
            ) {
                IllustrationFrame { illustration(ctx) }
            }
        }
    }
}

/** Settings → Help → WeaselPlex tour: the whole tour, or one part of it */
@Composable
private fun TourPicker(
    ctx: TourContext,
    viewModel: WalkthroughViewModel,
    modifier: Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.tryRequestFocus() }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .padding(top = NeonBoard.Size.OverscanY, bottom = NeonBoard.Size.OverscanY, end = NeonBoard.Size.OverscanX)
                .focusGroup()
                .focusProperties { onEnter = { first.tryRequestFocus() } },
    ) {
        Text(
            text = stringResource(R.string.tour_chapter_picker).uppercase(),
            style = NeonType.eyebrow(),
            color = NeonBoard.Volt,
        )
        Text(
            text = stringResource(R.string.tour_picker_title),
            style = NeonType.dialogTitle(),
            color = NeonBoard.Text,
            modifier = Modifier.semantics { heading() },
        )
        TourBody(stringResource(R.string.tour_picker_body))
        TourButton(
            text = stringResource(R.string.tour_picker_take),
            onClick = { viewModel.openChapter(null) },
            modifier = Modifier.focusRequester(first),
        )
        Box(
            Modifier
                .width(480.dp)
                .height(1.dp)
                .background(NeonBoard.Line2),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TourCards.chapters(ctx).forEach { chapter ->
                TourButton(stringResource(chapter.label(ctx)), onClick = { viewModel.openChapter(chapter) })
            }
        }
    }
}

@Composable
private fun TourButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier) {
        Text(text = text)
    }
}
