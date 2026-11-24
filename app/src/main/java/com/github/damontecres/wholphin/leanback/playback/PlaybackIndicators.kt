package com.github.damontecres.wholphin.leanback.playback

import android.animation.ValueAnimator
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.common.Player
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.math.abs

/** Compose's default easing (FastOutSlowIn) for tweens */
internal val FastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)

/**
 * The animated "pause" icon shown once each time the player pauses (`PauseIndicator`): 64dp, scales in over 300ms,
 * fades out 50ms after. Only active while nothing else covers the video; activating re-reads the player like the old
 * composable entering composition.
 */
internal class PauseIndicatorView(
    context: Context,
) : ImageView(context) {
    private var player: Player? = null
    private var isPaused = false
    private var active = false

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    )
                ) {
                    evaluate()
                }
            }
        }

    init {
        setImageResource(R.drawable.baseline_pause_24)
        imageTintList =
            android.content.res.ColorStateList
                .valueOf(AppTheme.palette.onSurface)
        visibility = GONE
    }

    fun setPlayer(newPlayer: Player?) {
        player?.removeListener(listener)
        player = newPlayer
        newPlayer?.addListener(listener)
    }

    /** Whether the indicator may show (controls, dialogs and skip indicators hidden) */
    fun setActive(value: Boolean) {
        if (value == active) return
        active = value
        if (value) {
            isPaused = false
            evaluate()
        } else {
            animate().cancel()
            visibility = GONE
        }
    }

    private fun evaluate() {
        if (!active) return
        val p = player ?: return
        val was = isPaused
        // Not already paused (don't trigger more than once), actually paused, and could play if it was not paused
        isPaused = !isPaused &&
            !p.playWhenReady &&
            p.playbackState.let { it == Player.STATE_READY || it == Player.STATE_BUFFERING }
        if (isPaused && !was) show()
    }

    private fun show() {
        animate().cancel()
        alpha = 1f
        scaleX = 0f
        scaleY = 0f
        visibility = VISIBLE
        animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(FastOutSlowIn)
            .withEndAction {
                postDelayed({
                    animate()
                        .alpha(0f)
                        .setDuration(300L)
                        .withEndAction { visibility = GONE }
                        .start()
                }, 50L)
            }.start()
    }
}

/**
 * The rotating arrow with the seconds skipped (`SkipIndicator`): 55dp, turns 270° over 800ms per skip (mirrored when
 * going back) and reports [onFinish] when the turn ends.
 */
internal class SkipIndicatorView(
    context: Context,
) : FrameLayout(context) {
    private val arrow = ImageView(context)
    private val label: TextView
    private var rotationNow = 0f
    private var animator: ValueAnimator? = null
    var onFinish: (() -> Unit)? = null

    init {
        arrow.setImageResource(R.drawable.circular_arrow_right)
        addView(arrow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        label =
            context.textView(TextRole.BODY_SMALL, color = AppTheme.palette.onBackground).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
            }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = GONE
    }

    /** Show (or update) the indicator for a total skip of [durationMs] */
    fun show(durationMs: Long) {
        val backward = durationMs < 0
        label.text = abs(durationMs / 1000).toString()
        arrow.scaleX = if (backward) -1f else 1f
        visibility = VISIBLE
        animator?.removeAllListeners()
        animator?.cancel()
        val from = rotationNow
        val to = rotationNow + if (backward) -270f else 270f
        animator =
            ValueAnimator.ofFloat(from, to).apply {
                duration = 800L
                interpolator = FastOutSlowIn
                addUpdateListener {
                    rotationNow = it.animatedValue as Float
                    // Rotation applies outside the mirroring, as `rotate(..).scale(-1, 1)` did
                    arrow.rotation = rotationNow
                }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!cancelled) onFinish?.invoke()
                        }
                    },
                )
                start()
            }
    }

    fun hide() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        visibility = GONE
    }
}

/**
 * The compact overlay shown while D-pad seeking in trickplay mode (`DpadSeekOverlay`): trickplay preview riding
 * above a read-only seek bar, with the timecodes.
 */
internal class DpadSeekOverlayView(
    context: Context,
) : LinearLayout(context) {
    private val previewRow = FrameLayout(context)
    val preview = TrickplayPreviewView(context)
    val seekBar = SeekBarView(context, interactive = false)
    val timecodes = SeekTimecodesView(context)
    private var percent = 0f

    init {
        orientation = VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        noClip()
        previewRow.noClip()
        previewRow.addView(
            preview,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = context.dp(8)
            },
        )
        addView(previewRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(timecodes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    fun setTrickplay(
        info: TrickplayInfo?,
        urlFor: (Int) -> String?,
    ) = preview.setTrickplay(info, urlFor)

    fun bind(
        player: Player,
        seekPositionMs: Long,
        accent: Int,
    ) {
        val durationMs = player.duration.coerceAtLeast(0L)
        percent = if (durationMs > 0) (seekPositionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
        val buffered = if (durationMs > 0) (player.bufferedPosition.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
        seekBar.accent = accent
        seekBar.progress = percent
        seekBar.buffered = buffered
        timecodes.bind(seekPositionMs, durationMs)
        preview.bind(seekPositionMs)
        requestLayout()
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        // offsetByPercent: centre the preview on the position, kept inside the row
        val max = previewRow.width
        val pw = preview.width
        val x = ((max * percent).toInt() - pw / 2).coerceIn(0, (max - pw).coerceAtLeast(0))
        preview.translationX = x.toFloat()
    }

    private var shown = false

    /** Show with fade + slide up (half its height) or hide with fade + slide down */
    fun setShown(show: Boolean) {
        if (show == shown) return
        shown = show
        animate().cancel()
        if (show) {
            if (!isVisible) {
                alpha = 0f
                visibility = VISIBLE
                translationY = if (height > 0) height / 2f else context.dp(60).toFloat()
            }
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(FastOutSlowIn)
                .withEndAction(null)
                .start()
        } else {
            animate()
                .alpha(0f)
                .translationY(height.toFloat())
                .setDuration(220L)
                .setInterpolator(LinearInterpolator())
                .withEndAction { visibility = GONE }
                .start()
        }
    }
}

/** A plain view helper: hide a view without leaving it focusable */
internal fun View.setShownNow(show: Boolean) {
    animate().cancel()
    alpha = 1f
    visibility = if (show) View.VISIBLE else View.GONE
}
