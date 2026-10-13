package com.github.damontecres.wholphin.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.theme.LocalHomeCardAppearance
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import com.github.damontecres.wholphin.ui.util.KeepVisibleWithMarginBringIntoViewSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

/** Measured first-row budget, shared by item, cast, chapter and discovery rows. */
class DetailViewportState(
    height: Dp,
    width: Dp = Dp.Unspecified,
) {
    /** The page's current size. It changes as headers above the content animate in and out. */
    var height by mutableStateOf(height)
        internal set
    var width by mutableStateOf(width)
        internal set

    var firstRowHeight by mutableStateOf(280.dp)
        private set
    private var firstRow: Any? = null
    private var firstRowPriority = Int.MAX_VALUE
    var actionBarHeight by mutableStateOf(72.dp)
        private set

    /** Whether any row has reported its height yet. */
    var rowReported by mutableStateOf(false)
        private set

    /** Whether the action bar has reported its height yet. */
    var actionBarReported by mutableStateOf(false)
        private set

    /**
     * The first row and action bar heights the header was first shown with. Once set, the
     * header keeps that size, so rows loading or changing below it never resize it.
     */
    var lockedRowHeight by mutableStateOf<Dp?>(null)
        private set
    var lockedActionBarHeight by mutableStateOf<Dp?>(null)
        private set

    fun reportRow(
        key: Any,
        height: Dp,
        priority: Int = Int.MAX_VALUE,
    ) {
        if (firstRow == null || priority < firstRowPriority) {
            firstRow = key
            firstRowPriority = priority
        }
        if (firstRow === key) firstRowHeight = height
        rowReported = true
    }

    fun reportActionBar(height: Dp) {
        actionBarHeight = height
        actionBarReported = true
    }

    /** Whether the page has been revealed with its header size fixed. */
    val isLocked: Boolean get() = lockedRowHeight != null

    /** Measurements a header needs before the page is revealed, registered by [FittedDetailHeader]. */
    var needsRow by mutableStateOf(false)
        private set
    var needsActionBar by mutableStateOf(false)
        private set

    fun requireMeasurements(
        row: Boolean,
        actionBar: Boolean,
    ) {
        if (row) needsRow = true
        if (actionBar) needsActionBar = true
    }

    val measurementsReady: Boolean
        get() = (!needsRow || rowReported) && (!needsActionBar || actionBarReported)

    fun lock() {
        if (lockedRowHeight == null) {
            lockedRowHeight = firstRowHeight
            lockedActionBarHeight = actionBarHeight
        }
    }
}

val LocalHeaderHeightBudget = staticCompositionLocalOf<Dp?> { null }

val LocalCompactDetailHeader = staticCompositionLocalOf { false }

val LocalDetailViewport = staticCompositionLocalOf<DetailViewportState?> { null }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DetailPageViewport(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        // One state for the life of the page: a header animating above the content changes its
        // size every frame, and a new state would hide the page and fade it in again (a black flash)
        val state = remember { DetailViewportState(maxHeight, maxWidth) }
        state.height = maxHeight
        state.width = maxWidth
        val neon = isWeaselTv()
        val inheritedScrollSpec = LocalBringIntoViewSpec.current
        if (neon) {
            // Keep the page hidden until its header knows the row and action bar sizes, then
            // reveal it with everything in its final place, so nothing shifts as it appears.
            LaunchedEffect(state) {
                // Lazy items (and their headers) compose during the first layout
                withFrameNanos { }
                withTimeoutOrNull(HEADER_MEASURE_TIMEOUT_MS) {
                    snapshotFlow { state.measurementsReady }.first { it }
                }
                // Let the reported sizes settle for a frame before fixing the header's size
                withFrameNanos { }
                state.lock()
            }
        }
        val alpha by animateFloatAsState(if (!neon || state.isLocked) 1f else 0f, tween(100), label = "pageReveal")
        CompositionLocalProvider(
            LocalDetailViewport provides if (neon) state else null,
            LocalBringIntoViewSpec provides if (neon) KeepVisibleBringIntoViewSpec else inheritedScrollSpec,
        ) {
            Box(Modifier.graphicsLayer { this.alpha = alpha }) {
                content()
            }
        }
    }
}

/**
 * Rows scroll far enough to leave [room] beside the focused item, so the item at either end keeps
 * its border and glow instead of stopping flush against the clipping edge. WeaselTV only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeepFocusRoomOnScroll(
    room: Dp,
    content: @Composable () -> Unit,
) {
    if (!isWeaselTv()) {
        content()
        return
    }
    val roomPx = with(LocalDensity.current) { room.toPx() }
    val spec = remember(roomPx) { KeepVisibleWithMarginBringIntoViewSpec(roomPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Report a whole titled row, including captions and the clearance for focus effects. */
@Composable
fun Modifier.reportDetailRowHeight(priority: Int = Int.MAX_VALUE): Modifier {
    val viewport = LocalDetailViewport.current ?: return this
    val key = remember { Any() }
    val density = LocalDensity.current
    return onSizeChanged { viewport.reportRow(key, with(density) { it.height.toDp() }, priority) }
}

/** Include the complete action bar's padding in the detail header budget. */
@Composable
fun Modifier.reportDetailActionHeight(): Modifier {
    val viewport = LocalDetailViewport.current ?: return this
    val density = LocalDensity.current
    return onSizeChanged { viewport.reportActionBar(with(density) { it.height.toDp() }) }
}

/** How long a page waits for its first row and action bar to be measured before showing. */
private const val HEADER_MEASURE_TIMEOUT_MS = 300L

/**
 * Reserve a full first row, action bar and bottom gutter before laying out detail copy.
 *
 * Pass [firstRowHeight] when the first row's height is known up front. Otherwise the page
 * ([DetailPageViewport]) stays hidden until the measured first row and action bar arrive (or a
 * short timeout), then appears with the header at that size and keeps it, so the header never
 * visibly shrinks or grows as rows load.
 */
@Composable
fun FittedDetailHeader(
    reservedHeight: Dp? = null,
    firstRowHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val viewport = LocalDetailViewport.current
    if (viewport == null) {
        content()
    } else {
        val waitForRow = firstRowHeight == null
        val waitForActionBar = reservedHeight == null
        SideEffect { viewport.requireMeasurements(row = waitForRow, actionBar = waitForActionBar) }
        val rowHeight = firstRowHeight ?: viewport.lockedRowHeight ?: viewport.firstRowHeight
        val actionBarHeight = viewport.lockedActionBarHeight ?: viewport.actionBarHeight
        val budget =
            (viewport.height - rowHeight - (reservedHeight ?: (actionBarHeight + 32.dp)))
                .coerceAtLeast(
                    1.dp,
                )
        CompositionLocalProvider(LocalCompactDetailHeader provides (budget < 230.dp)) {
            FitHeaderToHeight(budget, content = content)
        }
    }
}

/**
 * Measure header content naturally, then fit the complete block if the viewport is smaller.
 * Scaling the placement (rather than constraining child height) never cuts through text or
 * images. Cards and action buttons outside the header retain their approved dimensions.
 */
@Composable
fun FitHeaderToHeight(
    maxHeight: Dp,
    fillHeight: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalHeaderHeightBudget provides maxHeight) {
        Layout(content = content, modifier = modifier) { measurables, constraints ->
            val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
            val naturalHeight = children.maxOfOrNull { it.height } ?: 0
            val limit = maxHeight.roundToPx().coerceAtLeast(1)
            val scale = if (naturalHeight > limit) limit.toFloat() / naturalHeight else 1f
            val width = constraints.constrainWidth(children.maxOfOrNull { it.width } ?: 0)
            val height = constraints.constrainHeight(if (fillHeight) limit else ceil(naturalHeight * scale).toInt())
            layout(width, height) {
                children.forEach { child ->
                    child.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    }
}

/** All media rows reserve the actual focus enlargement, outline and glow on both edges. */
@Composable
fun FocusSafeLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val neon = isWeaselTv()
    val appearance = LocalHomeCardAppearance.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var contentHeight by remember { mutableStateOf(170.dp) }
    val clearance =
        if (neon) {
            (
                contentHeight * ((appearance.focusScalePercent - 100).coerceAtLeast(0) / 200f) +
                    appearance.glowSpreadDp.dp + appearance.borderWidthDp.dp
            ).coerceAtLeast(8.dp)
        } else {
            0.dp
        }
    val top = maxOf(contentPadding.calculateTopPadding(), clearance)
    val bottom = maxOf(contentPadding.calculateBottomPadding(), clearance)
    val padding =
        PaddingValues(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = top,
            bottom = bottom,
        )
    LazyRow(
        modifier =
            modifier
                .onSizeChanged {
                    // Subtract the same rounded pixel padding used by LazyRow. Subtracting
                    // fractional dp from rounded sizes feeds rounding error back into padding.
                    contentHeight =
                        with(density) {
                            (it.height - top.roundToPx() - bottom.roundToPx()).coerceAtLeast(0).toDp()
                        }
                },
        state = state,
        contentPadding = padding,
        reverseLayout = reverseLayout,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

/**
 * Expand the automatic focus request to the complete card and caption. There is
 * one request through the scroll chain, rather than a second on-focus coroutine
 * competing with the inner image's request.
 */
@Composable
fun Modifier.keepFocusedItemVisible(): Modifier {
    if (!isWeaselTv()) return this
    val appearance = LocalHomeCardAppearance.current
    val density = LocalDensity.current
    val decoration = with(density) { (appearance.glowSpreadDp.dp + appearance.borderWidthDp.dp).toPx() }
    return then(CompleteFocusBoundsElement(decoration, (appearance.focusScalePercent - 100).coerceAtLeast(0) / 200f))
}

/** A row's heading, cards, captions and focus padding travel as one visibility target. */
@Composable
fun Modifier.keepTitledRowVisible(): Modifier = if (isWeaselTv()) then(CompleteFocusBoundsElement(0f, 0f)) else this

private data class CompleteFocusBoundsElement(
    val clearance: Float,
    val scaleClearance: Float,
) : ModifierNodeElement<CompleteFocusBoundsNode>() {
    override fun create() = CompleteFocusBoundsNode(clearance, scaleClearance)

    override fun update(node: CompleteFocusBoundsNode) {
        node.clearance = clearance
        node.scaleClearance = scaleClearance
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "completeFocusBounds"
    }
}

private class CompleteFocusBoundsNode(
    var clearance: Float,
    var scaleClearance: Float,
) : Modifier.Node(),
    BringIntoViewModifierNode,
    FocusEventModifierNode,
    LayoutAwareModifierNode {
    private var hasFocus = false
    private var previousSize: IntSize? = null
    private var resizeRequest: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
        if (!hasFocus) resizeRequest?.cancel()
    }

    override fun onRemeasured(size: IntSize) {
        val changed = previousSize != null && previousSize != size
        previousSize = size
        if (hasFocus && changed) {
            // Paging can add a caption after the original focus scroll ended.
            // Recheck only a resized focused component, after its new placement.
            // Ordinary left/right focus changes still use one automatic request.
            resizeRequest?.cancel()
            resizeRequest =
                coroutineScope.launch {
                    withFrameNanos { }
                    if (isAttached && hasFocus) bringIntoView { completeBounds() }
                }
        }
    }

    private fun completeBounds(): Rect {
        val size = requireLayoutCoordinates().size
        val extra = clearance + size.height * scaleClearance
        return Rect(0f, -extra, size.width.toFloat(), size.height + extra)
    }

    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) {
        // Propagate the original automatic request, but with this complete
        // component's measured bounds. Re-evaluate during scrolling/remeasurement.
        bringIntoView {
            if (!isAttached || !childCoordinates.isAttached || boundsProvider() == null) {
                null
            } else {
                completeBounds()
            }
        }
    }
}
