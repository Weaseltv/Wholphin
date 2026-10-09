package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.theme.LocalHomeCardAppearance
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.util.KeepVisibleBringIntoViewSpec
import kotlin.math.ceil

/** Measured first-row budget, shared by item, cast, chapter and discovery rows. */
class DetailViewportState(val height: Dp) {
    var firstRowHeight by mutableStateOf(280.dp)
        private set
    private var firstRow: Any? = null
    private var firstRowPriority = Int.MAX_VALUE
    var actionBarHeight by mutableStateOf(72.dp)

    fun reportRow(key: Any, height: Dp, priority: Int = Int.MAX_VALUE) {
        if (firstRow == null || priority < firstRowPriority) {
            firstRow = key
            firstRowPriority = priority
        }
        if (firstRow === key) firstRowHeight = height
    }
}

val LocalHeaderHeightBudget = staticCompositionLocalOf<Dp?> { null }

val LocalCompactDetailHeader = staticCompositionLocalOf { false }

val LocalDetailViewport = staticCompositionLocalOf<DetailViewportState?> { null }

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DetailPageViewport(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val state = remember(maxHeight) { DetailViewportState(maxHeight) }
        val neon = isWeaselTv()
        val inheritedScrollSpec = LocalBringIntoViewSpec.current
        CompositionLocalProvider(
            LocalDetailViewport provides if (neon) state else null,
            LocalBringIntoViewSpec provides if (neon) KeepVisibleBringIntoViewSpec else inheritedScrollSpec,
        ) {
            content()
        }
    }
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
    return onSizeChanged { viewport.actionBarHeight = with(density) { it.height.toDp() } }
}

/** Reserve a full first row, action bar and bottom gutter before laying out detail copy. */
@Composable
fun FittedDetailHeader(
    reservedHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val viewport = LocalDetailViewport.current
    if (viewport == null) {
        content()
    } else {
        val budget = (viewport.height - viewport.firstRowHeight - (reservedHeight ?: (viewport.actionBarHeight + 32.dp))).coerceAtLeast(1.dp)
        CompositionLocalProvider(LocalCompactDetailHeader provides (budget < 230.dp)) {
            FitHeaderToHeight(budget, content)
        }
    }
}

/**
 * Measure header content naturally, then fit the complete block if the viewport is smaller.
 * Scaling the placement (rather than constraining child height) never cuts through text or
 * images. Cards and action buttons outside the header retain their approved dimensions.
 */
@Composable
fun FitHeaderToHeight(maxHeight: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHeaderHeightBudget provides maxHeight) {
        Layout(content = content) { measurables, constraints ->
            val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
            val naturalHeight = children.maxOfOrNull { it.height } ?: 0
            val limit = maxHeight.roundToPx().coerceAtLeast(1)
            val scale = if (naturalHeight > limit) limit.toFloat() / naturalHeight else 1f
            val width = constraints.constrainWidth(children.maxOfOrNull { it.width } ?: 0)
            val height = constraints.constrainHeight(ceil(naturalHeight * scale).toInt())
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
    val clearance = if (neon) {
        (contentHeight * ((appearance.focusScalePercent - 100).coerceAtLeast(0) / 200f) +
            appearance.glowSpreadDp.dp + appearance.borderWidthDp.dp).coerceAtLeast(8.dp)
    } else 0.dp
    val top = maxOf(contentPadding.calculateTopPadding(), clearance)
    val bottom = maxOf(contentPadding.calculateBottomPadding(), clearance)
    val padding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        end = contentPadding.calculateEndPadding(direction),
        top = top,
        bottom = bottom,
    )
    LazyRow(
        modifier = modifier
            .onSizeChanged {
                // Subtract the same rounded pixel padding used by LazyRow. Subtracting
                // fractional dp from rounded sizes feeds rounding error back into padding.
                contentHeight = with(density) {
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

/** Keep grid captions and focus decoration visible, not just the inner image's bounds. */
@Composable
fun Modifier.keepFocusedItemVisible(): Modifier {
    if (!isWeaselTv()) return this
    val requester = remember { BringIntoViewRequester() }
    val appearance = LocalHomeCardAppearance.current
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val decoration = with(density) { (appearance.glowSpreadDp.dp + appearance.borderWidthDp.dp).toPx() }
    LaunchedEffect(focused, size, decoration, appearance.focusScalePercent) {
        if (focused && size.height > 0) {
            val extra = size.height * (appearance.focusScalePercent - 100).coerceAtLeast(0) / 200f + decoration
            requester.bringIntoView(Rect(0f, -extra, size.width.toFloat(), size.height + extra))
        }
    }
    return bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .onFocusChanged { focused = it.hasFocus }
}
