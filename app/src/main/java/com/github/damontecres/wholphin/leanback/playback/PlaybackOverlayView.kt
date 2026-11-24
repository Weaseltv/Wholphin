package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.Disposable
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Everything the overlay needs from the page */
internal class OverlayConfig(
    val player: Player,
    val controllerViewState: ControllerViewState,
    val imageUrlService: ImageUrlService,
    val appearance: HomeCardAppearance,
    val seekBack: Duration,
    val seekForward: Duration,
    val skipBackOnResume: Duration?,
    val showClock: Boolean,
    /** `neonAccentFor(item)` of the page */
    val accentFor: (BaseItem?) -> Int,
    val onAction: (PlaybackViewAction) -> Unit,
    val onDialog: (PlaybackDialogType) -> Unit,
    val onSeekBarChange: (Long) -> Unit,
    val onClickPlaylist: (BaseItem) -> Unit,
)

/**
 * The playback overlay (`PlaybackOverlay`), no-Compose: the bottom scrim, the controller / chapters / queue rows that
 * replace each other with a vertical slide, the trickplay preview over the seek bar, the logo (top start), the clock
 * (top end) and the debug overlay.
 */
internal class PlaybackOverlayView(
    context: Context,
    private val config: OverlayConfig,
) : FrameLayout(context) {
    private val player = config.player
    private val cvs = config.controllerViewState
    private val scrim = View(context)
    private val contentHost = FrameLayout(context)
    private val previewHost = FrameLayout(context)
    private val preview = TrickplayPreviewView(context)
    private val logo = ImageView(context)
    private val clock: TextView
    private val debug: PlaybackDebugView

    private var item: BaseItem? = null
    private var accent: Int = config.accentFor(null)
    private var chapters: List<Chapter> = emptyList()
    private var queue: List<PlaylistItem> = emptyList()
    private var nextEnabled = false
    private var segment: MediaSegmentDto? = null
    private var showDebug = false
    private var controlsVisible = false
    private var state = OverlayState.HIDDEN
    private var current: View? = null
    private var controller: PlaybackControllerView? = null
    private var seekBarFocused = false
    private var seekProgressMs = 0L
    private var logoUrl: String? = null
    private var logoRequest: Disposable? = null
    private var clockTicking = false
    private var lastBind: List<Any?>? = null

    private val clockTicker =
        object : Runnable {
            override fun run() {
                if (!clockTicking) return
                clock.text =
                    com.github.damontecres.wholphin.ui
                        .formatTime(context, java.time.LocalDateTime.now())
                postDelayed(this, 2_000L)
            }
        }

    init {
        noClip()
        // Background scrim for OSD readability
        scrim.background =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0x80000000.toInt(), 0xCC000000.toInt()),
            )
        scrim.visibility = GONE
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        contentHost.noClip()
        addView(contentHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Trickplay: 95 % wide, centred, positioned over the title area by [positionPreview]
        previewHost.noClip()
        previewHost.layoutDirection = LAYOUT_DIRECTION_LTR
        previewHost.addView(preview, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        previewHost.visibility = GONE
        addView(previewHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Top start: logo 240x120 with 16dp padding
        logo.scaleType = ImageView.ScaleType.FIT_START
        val pad = context.dp(16)
        logo.setPadding(pad, pad, pad, pad)
        logo.visibility = GONE
        addView(logo, LayoutParams(context.dp(240), context.dp(120), Gravity.TOP or Gravity.START))

        // Top end: the clock (TimeDisplay)
        clock =
            context.textView(TextRole.BODY_LARGE).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
                visibility = GONE
            }
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        debug =
            PlaybackDebugView(context) { hasFocus ->
                // If the debug overlay gains focus, do not hide the controls
                if (hasFocus) cvs.pulseControls(Long.MAX_VALUE) else cvs.pulseControls()
            }
        debug.visibility = GONE
        addView(debug, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    fun setTrickplay(
        info: TrickplayInfo?,
        urlFor: (Int) -> String?,
    ) = preview.setTrickplay(info, urlFor)

    /** Bind the playback state */
    fun bind(
        item: BaseItem?,
        chapters: List<Chapter>,
        queue: List<PlaylistItem>,
        nextEnabled: Boolean,
        currentSegment: MediaSegmentDto?,
        showDebug: Boolean,
        currentPlayback: CurrentPlayback?,
        analyticsState: AnalyticsState,
    ) {
        val key = listOf(item, chapters, queue, nextEnabled, currentSegment, showDebug, currentPlayback, analyticsState)
        if (key == lastBind) return
        lastBind = key
        val itemChanged = item != this.item
        this.item = item
        accent = config.accentFor(item)
        this.chapters = chapters
        this.queue = queue
        this.nextEnabled = nextEnabled
        this.segment = currentSegment
        val debugChanged = showDebug != this.showDebug
        this.showDebug = showDebug
        if (itemChanged) {
            logoUrl = config.imageUrlService.getItemImageUrl(item, ImageType.LOGO)
            logoRequest?.dispose()
            logoRequest = if (logoUrl.isNotNullOrBlank()) logo.load(logoUrl) else null
            // The accent follows the item: rebuild the controller when it is next shown
            if (state != OverlayState.CONTROLLER) controller = null
        }
        controller?.bind(item, nextEnabled, chapters.isNotEmpty(), currentSegment)
        debug.accent = accent
        debug.bind(currentPlayback, analyticsState)
        if (debugChanged) updateChrome()
        if (state == OverlayState.CHAPTERS && chapters.isEmpty()) setState(OverlayState.CONTROLLER)
        if (state == OverlayState.QUEUE &&
            !nextEnabled
        ) {
            setState(if (chapters.isNotEmpty()) OverlayState.CHAPTERS else OverlayState.CONTROLLER)
        }
    }

    /** The controls were shown or hidden (`controllerViewState.controlsVisible`) */
    fun setControlsVisible(visible: Boolean) {
        if (visible == controlsVisible) return
        controlsVisible = visible
        setState(if (visible) OverlayState.CONTROLLER else OverlayState.HIDDEN)
        updateChrome()
    }

    /** Whether focus is inside the visible controls */
    fun hasControlsFocus(): Boolean = current?.hasFocus() == true || debug.hasFocus()

    private fun updateChrome() {
        PlaybackAnim.fade(scrim, controlsVisible && !showDebug)
        val logoShown = !showDebug && logoUrl.isNotNullOrBlank() && controlsVisible
        slideCorner(logo, logoShown, -1f)
        slideCorner(clock, !showDebug && config.showClock && controlsVisible, 1f)
        if (!showDebug && config.showClock && controlsVisible) {
            if (!clockTicking) {
                clockTicking = true
                clockTicker.run()
            }
        } else {
            clockTicking = false
            removeCallbacks(clockTicker)
        }
        val debugShown = showDebug && controlsVisible
        if (debugShown != debug.isVisible) {
            debug.maxHeightPx = (height - (controller?.height ?: 0)).coerceAtLeast(0)
            if (debugShown) {
                debug.alpha = 0f
                debug.translationY = -context.dp(40).toFloat()
                debug.isVisible = true
                debug
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250L)
                    .start()
            } else {
                debug
                    .animate()
                    .alpha(
                        0f,
                    ).translationY(-context.dp(40).toFloat())
                    .setDuration(250L)
                    .withEndAction { debug.isVisible = false }
                    .start()
            }
        }
        updatePreview()
    }

    /** slideIn/slideOut by half the size towards the top corner ([xDir] -1 start, +1 end) with a fade */
    private fun slideCorner(
        view: View,
        show: Boolean,
        xDir: Float,
    ) {
        view.animate().cancel()
        val dx = (if (view.width > 0) view.width else context.dp(120)) / 2f * xDir
        val dy = -(if (view.height > 0) view.height else context.dp(40)) / 2f
        if (show) {
            if (!view.isVisible) {
                view.alpha = 0f
                view.translationX = dx
                view.translationY = dy
                view.isVisible = true
            }
            view
                .animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(250L)
                .setInterpolator(FastOutSlowIn)
                .start()
        } else if (view.isVisible) {
            view
                .animate()
                .alpha(0f)
                .translationX(dx)
                .translationY(dy)
                .setDuration(250L)
                .withEndAction { view.visibility = GONE }
                .start()
        }
    }

    private fun setState(newState: OverlayState) {
        val old = state
        if (newState == old && current != null) return
        state = newState
        val outgoing = current
        val incoming: View? =
            when (newState) {
                OverlayState.HIDDEN -> null
                OverlayState.CONTROLLER -> controllerView()
                OverlayState.CHAPTERS -> if (chapters.isNotEmpty()) chapterRow() else null
                OverlayState.QUEUE -> if (nextEnabled) queueRow() else null
            }
        current = incoming
        // Moving down: the new row comes up from below and the old one leaves upwards; moving up the reverse
        val down = newState.ordinal > old.ordinal
        if (outgoing != null && outgoing !== incoming) {
            (outgoing as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (outgoing === controller) controller?.setActive(false)
            val h = outgoing.height / 2f
            outgoing.animate().cancel()
            outgoing
                .animate()
                .alpha(0f)
                .translationY(if (down) -h else h)
                .setDuration(TRANSITION_MS)
                .setInterpolator(FastOutSlowIn)
                .withEndAction {
                    if (current !== outgoing) {
                        outgoing.visibility = GONE
                        contentHost.removeView(outgoing)
                    }
                }.start()
        }
        if (incoming != null) {
            (incoming as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (incoming.parent == null) {
                contentHost.addView(incoming, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            incoming.animate().cancel()
            incoming.visibility = VISIBLE
            incoming.alpha = 0f
            val start = { h: Float -> incoming.translationY = if (down) h / 2f else -h / 2f }
            if (incoming.height > 0) start(incoming.height.toFloat()) else start(context.dp(120).toFloat())
            incoming
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(TRANSITION_MS)
                .setInterpolator(FastOutSlowIn)
                .start()
            when (incoming) {
                is PlaybackControllerView -> {
                    incoming.bind(item, nextEnabled, chapters.isNotEmpty(), segment)
                    incoming.setActive(true)
                    // PlaybackControls: focus play/pause whenever the controller appears
                    incoming.playPause.requestFocus()
                }

                is ChapterRowView -> {
                    incoming.focusCurrent()
                }

                is QueueRowView -> {
                    incoming.focusFirst()
                }
            }
        }
        updatePreview()
    }

    private fun controllerView(): PlaybackControllerView =
        controller ?: PlaybackControllerView(
            context = context,
            player = player,
            controllerViewState = cvs,
            accent = accent,
            seekBackAmount = config.seekBack,
            seekForwardAmount = config.seekForward,
            skipBackOnResume = config.skipBackOnResume,
            onAction = config.onAction,
            onDialog = config.onDialog,
            onSeekBarChange = {
                config.onSeekBarChange(it)
                seekProgressMs = it
                updatePreview()
            },
            onChangeState = { setState(it) },
            onSeekBarFocus = { focused ->
                seekBarFocused = focused
                // remember(seekBarFocused) { player.currentPosition }
                seekProgressMs = player.currentPosition
                updatePreview()
            },
        ).also { controller = it }

    private fun chapterRow(): ChapterRowView =
        ChapterRowView(
            context = context,
            player = player,
            controllerViewState = cvs,
            chapters = chapters,
            hasNext = nextEnabled,
            appearance = config.appearance,
            accent = accent,
            imageUrlService = config.imageUrlService,
            onChangeState = { setState(it) },
        )

    private fun queueRow(): QueueRowView =
        QueueRowView(
            context = context,
            queue = queue,
            controllerViewState = cvs,
            nextState = if (chapters.isNotEmpty()) OverlayState.CHAPTERS else OverlayState.CONTROLLER,
            appearance = config.appearance,
            accentFor = config.accentFor,
            imageUrlService = config.imageUrlService,
            onChangeState = { setState(it) },
            onClickPlaylist = config.onClickPlaylist,
        )

    private fun updatePreview() {
        val c = controller
        val duration = player.duration
        val percent = (seekProgressMs.toDouble() / duration).toFloat()
        val show = controlsVisible && state == OverlayState.CONTROLLER && seekBarFocused && percent >= 0 && c != null
        if (show) {
            preview.bind(seekProgressMs)
            positionPreview(percent.coerceIn(0f, 1f), c!!)
        }
        if (show != previewHost.isVisible) PlaybackAnim.fade(previewHost, show, 200L)
    }

    /** offsetByPercent inside a 95 % wide box, bottom at the controller height minus the title text heights */
    private fun positionPreview(
        percent: Float,
        c: PlaybackControllerView,
    ) {
        val hostW = if (previewHost.width > 0) previewHost.width else (width - paddingLeft - paddingRight)
        val boxW = hostW * .95f
        val boxLeft = (hostW - boxW) / 2f
        preview.measure(
            MeasureSpec.makeMeasureSpec(boxW.toInt(), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val pw = preview.measuredWidth
        val x = ((boxW * percent).toInt() - pw / 2).coerceIn(0, (boxW.toInt() - pw).coerceAtLeast(0))
        preview.translationX = boxLeft + x
        val lp = preview.layoutParams as LayoutParams
        val bottomOffset = (c.height - c.titleTextHeights()).coerceAtLeast(0)
        if (lp.bottomMargin != bottomOffset) {
            lp.bottomMargin = bottomOffset
            preview.layoutParams = lp
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clockTicking = false
        removeCallbacks(clockTicker)
        controller?.setActive(false)
        logoRequest?.dispose()
    }

    companion object {
        const val TRANSITION_MS = 300L
    }
}

/**
 * The chapters row (`ChapterRowOverlay`): heading, a row of chapter cards starting at the current chapter, and the
 * focusable "Queue" heading under it when there is a queue. UP returns to the controller.
 */
internal class ChapterRowView(
    context: Context,
    private val player: Player,
    private val controllerViewState: ControllerViewState,
    private val chapters: List<Chapter>,
    hasNext: Boolean,
    private val appearance: HomeCardAppearance,
    private val accent: Int,
    private val imageUrlService: ImageUrlService,
    private val onChangeState: (OverlayState) -> Unit,
) : LinearLayout(context) {
    private val grid = HorizontalGridView(context)
    private val chapterIndex: Int

    init {
        orientation = VERTICAL
        noClip()
        setPadding(context.dp(8), 0, context.dp(8), 0)
        val position = player.currentPosition.milliseconds
        chapterIndex =
            chapters
                .indexOfFirst { it.position > position }
                .minus(1)
                .let {
                    if (it < 0) {
                        // Didn't find a chapter, so it's either the first or last
                        if (position < chapters.first().position) 0 else chapters.lastIndex
                    } else {
                        it
                    }
                }.coerceIn(0, chapters.lastIndex)

        val title = context.textView().apply { PlaybackText.sectionTitle(this) }
        title.text = context.getString(R.string.chapters)
        // 8dp below the old zero-height hidden focus box
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        grid.noClip()
        val pad = context.dp(16)
        grid.setPadding(pad, pad, pad, pad)
        grid.setItemSpacing(context.dp(16))
        grid.focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
        grid.setRowHeight(context.dp(ChapterCardView.HEIGHT_DP.toInt()))
        grid.adapter = ChapterAdapter()
        grid.selectedPosition = chapterIndex
        grid.setOnKeyInterceptListener { event ->
            // focusProperties { up = hidden box }: UP goes back to the controller
            if (event.action == KeyEvent.ACTION_DOWN && PlaybackKeys.isUp(event)) {
                onChangeState(OverlayState.CONTROLLER)
                true
            } else {
                false
            }
        }
        grid.onFocusChangeListener = OnFocusChangeListener { _, f -> if (f) controllerViewState.pulseControls() }
        addView(
            grid,
            LayoutParams(LayoutParams.MATCH_PARENT, context.dp(ChapterCardView.HEIGHT_DP.toInt()) + pad * 2).apply {
                topMargin =
                    context.dp(8)
            },
        )

        if (hasNext) {
            val queue =
                context.textView().apply {
                    PlaybackText.sectionTitle(this)
                    text = context.getString(R.string.queue)
                    setPadding(0, 0, 0, context.dp(8))
                    isFocusable = true
                    setOnFocusChangeListener { v, f -> if (f) v.post { onChangeState(OverlayState.QUEUE) } }
                }
            addView(queue, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
        }
    }

    /** Focus the current chapter's card */
    fun focusCurrent() {
        grid.selectedPosition = chapterIndex
        grid.requestFocus()
    }

    private inner class ChapterAdapter : RecyclerView.Adapter<ChapterAdapter.Holder>() {
        inner class Holder(
            val card: ChapterCardView,
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val card = ChapterCardView(parent.context, appearance, accent)
            card.layoutParams =
                RecyclerView.LayoutParams(
                    context.dp(ChapterCardView.WIDTH_DP.toInt()),
                    context.dp(ChapterCardView.HEIGHT_DP.toInt()),
                )
            val prior = card.onFocusChangeListener
            card.setOnFocusChangeListener { v, f ->
                prior?.onFocusChange(v, f)
                if (f) controllerViewState.pulseControls()
            }
            return Holder(card)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val chapter = chapters[position]
            val url =
                imageUrlService.getItemImageUrl(
                    itemId = chapter.itemId,
                    imageType = ImageType.CHAPTER,
                    tag = chapter.tag,
                    imageIndex = chapter.index,
                    fillHeight = context.dp(ChapterCardView.HEIGHT_DP.toInt()),
                )
            holder.card.bind(chapter, url)
            holder.card.setOnClickListener {
                player.seekTo(chapter.position.inWholeMilliseconds)
                controllerViewState.hideControls()
            }
        }

        override fun getItemCount(): Int = chapters.size
    }
}

/**
 * The queue row (`QueueRowOverlay`): heading and the upcoming items as 140dp cards; UP returns to the chapters row
 * (or the controller). Clicking plays that item.
 */
internal class QueueRowView(
    context: Context,
    private val queue: List<PlaylistItem>,
    private val controllerViewState: ControllerViewState,
    private val nextState: OverlayState,
    private val appearance: HomeCardAppearance,
    private val accentFor: (BaseItem?) -> Int,
    private val imageUrlService: ImageUrlService,
    private val onChangeState: (OverlayState) -> Unit,
    private val onClickPlaylist: (BaseItem) -> Unit,
) : LinearLayout(context) {
    private val grid = HorizontalGridView(context)

    init {
        orientation = VERTICAL
        noClip()
        val p8 = context.dp(8)
        setPadding(p8, p8, p8, p8)
        val title = context.textView().apply { PlaybackText.sectionTitle(this) }
        title.text = context.getString(R.string.queue)
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })

        grid.noClip()
        grid.setPadding(context.dp(16), p8, context.dp(16), p8)
        grid.setItemSpacing(context.dp(16))
        // As tall as its cards; with the default row height a wrap_content strip takes whatever height it is offered
        grid.setRowHeight(LayoutParams.WRAP_CONTENT)
        grid.focusScrollStrategy = BaseGridView.FOCUS_SCROLL_ITEM
        grid.adapter = QueueAdapter()
        grid.setOnKeyInterceptListener { event ->
            if (event.action == KeyEvent.ACTION_DOWN && PlaybackKeys.isUp(event)) {
                onChangeState(nextState)
                true
            } else {
                false
            }
        }
        grid.onFocusChangeListener = OnFocusChangeListener { _, f -> if (f) controllerViewState.pulseControls() }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) })
    }

    /** Focus the first upcoming item */
    fun focusFirst() {
        grid.selectedPosition = 0
        grid.requestFocus()
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {
        inner class Holder(
            val card: QueueCardView,
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val card = QueueCardView(parent.context, appearance, IMAGE_HEIGHT_DP)
            card.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            card.onCardFocus = { if (it) controllerViewState.pulseControls() }
            return Holder(card)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val item = queue[position].item
            val url = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = context.dp(IMAGE_HEIGHT_DP))
            holder.card.bind(item, accentFor(item), url)
            holder.card.card.setOnClickListener {
                onClickPlaylist(item)
                controllerViewState.hideControls()
            }
        }

        override fun getItemCount(): Int = queue.size
    }

    private companion object {
        const val IMAGE_HEIGHT_DP = 140
    }
}
