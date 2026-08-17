package com.github.damontecres.wholphin.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext

/**
 * The WeaselTV prismatic rainbow.
 *
 * Stops come verbatim from the approved handoff. The last repeats the first so the
 * animated sweep loops without a visible seam — do not "tidy" the duplicate away.
 */
val PrismaticColors =
    listOf(
        Color(0xFF63C7FF),
        Color(0xFF7E57C2),
        Color(0xFFFF3B55),
        Color(0xFFFFF700),
        Color(0xFFA7FF3B),
        Color(0xFF63C7FF),
    )

/** Cycle lengths, in ms, per the handoff's `durationsMs`. */
object PrismaticDuration {
    const val FOCUS_BORDER = 5_000
    const val BUTTON_FILL = 6_000
    const val TITLE_TEXT = 9_000
    const val PROGRESS_FILL = 5_000
}

/**
 * A single app-wide phase driver.
 *
 * 🛑 Every prismatic surface reads from THIS, rather than each starting its own
 * [rememberInfiniteTransition]. On a screen full of focusable cards that would mean
 * dozens of independent animators all invalidating separately, which is exactly the
 * kind of thing that turns a TV UI janky. It also keeps the sweep phase-aligned across
 * elements, which is what makes the effect read as one surface rather than confetti.
 */
val LocalPrismaticPhase: ProvidableCompositionLocal<PrismaticPhase?> =
    compositionLocalOf { null }

/** True when animations should not run at all — see [PrismaticAnimationHost]. */
val LocalPrismaticEnabled = staticCompositionLocalOf { true }

class PrismaticPhase(
    private val transition: InfiniteTransition?,
) {
    /**
     * Normalised 0..1 offset for a given cycle length. Returns a constant when
     * animation is disabled so callers get a stable, still gradient.
     */
    @Composable
    fun phase(durationMs: Int): State<Float> {
        val t = transition
        return if (t == null) {
            remember { mutableFloatStateOf(0f) }
        } else {
            t.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMs, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "prismaticPhase$durationMs",
            )
        }
    }
}

/**
 * Installs the shared driver. Wrap the app content once.
 *
 * [animate] lets playback switch the whole system off while controls are hidden, so a
 * paused OSD is not repainting a rainbow behind the video.
 */
@Composable
fun PrismaticAnimationHost(
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Honour "Remove animations" / animator duration scale 0. Users who turn animations
    // off system-wide mean it, and on low-end TV boxes it is often off for a reason.
    val animatorScale =
        remember(context) {
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)
        }
    val enabled = animate && animatorScale > 0f
    val transition = if (enabled) rememberInfiniteTransition(label = "prismatic") else null
    val phase = remember(transition) { PrismaticPhase(transition) }
    CompositionLocalProvider(
        LocalPrismaticPhase provides phase,
        LocalPrismaticEnabled provides enabled,
        content = content,
    )
}

/**
 * The brush to paint with.
 *
 * [widthPx] should be roughly the element's width; the gradient is tiled at twice that
 * and shifted one full tile per cycle, which is what produces a continuous sweep rather
 * than a bounce. Slanted ~100° per the handoff.
 */
@Composable
fun rememberPrismaticBrush(
    durationMs: Int = PrismaticDuration.FOCUS_BORDER,
    widthPx: Float = 600f,
): Brush {
    val phase = LocalPrismaticPhase.current
    if (phase == null || !LocalPrismaticEnabled.current) {
        // Static fallback: the same ramp, unmoving. Never a flat colour, so the brand
        // still reads when animation is off.
        return remember(widthPx) {
            Brush.linearGradient(
                colors = PrismaticColors,
                start = Offset(0f, 0f),
                end = Offset(widthPx * 2f, widthPx * 0.7f),
                tileMode = TileMode.Repeated,
            )
        }
    }
    val t by phase.phase(durationMs)
    val shift = t * widthPx * 2f
    return Brush.linearGradient(
        colors = PrismaticColors,
        start = Offset(-widthPx * 2f + shift, 0f),
        end = Offset(shift, widthPx * 0.35f),
        tileMode = TileMode.Repeated,
    )
}
