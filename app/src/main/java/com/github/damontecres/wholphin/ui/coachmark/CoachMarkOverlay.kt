package com.github.damontecres.wholphin.ui.coachmark

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
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

    // Started from Settings: wait for Home and its rows to be on screen, and for the move there to settle
    if (tour.pending) {
        LaunchedEffect(Unit) {
            withTimeoutOrNull(5_000) {
                while (navigationManager.backStack.lastOrNull() !is Destination.Home ||
                    tour.bounds(CoachMarkStep.HOME_ROW) == null
                ) {
                    delay(100)
                }
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

        Layout(
            content = {
                CoachMarkCard(
                    state = state,
                    modifier = Modifier.graphicsLayer { alpha = cardAlpha.value },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val card = measurables.first().measure(Constraints(maxWidth = minOf(CardWidth.roundToPx(), constraints.maxWidth)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (!step.hasTarget) {
                    card.place((constraints.maxWidth - card.width) / 2, (constraints.maxHeight - card.height) / 2)
                    return@layout
                }
                card.place(
                    placeCard(
                        hole = onScreen(hole.value, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), HoleScreenInset.toPx()),
                        width = card.width,
                        height = card.height,
                        screenWidth = constraints.maxWidth,
                        screenHeight = constraints.maxHeight,
                        gap = CardGap.roundToPx(),
                        marginX = ScreenMarginX.roundToPx(),
                        marginY = ScreenMarginY.roundToPx(),
                    ),
                )
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

/**
 * Beside a narrow target (a menu item), otherwise below it or above it, whichever fits; always on screen
 */
internal fun placeCard(
    hole: Rect,
    width: Int,
    height: Int,
    screenWidth: Int,
    screenHeight: Int,
    gap: Int,
    marginX: Int,
    marginY: Int,
): IntOffset {
    fun x(v: Float) = v.roundToInt().coerceIn(marginX, max(marginX, screenWidth - marginX - width))

    fun y(v: Float) = v.roundToInt().coerceIn(marginY, max(marginY, screenHeight - marginY - height))
    val besideY = y(hole.center.y - height / 2f)
    if (hole.width < screenWidth * .4f) {
        if (hole.right + gap + width <= screenWidth - marginX) return IntOffset((hole.right + gap).roundToInt(), besideY)
        if (hole.left - gap - width >= marginX) return IntOffset((hole.left - gap - width).roundToInt(), besideY)
    }
    if (hole.bottom + gap + height <= screenHeight - marginY) return IntOffset(x(hole.left), (hole.bottom + gap).roundToInt())
    if (hole.top - gap - height >= marginY) return IntOffset(x(hole.left), (hole.top - gap - height).roundToInt())
    return IntOffset(x((screenWidth - marginX - width).toFloat()), y((screenHeight - marginY - height).toFloat()))
}

@Composable
private fun CoachMarkCard(
    state: CoachMarkState,
    modifier: Modifier = Modifier,
) {
    val step = state.step
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .background(NeonBoard.Card, shape)
                .border(1.dp, NeonBoard.Line2, shape)
                .padding(24.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(R.string.coach_step_count, state.index + 1, state.steps.size).uppercase(),
            style = NeonType.eyebrow(),
            color = NeonBoard.Mid,
        )
        // Volt only for the title and the chosen button
        Text(
            text = stringResource(step.title),
            style = NeonType.dialogTitle(),
            color = NeonBoard.Volt,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(step.body),
            style = NeonType.rowSubtitle().copy(fontSize = 17.sp, lineHeight = 24.sp),
            color = NeonBoard.Text.copy(alpha = .86f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
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
        }
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
