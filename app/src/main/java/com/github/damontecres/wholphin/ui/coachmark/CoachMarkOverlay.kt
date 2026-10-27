package com.github.damontecres.wholphin.ui.coachmark

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.BasicDialogPanel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setup.UpdatePromptContent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.util.Version
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

private val ScrimColor = Color(0xD9050608)
/** The outline stays this far inside the screen, so all four sides show even for the menu, which touches the edges */
private val HoleScreenInset = 6.dp
private val HoleCorner = 14.dp
// Wide enough for most bodies to fit on two lines
private val CardWidth = 740.dp
private val CardGap = 28.dp
private val ScreenMarginX = 48.dp
private val ScreenMarginY = 27.dp

// The shorter, screen-wide card for when the full one would cover the target (the row of posters)
private val CompactGap = 22.dp
private val CompactMarginY = 18.dp

/**
 * Draws the [tour] over the signed-in app: the screen dimmed, the current step's target cut out of the dim with a
 * pulsing glow around it, and a short card beside it. Keys are handled by [CoachMarkTour.onKey].
 */
@Composable
fun CoachMarkOverlay(
    tour: CoachMarkTour,
    navigationManager: NavigationManager,
) {
    DisposableEffect(tour) { onDispose { tour.end() } }

    // Started from Settings or by a new account: wait for Home, then for it to focus the starting row, and for the move
    // there to settle. Home not coming up within a minute (still signing in, or gone elsewhere) drops it.
    if (tour.pending) {
        LaunchedEffect(Unit) {
            val home =
                withTimeoutOrNull(60_000 - (SystemClock.uptimeMillis() - tour.pendingSince)) {
                    while (navigationManager.backStack.lastOrNull() !is Destination.Home) delay(100)
                }
            if (home == null) {
                tour.end()
                return@LaunchedEffect
            }
            withTimeoutOrNull(10_000) {
                while (!tour.homeRowReady || tour.bounds(CoachMarkStep.HOME_ROW) == null) delay(100)
            }
            delay(700)
            tour.begin()
        }
    }

    val state = tour.state ?: return
    val step = state.step

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Rect?>(null) }
    // A menu row that's scrolled out of sight is scrolled back first
    LaunchedEffect(step) { tour.rail?.bringIntoView(step) }
    // Follow the target as it moves (a row scrolling into place); leaving Home ends the tour
    LaunchedEffect(step) {
        while (true) {
            withFrameNanos { }
            if (navigationManager.backStack.lastOrNull() !is Destination.Home) {
                tour.end()
                break
            }
            // The opening card follows the next step's target unseen, so the spotlight is ready when it appears
            val follow = if (step.hasTarget) step else state.steps.getOrNull(state.index + 1) ?: step
            tour.bounds(follow)?.let { target = it.translate(-origin).inflate(with(density) { follow.padding.toPx() }) }
        }
    }

    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var holePlaced by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        if (!holePlaced) {
            hole.snapTo(t)
            holePlaced = true
        } else {
            hole.animateTo(t, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    // The opening card has no target; any other step waits for its target's first position
    if (step.hasTarget && !holePlaced) return

    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fadeIn.animateTo(1f, tween(250)) }
    val cardAlpha = remember { Animatable(0f) }
    LaunchedEffect(state.index) {
        cardAlpha.snapTo(0f)
        cardAlpha.animateTo(1f, tween(300, delayMillis = 120))
    }

    val pulse by rememberInfiniteTransition(label = "coach_pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "coach_pulse",
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { alpha = fadeIn.value },
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(ScrimColor)
            if (!step.hasTarget) return@Canvas
            val r = onScreen(hole.value, size.width, size.height, HoleScreenInset.toPx())
            val corner = CornerRadius(HoleCorner.toPx())
            drawRoundRect(Color.Black, r.topLeft, r.size, corner, blendMode = BlendMode.Clear)
            // Soft glow: wide faint rings breathing outwards, then a crisp edge
            val spread = (6 + 10 * pulse).dp.toPx()
            for (ring in 1..3) {
                val inset = spread * ring / 3f
                val g = r.inflate(inset)
                drawRoundRect(
                    color = NeonBoard.Volt.copy(alpha = (.32f - .08f * ring) * (.5f + .5f * pulse)),
                    topLeft = g.topLeft,
                    size = g.size,
                    cornerRadius = CornerRadius(corner.x + inset),
                    style = Stroke(width = spread / 2f),
                )
            }
            drawRoundRect(
                color = NeonBoard.Volt.copy(alpha = .7f + .3f * pulse),
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = corner,
                style = Stroke(width = 3.dp.toPx()),
            )
        }

        val cardModifier = Modifier.graphicsLayer { alpha = cardAlpha.value }
        Layout(
            content = {
                CoachMarkCard(state = state, compact = false, modifier = cardModifier)
                CoachMarkCard(state = state, compact = true, modifier = cardModifier)
                if (step.picture) {
                    // Tagged like the real things, so the spotlight finds it the same way
                    CompositionLocalProvider(LocalCoachMarkTour provides tour) {
                        UpdatePromptPicture(cardModifier.coachMarkTarget(step.target))
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val screenWidth = constraints.maxWidth
            val screenHeight = constraints.maxHeight
            val marginX = ScreenMarginX.roundToPx()
            val full = measurables[0].measure(Constraints(maxWidth = minOf(CardWidth.roundToPx(), screenWidth)))
            val compact = measurables[1].measure(Constraints(maxWidth = max(0, screenWidth - 2 * marginX)))
            val picture = measurables.getOrNull(2)?.measure(Constraints())
            layout(screenWidth, screenHeight) {
                if (picture != null) {
                    // The popup and the card under it, together in the middle of the screen
                    val useFull = picture.height + CardGap.roundToPx() + full.height <= screenHeight - 2 * ScreenMarginY.roundToPx()
                    val card = if (useFull) full else compact
                    val gap = (if (useFull) CardGap else CompactGap).roundToPx()
                    val top = max(0, (screenHeight - picture.height - gap - card.height) / 2)
                    picture.place((screenWidth - picture.width) / 2, top)
                    card.place((screenWidth - card.width) / 2, top + picture.height + gap)
                    return@layout
                }
                if (!step.hasTarget) {
                    full.place((screenWidth - full.width) / 2, (screenHeight - full.height) / 2)
                    return@layout
                }
                // Placed for where the spotlight is going, so the card doesn't hop while it moves
                val spot =
                    placeCard(
                        hole = onScreen(target ?: hole.value, screenWidth.toFloat(), screenHeight.toFloat(), HoleScreenInset.toPx()),
                        full = IntSize(full.width, full.height),
                        compact = IntSize(compact.width, compact.height),
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        gap = CardGap.roundToPx(),
                        compactGap = CompactGap.roundToPx(),
                        marginX = marginX,
                        marginY = ScreenMarginY.roundToPx(),
                        compactMarginY = CompactMarginY.roundToPx(),
                    )
                (if (spot.compact) compact else full).place(spot.offset)
            }
        }
    }
}

/** [hole] cut to the screen less [inset] on every side */
private fun onScreen(
    hole: Rect,
    width: Float,
    height: Float,
    inset: Float,
) = Rect(
    left = hole.left.coerceIn(inset, width - inset),
    top = hole.top.coerceIn(inset, height - inset),
    right = hole.right.coerceIn(inset, width - inset),
    bottom = hole.bottom.coerceIn(inset, height - inset),
)

internal data class CardSpot(
    val offset: IntOffset,
    val compact: Boolean,
)

/**
 * Where the card goes without covering [hole]: the full card beside a narrow target (a menu item), otherwise below or
 * above it; then the shorter [compact] card below or above. Only when neither fits does the compact card go in the
 * bigger space, as clear of the target as it can be.
 */
internal fun placeCard(
    hole: Rect,
    full: IntSize,
    compact: IntSize,
    screenWidth: Int,
    screenHeight: Int,
    gap: Int,
    compactGap: Int,
    marginX: Int,
    marginY: Int,
    compactMarginY: Int,
): CardSpot {
    fun x(v: Float) = v.roundToInt().coerceIn(marginX, max(marginX, screenWidth - marginX - full.width))

    fun y(v: Float) = v.roundToInt().coerceIn(marginY, max(marginY, screenHeight - marginY - full.height))

    fun full(offset: IntOffset) = CardSpot(offset, compact = false)
    val besideY = y(hole.center.y - full.height / 2f)
    if (hole.width < screenWidth * .4f) {
        if (hole.right + gap + full.width <= screenWidth - marginX) return full(IntOffset((hole.right + gap).roundToInt(), besideY))
        if (hole.left - gap - full.width >= marginX) return full(IntOffset((hole.left - gap - full.width).roundToInt(), besideY))
    }
    if (hole.bottom + gap + full.height <= screenHeight - marginY) return full(IntOffset(x(hole.left), (hole.bottom + gap).roundToInt()))
    if (hole.top - gap - full.height >= marginY) return full(IntOffset(x(hole.left), (hole.top - gap - full.height).roundToInt()))

    val compactX = (screenWidth - compact.width) / 2
    val below = (hole.bottom + compactGap).roundToInt()
    val above = (hole.top - compactGap - compact.height).roundToInt()
    val y =
        when {
            below + compact.height <= screenHeight - compactMarginY -> below
            above >= compactMarginY -> above
            screenHeight - hole.bottom >= hole.top -> screenHeight - compactMarginY - compact.height
            else -> compactMarginY
        }
    return CardSpot(IntOffset(compactX, y), compact = true)
}

/**
 * The step's card. [compact] packs the same things into fewer lines: the step count beside the title, and the hint
 * beside the buttons.
 */
@Composable
private fun CoachMarkCard(
    state: CoachMarkState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val step = state.step
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        modifier =
            modifier
                .background(NeonBoard.Card, shape)
                .border(1.dp, NeonBoard.Line2, shape)
                .padding(if (compact) PaddingValues(horizontal = 24.dp, vertical = 16.dp) else PaddingValues(24.dp))
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val count: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.coach_step_count, state.index + 1, state.steps.size).uppercase(),
                style = NeonType.eyebrow(),
                color = NeonBoard.Mid,
            )
        }
        // Volt only for the title and the chosen button
        val title: @Composable (Modifier) -> Unit = {
            Text(
                text = stringResource(step.title),
                style = NeonType.dialogTitle(),
                color = NeonBoard.Volt,
                modifier = it.semantics { heading() },
            )
        }
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                title(Modifier.weight(1f))
                count()
            }
        } else {
            count()
            title(Modifier)
        }
        Text(
            text = stringResource(step.body),
            style = NeonType.rowSubtitle().copy(fontSize = 17.sp, lineHeight = 24.sp),
            color = NeonBoard.Text.copy(alpha = .86f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = if (compact) 4.dp else 8.dp),
        ) {
            CoachMarkPill(
                text =
                    stringResource(
                        when {
                            state.isLast -> R.string.coach_done
                            !step.hasTarget -> R.string.coach_start
                            else -> R.string.coach_next
                        },
                    ),
                selected = !state.skipSelected,
            )
            if (!state.isLast) {
                CoachMarkPill(text = stringResource(R.string.coach_skip), selected = state.skipSelected)
            }
            if (compact) {
                Spacer(Modifier.weight(1f))
                CoachMarkHint(state)
            }
        }
        if (!compact) CoachMarkHint(state)
    }
}

@Composable
private fun CoachMarkHint(state: CoachMarkState) {
    Text(
        text =
            stringResource(
                when {
                    state.isLast -> R.string.coach_hint_last
                    state.index == 0 -> R.string.coach_hint_first
                    else -> R.string.coach_hint
                },
            ),
        style = NeonType.rowSubtitle(),
        color = NeonBoard.Mid,
    )
}

/** The "update available" popup as it looks when WeaselPlex opens, offering the next version; only a picture */
@Composable
private fun UpdatePromptPicture(modifier: Modifier = Modifier) {
    val version =
        remember {
            Version.fromStringLenient(BuildConfig.VERSION_NAME)?.let { it.copy(patch = it.patch + 1, numCommits = null, hash = null) }
                ?: Version(1, 3, 0)
        }
    BasicDialogPanel(modifier = modifier, elevation = 6.dp) {
        UpdatePromptContent(version = version, onUpdate = {}, onNotNow = {}, picture = true)
    }
}

/** A button look; the remote's ◀ ▶ choose it and OK takes it (see [CoachMarkTour.onKey]) */
@Composable
private fun CoachMarkPill(
    text: String,
    selected: Boolean,
) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .then(
                if (selected) {
                    Modifier.background(NeonBoard.Volt, shape)
                } else {
                    Modifier.border(1.dp, NeonBoard.Line2, shape)
                },
            ).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = NeonType.button(),
            color = if (selected) NeonBoard.OnAccent else NeonBoard.Text,
        )
    }
}
