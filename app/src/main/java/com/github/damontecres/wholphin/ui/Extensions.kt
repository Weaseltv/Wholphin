package com.github.damontecres.wholphin.ui

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.data.RowColumnSaver
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import kotlin.math.min

/**
 * Try to call [FocusRequester.requestFocus], but catch & log the exception if something is not configured properly
 */
fun FocusRequester.tryRequestFocus(tag: String? = null): Boolean =
    try {
        val focused = requestFocus()
        tag?.let { Timber.v("Request focus tag=%s", tag) }
        focused
    } catch (ex: IllegalStateException) {
        Timber.w(ex, "Failed to request focus, tag=%s", tag)
        false
    }

/**
 * Used to apply modifiers conditionally.
 */
fun Modifier.ifElse(
    condition: () -> Boolean,
    ifTrueModifier: Modifier,
    ifFalseModifier: Modifier = Modifier,
): Modifier = then(if (condition()) ifTrueModifier else ifFalseModifier)

/**
 * Used to apply modifiers conditionally.
 */
fun Modifier.ifElse(
    condition: Boolean,
    ifTrueModifier: Modifier,
    ifFalseModifier: Modifier = Modifier,
): Modifier = ifElse({ condition }, ifTrueModifier, ifFalseModifier)

fun Modifier.ifElse(
    condition: Boolean,
    ifTrueModifier: () -> Modifier,
    ifFalseModifier: () -> Modifier = { Modifier },
): Modifier = then(if (condition) ifTrueModifier.invoke() else ifFalseModifier.invoke())

/**
 * Handles horizontal (Left & Right) D-Pad Keys and consumes the event(s) so that the focus doesn't
 * accidentally move to another element.
 * */
fun Modifier.handleDPadKeyEvents(
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onCenter: (() -> Unit)? = null,
    triggerOnAction: Int = KeyEvent.ACTION_UP,
) = onPreviewKeyEvent {
    fun onActionUp(block: () -> Unit) {
        if (it.nativeKeyEvent.action == triggerOnAction) block()
    }

    when (it.nativeKeyEvent.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> {
            onLeft?.apply {
                onActionUp(::invoke)
                return@onPreviewKeyEvent true
            }
        }

        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> {
            onRight?.apply {
                onActionUp(::invoke)
                return@onPreviewKeyEvent true
            }
        }

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            onCenter?.apply {
                onActionUp(::invoke)
                return@onPreviewKeyEvent true
            }
        }
    }

    false
}

/**
 * Run a [LaunchedEffect] exactly once even with multiple recompositions.
 *
 * If the composition is removed from the navigation back stack and "re-added", this will run again
 */
@Composable
fun OneTimeLaunchedEffect(runOnceBlock: suspend CoroutineScope.() -> Unit) {
    var hasRun by rememberSaveable { mutableStateOf(false) }
    if (!hasRun) {
        LaunchedEffect(Unit) {
            hasRun = true
            runOnceBlock.invoke(this)
        }
    }
}

/**
 * Calls [tryRequestFocus] on the provided [FocusRequester] when this composable launches or resumes
 */
@Composable
fun RequestOrRestoreFocus(
    focusRequester: FocusRequester?,
    debugKey: String? = null,
) {
    if (focusRequester != null) {
        LaunchedEffect(Unit) {
            debugKey?.let { Timber.v("RequestOrRestoreFocus: %s", it) }
            focusRequester.tryRequestFocus()
        }
//        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
//            debugKey?.let { Timber.v("RequestOrRestoreFocus onResume: %s", it) }
//            focusRequester.tryRequestFocus()
//        }
    }
}

/**
 * Widens a divider or rule by [amount] on each side, so a line inside padded content can
 * still reach the edges of its panel.
 */
fun Modifier.bleedHorizontal(amount: Dp): Modifier = bleedHorizontal(start = amount, end = amount)

/** Extends a rule across asymmetric content gutters without moving the surrounding content. */
fun Modifier.bleedHorizontal(
    start: Dp,
    end: Dp,
): Modifier =
    layout { measurable, constraints ->
        val startPx = start.roundToPx()
        val extra = startPx + end.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.minWidth + extra,
                    maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
                ),
            )
        layout(placeable.width - extra, placeable.height) {
            placeable.placeRelative(-startPx, 0)
        }
    }

fun Modifier.enableMarquee(focused: Boolean) =
    if (focused) {
        basicMarquee(
            initialDelayMillis = 250,
            animationMode = MarqueeAnimationMode.Immediately,
            velocity = 40.dp,
        )
    } else {
        basicMarquee(animationMode = MarqueeAnimationMode.WhileFocused)
    }

@Composable
fun Modifier.playSoundOnFocus(enabled: Boolean): Modifier {
    if (!enabled) {
        return this
    }
    val context = LocalContext.current
    val audioManager =
        remember {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    return onFocusChanged {
        if (it.isFocused) {
            audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP)
        }
    }
}

// Adapted from https://stackoverflow.com/a/69196765
fun Arrangement.spacedByWithFooter(space: Dp) =
    object : Arrangement.Vertical {
        override val spacing = space

        override fun Density.arrange(
            totalSize: Int,
            sizes: IntArray,
            outPositions: IntArray,
        ) {
            if (sizes.isEmpty()) return
            val spacePx = space.roundToPx()

            var occupied = 0
            sizes.forEachIndexed { index, size ->
                if (index == sizes.lastIndex) {
                    outPositions[index] = totalSize - size
                } else {
                    outPositions[index] = min(occupied, totalSize - size)
                }
                val lastSpace = min(spacePx, totalSize - outPositions[index] - size)
                occupied = outPositions[index] + size + lastSpace
            }
        }
    }

/**
 * Convenient way to [rememberSaveable] a [RowColumn]
 */
@Composable
fun rememberPosition(initialPosition: RowColumn = RowColumn(-1, -1)) =
    rememberSaveable(stateSaver = RowColumnSaver) {
        mutableStateOf(
            initialPosition,
        )
    }

/**
 * Convenient way to [rememberSaveable] a [RowColumn]
 */
@Composable
fun rememberPosition(
    row: Int,
    column: Int,
) = rememberPosition(RowColumn(row, column))

/**
 * Convenient way to [rememberSaveable] a Int
 */
@Composable
fun rememberInt(initial: Int = 0) = rememberSaveable { mutableIntStateOf(initial) }

fun Modifier.dimAndBlur(enabled: Boolean) =
    this.ifElse(
        enabled,
        Modifier
            .alpha(.5f)
            .blur(16.dp),
    )
