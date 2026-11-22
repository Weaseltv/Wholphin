package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.seekBack
import com.github.damontecres.wholphin.ui.seekForward
import com.github.damontecres.wholphin.ui.skipStringRes
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.extensions.ticks
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the player's buttons and dialogs ask the page to do (`PlaybackAction`, with [VideoScale] for the scale) */
sealed interface PlaybackViewAction {
    data object ShowDebug : PlaybackViewAction

    data object SearchCaptions : PlaybackViewAction

    data class ToggleCaptions(
        val index: Int,
    ) : PlaybackViewAction

    data class ToggleAudio(
        val index: Int,
    ) : PlaybackViewAction

    data class PlaybackSpeed(
        val value: Float,
    ) : PlaybackViewAction

    data class Scale(
        val scale: VideoScale,
    ) : PlaybackViewAction

    data object Previous : PlaybackViewAction

    data object Next : PlaybackViewAction
}

/** The overlay's view state (`OverlayViewState`) */
enum class OverlayState {
    HIDDEN,
    CONTROLLER,
    CHAPTERS,
    QUEUE,
}

/** Lays out its only child at a fraction of its own width (`fillMaxWidth(fraction)`), aligned by [gravity] */
internal class FractionWidthLayout(
    context: Context,
    private val fraction: Float,
    private val gravity: Int = Gravity.START,
) : ViewGroup(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val child = getChildAt(0)
        var h = 0
        if (child != null && child.visibility != GONE) {
            val cw = ((w - paddingLeft - paddingRight) * fraction).toInt()
            val lp = child.layoutParams
            val hSpec =
                when (lp.height) {
                    LayoutParams.MATCH_PARENT -> {
                        heightMeasureSpec
                    }

                    LayoutParams.WRAP_CONTENT -> {
                        MeasureSpec.makeMeasureSpec(
                            MeasureSpec.getSize(heightMeasureSpec),
                            MeasureSpec.UNSPECIFIED,
                        )
                    }

                    else -> {
                        MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                    }
                }
            val wSpec =
                if (lp.width == LayoutParams.WRAP_CONTENT) {
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
                }
            child.measure(wSpec, hSpec)
            h = child.measuredHeight
        }
        setMeasuredDimension(w, resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val child = getChildAt(0) ?: return
        val inner = (r - l) - paddingLeft - paddingRight
        val cw = child.measuredWidth
        val x =
            when (Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> paddingLeft + (inner - cw) / 2
                Gravity.RIGHT -> paddingLeft + inner - cw
                else -> paddingLeft
            }
        child.layout(x, paddingTop, x + cw, paddingTop + child.measuredHeight)
    }
}

/**
 * The controller state of the overlay (`PlaybackController` + `Controller` + `PlaybackControls`): title, subtitle and
 * "ends at", the seek bar with its timecodes, the left (settings), centre (transport) and right (skip segment,
 * captions, audio) buttons, and the focusable "Chapters"/"Queue" heading that opens the next row.
 */
@OptIn(UnstableApi::class)
internal class PlaybackControllerView(
    context: Context,
    private val player: Player,
    private val controllerViewState: ControllerViewState,
    private val accent: Int,
    private val seekBackAmount: Duration,
    private val seekForwardAmount: Duration,
    private val skipBackOnResume: Duration?,
    private val onAction: (PlaybackViewAction) -> Unit,
    private val onDialog: (PlaybackDialogType) -> Unit,
    private val onSeekBarChange: (Long) -> Unit,
    private val onChangeState: (OverlayState) -> Unit,
    /** The seek bar gained/lost focus (trickplay preview) */
    private val onSeekBarFocus: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val titleBlock = LinearLayout(context)
    private val title: TextView
    private val subtitle: TextView
    private val endsAt: TextView
    private val subtitleRow = LinearLayout(context)
    private val endsAtBox = FrameLayout(context)
    val seekBar = SeekBarView(context, interactive = true)
    private val timecodes = SeekTimecodesView(context)
    val playPause: SurfaceFrameLayout
    private val previous: SurfaceFrameLayout
    private val next: SurfaceFrameLayout
    private val skipSegment: SurfaceTextView
    private val skipSegmentHolder = FrameLayout(context)
    private val nextHeader: TextView
    private var nextState: OverlayState? = null
    private var segment: MediaSegmentDto? = null
    private var showPlay = true
    private var ticking = false
    private var lastEndsAt = 0L
    private var lastBind: List<Any?>? = null

    private val pulse: () -> Unit = { controllerViewState.pulseControls() }

    private val ticker =
        object : Runnable {
            override fun run() {
                if (!ticking) return
                updateProgress()
                val now = System.currentTimeMillis()
                if (now - lastEndsAt >= 1000L) {
                    lastEndsAt = now
                    updateEndsAt()
                }
                postDelayed(this, 250L)
            }
        }

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                    )
                ) {
                    updatePlayPause()
                }
            }
        }

    init {
        orientation = VERTICAL
        noClip()
        setPadding(0, 0, 0, context.dp(8))

        // Title block: 16dp in, bumps up 32dp while the seek bar is focused
        titleBlock.orientation = VERTICAL
        titleBlock.setPadding(context.dp(16), 0, 0, 0)
        titleBlock.noClip()
        title =
            context.textView(maxLines = 1, ellipsize = TextUtils.TruncateAt.MIDDLE).apply {
                PlaybackText.role(this, TextRole.TITLE_LARGE, sizeSp = TITLE_SP)
            }
        titleBlock.addView(
            FractionWidthLayout(context, .75f).apply { addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        subtitle =
            context.textView(maxLines = 1, ellipsize = TextUtils.TruncateAt.MIDDLE).apply {
                PlaybackText.role(this, TextRole.TITLE_MEDIUM, sizeSp = SUBTITLE_SP)
            }
        endsAt =
            context.textView(TextRole.LABEL_LARGE).apply {
                setPadding(0, 0, context.dp(32), 0)
            }
        // Row(SpaceBetween): the subtitle takes 75 % of the width, "ends at" sits at the end of the rest
        subtitleRow.orientation = HORIZONTAL
        subtitleRow.gravity = Gravity.CENTER_VERTICAL
        subtitleRow.weightSum = 1f
        subtitleRow.addView(subtitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, .75f))
        endsAtBox.addView(
            endsAt,
            FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        subtitleRow.addView(endsAtBox, LayoutParams(0, LayoutParams.WRAP_CONTENT, .25f))
        titleBlock.addView(
            subtitleRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) },
        )
        addView(titleBlock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // PlaybackControls: seek bar (95 % wide) and timecodes, then the button rows
        val controls = LinearLayout(context).apply { orientation = VERTICAL }.also { it.noClip() }
        val seekColumn = LinearLayout(context).apply { orientation = VERTICAL }.also { it.noClip() }
        seekBar.accent = accent
        seekBar.seekBack = seekBackAmount
        seekBar.seekForward = seekForwardAmount
        seekBar.controllerViewState = controllerViewState
        seekBar.onSeek = { onSeekBarChange(it) }
        seekBar.setOnFocusChangeListener { _, f -> onSeekBarFocus(f) }
        seekColumn.addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        seekColumn.addView(timecodes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        controls.addView(
            FractionWidthLayout(context, .95f, Gravity.CENTER_HORIZONTAL).apply {
                addView(seekColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val buttons = FrameLayout(context).apply { setPadding(context.dp(8), 0, context.dp(8), 0) }.also { it.noClip() }
        val spacing = context.dp(BUTTON_SPACING_DP)

        fun glass(
            iconRes: Int,
            onClick: () -> Unit,
        ) = GlassButton.icon(context, iconRes, accent).apply {
            bindFocus(1.1f) { pulse() }
            setOnClickListener {
                pulse()
                onClick()
            }
        }

        // Left: settings
        val left = LinearLayout(context).apply { orientation = HORIZONTAL }.also { it.noClip() }
        left.addView(glass(R.drawable.vector_settings) { onDialog(PlaybackDialogType.SETTINGS) })
        buttons.addView(
            left,
            FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )

        // Centre: previous, rewind, play/pause, fast forward, next (always LTR)
        val center = LinearLayout(context).apply { orientation = HORIZONTAL }.also { it.noClip() }
        center.layoutDirection = LAYOUT_DIRECTION_LTR
        previous = glass(R.drawable.baseline_skip_previous_24) { onAction(PlaybackViewAction.Previous) }
        val rewind = glass(R.drawable.baseline_fast_rewind_24) { player.seekBack(seekBackAmount) }
        playPause =
            glass(R.drawable.baseline_play_arrow_24) {
                if (showPlay) {
                    player.play()
                    skipBackOnResume?.let { player.seekBack(it) }
                } else {
                    player.pause()
                }
            }
        val forward = glass(R.drawable.baseline_fast_forward_24) { player.seekForward(seekForwardAmount) }
        next = glass(R.drawable.baseline_skip_next_24) { onAction(PlaybackViewAction.Next) }
        listOf(previous, rewind, playPause, forward, next).forEachIndexed { i, b ->
            center.addView(b, LayoutParams(b.layoutParams.width, b.layoutParams.height).apply { if (i > 0) marginStart = spacing })
        }
        buttons.addView(center, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Right: skip segment text button, captions, audio
        val right = LinearLayout(context).apply { orientation = HORIZONTAL }.also { it.noClip() }
        right.gravity = Gravity.CENTER_VERTICAL
        skipSegment =
            OutlineButton.text(context, "", accent).apply {
                setOnClickListener {
                    segment?.let { s -> player.seekTo(s.endTicks.ticks.inWholeMilliseconds) }
                }
            }
        skipSegmentHolder.noClip()
        skipSegmentHolder.addView(
            skipSegment,
            FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL),
        )
        skipSegmentHolder.setPadding(0, 0, context.dp(32), 0)
        skipSegmentHolder.visibility = GONE
        right.addView(skipSegmentHolder, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val captions = glass(R.drawable.captions_svgrepo_com) { onDialog(PlaybackDialogType.CAPTIONS) }
        val audio =
            GlassButton.fa(context, context.getString(R.string.fa_volume_high), accent).apply {
                bindFocus(1.1f) { pulse() }
                setOnClickListener {
                    pulse()
                    onDialog(PlaybackDialogType.AUDIO)
                }
            }
        right.addView(captions, LayoutParams(captions.layoutParams.width, captions.layoutParams.height))
        right.addView(audio, LayoutParams(audio.layoutParams.width, audio.layoutParams.height).apply { marginStart = spacing })
        buttons.addView(
            right,
            FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        controls.addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        // "Chapters" / "Queue": focusing it opens that row
        nextHeader =
            context.textView(TextRole.TITLE_LARGE).apply {
                setPadding(context.dp(16), 0, 0, 0)
                isFocusable = true
                setOnFocusChangeListener { v, f -> if (f) nextState?.let { st -> v.post { onChangeState(st) } } }
            }
        addView(nextHeader, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        // Title bump while the seek bar is focused (spring, medium-low stiffness)
        val existing = seekBar.onFocusChangeListener
        seekBar.setOnFocusChangeListener { v, f ->
            existing?.onFocusChange(v, f)
            titleBlock
                .animate()
                .translationY(
                    if (f) -context.dp(32).toFloat() else 0f,
                ).setDuration(350L)
                .setInterpolator(FastOutSlowIn)
                .start()
        }
        updatePlayPause()
    }

    /** Bind the item, the next row and the current segment */
    fun bind(
        item: BaseItem?,
        nextEnabled: Boolean,
        hasChapters: Boolean,
        currentSegment: MediaSegmentDto?,
    ) {
        val key = listOf(item, nextEnabled, hasChapters, currentSegment)
        if (key == lastBind) return
        lastBind = key
        val t = item?.title
        title.text = t
        (title.parent as View).isVisible = t != null
        (subtitleRow.layoutParams as LayoutParams).topMargin = if (t != null) context.dp(8) else 0
        val sub = item?.subtitleLong
        subtitle.text = sub
        subtitle.isVisible = sub != null
        // Row with SpaceBetween: alone, "ends at" sits at the start
        (endsAtBox.layoutParams as LayoutParams).weight = if (sub != null) .25f else 1f
        (endsAt.layoutParams as FrameLayout.LayoutParams).gravity =
            (if (sub != null) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        subtitleRow.requestLayout()
        next.looksDisabled = !nextEnabled
        nextState =
            when {
                hasChapters -> OverlayState.CHAPTERS
                nextEnabled -> OverlayState.QUEUE
                else -> null
            }
        nextHeader.text =
            when (nextState) {
                OverlayState.CHAPTERS -> context.getString(R.string.chapters)
                OverlayState.QUEUE -> context.getString(R.string.queue)
                else -> ""
            }
        nextHeader.isVisible = nextState != null
        segment = currentSegment
        skipSegmentHolder.isVisible = currentSegment != null
        currentSegment?.let { skipSegment.text = context.getString(it.type.skipStringRes) }
    }

    /** Start/stop the 250ms progress ticker */
    fun setActive(active: Boolean) {
        if (active == ticking) return
        ticking = active
        removeCallbacks(ticker)
        if (active) {
            player.addListener(playerListener)
            updatePlayPause()
            lastEndsAt = 0L
            ticker.run()
        } else {
            player.removeListener(playerListener)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        setActive(false)
    }

    private fun updatePlayPause() {
        showPlay = Util.shouldShowPlayButton(player)
        GlassButton.setIcon(playPause, if (showPlay) R.drawable.baseline_play_arrow_24 else R.drawable.baseline_pause_24)
    }

    private fun updateProgress() {
        val duration = player.duration
        seekBar.durationMs = duration
        seekBar.buffered = player.bufferedPosition.toFloat() / duration
        seekBar.progress = player.currentPosition.toFloat() / duration
        timecodes.bind(player.currentPosition, duration)
    }

    private fun updateEndsAt() {
        val remaining =
            (player.duration - player.currentPosition)
                .div(player.playbackParameters.speed)
                .toLong()
                .milliseconds
        val endTime = LocalTime.now().plusSeconds(remaining.inWholeSeconds)
        endsAt.text = context.getString(R.string.ends_at, formatTime(context, endTime))
    }

    /** Heights of the title and subtitle text sizes (`titleTextSize.toDp()`), for the trickplay preview position */
    fun titleTextHeights(): Int {
        var h = 0
        if (title.text.isNotBlank()) h += TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TITLE_SP, resources.displayMetrics).toInt()
        if (!subtitle.text.isNullOrBlank()) {
            h += TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SP, resources.displayMetrics).toInt()
        }
        return h
    }

    companion object {
        const val TITLE_SP = 28f
        const val SUBTITLE_SP = 18f
        const val BUTTON_SPACING_DP = 12
    }
}
