package com.github.damontecres.wholphin.leanback.playback

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.mpv.MpvPlayer
import com.github.damontecres.wholphin.preferences.AssPlaybackMode
import com.github.damontecres.wholphin.preferences.DpadSeekMode
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.SubtitlePreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.skipBackOnResume
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.PlaybackDialogType
import com.github.damontecres.wholphin.ui.playback.PlaybackState
import com.github.damontecres.wholphin.ui.playback.PlaybackViewModel
import com.github.damontecres.wholphin.ui.playback.PlayerInstance
import com.github.damontecres.wholphin.ui.playback.SubtitleSearchState
import com.github.damontecres.wholphin.ui.playback.SubtitleSearchStatus
import com.github.damontecres.wholphin.ui.playback.cancelSubtitleSearch
import com.github.damontecres.wholphin.ui.playback.downloadAndSwitchSubtitles
import com.github.damontecres.wholphin.ui.playback.playbackSpeedOptions
import com.github.damontecres.wholphin.ui.playback.searchForSubtitles
import com.github.damontecres.wholphin.ui.seasonEpisode
import com.github.damontecres.wholphin.ui.skipStringRes
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.Media3SubtitleOverride
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Lays out its only child at a fraction of its size, top-centred (`fillMaxSize(fraction).align(TopCenter)`):
 * the player box shrinking for next up, and the subtitles shrinking above the controls.
 */
internal class FractionBox(
    context: Context,
) : ViewGroup(context) {
    var fraction: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec((w * fraction).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * fraction).toInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val w = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = (w - c.measuredWidth) / 2
            c.layout(left, 0, left + c.measuredWidth, c.measuredHeight)
        }
    }
}

/**
 * The player page (no-Compose rewrite of `PlaybackPage`, 2026-11-22): video surface for every backend, subtitles
 * (Media3 SubtitleView with the subtitle style settings, libass through AssSubtitleView), the overlay, D-pad seek and
 * skip indicators, pause indicator, skip-segment button, next up, the player dialogs and subtitle search.
 *
 * Keys work as `PlaybackKeyHandler` did, through [onPreviewKeyEvent] while focus is in the player box; BACK through
 * [onBackPressed] (hide the controls, cancel the next up countdown, dismiss a segment prompt).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackFragment : DestinationFragment() {
    @Inject
    lateinit var imageUrlService: ImageUrlService

    @Inject
    lateinit var serverReportService: ServerReportService

    private val viewModel: PlaybackViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<PlaybackViewModel.Factory> { it.create(destination) }
        },
    )

    private lateinit var root: FrameLayout
    private var statusView: View? = null
    private var content: Content? = null
    private var released = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                setBackgroundColor(Color.BLACK)
                clipChildren = false
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        released = false
        viewModel.state.collectWhileStarted { render(it) }
        viewModel.currentPlayer.collectWhileStarted { render(viewModel.state.value) }
        viewModel.subtitleSearchState.collectWhileStarted { content?.onSubtitleSearch(it) }
        viewModel.controllerViewState.visible.collectWhileStarted { content?.onControlsVisible(it) }
        viewModel.currentUserDto.collectWhileStarted { user ->
            content?.hasSubtitleDownloadPermission = user?.policy?.let { it.isAdministrator || it.enableSubtitleManagement } == true
        }
        // LaunchedEffect(density): size MPV subtitles for this screen
        val metrics = resources.displayMetrics
        viewModel.updateDensity(metrics.density, resources.configuration.fontScale)
    }

    private fun render(state: PlaybackState) {
        when (val loading = state.loading) {
            is LoadingState.Error -> {
                content?.destroy()
                content = null
                showStatus(
                    PlaybackErrorView(requireContext(), loading.message, loading.exception, pageStyle.accentFor(null), ::sendLogs),
                )
            }

            LoadingState.Pending, LoadingState.Loading -> {
                if (statusView !is PlaybackLoadingView) showStatus(PlaybackLoadingView(requireContext()))
            }

            LoadingState.Success -> {
                val instance = viewModel.currentPlayer.value ?: return
                val c = content
                if (c == null || c.instance !== instance) {
                    c?.destroy()
                    removeStatus()
                    val focusHere = view?.hasFocus() == true || c == null
                    content = Content(instance).also { it.attach() }
                    if (focusHere) content?.focusDefault()
                }
                content?.bind(state)
            }
        }
    }

    private fun showStatus(view: View) {
        removeStatus()
        statusView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        view.requestFocus()
    }

    private fun removeStatus() {
        statusView?.let { root.removeView(it) }
        statusView = null
    }

    private fun sendLogs() {
        viewLifecycleOwner.lifecycleScope.launchIO { serverReportService.sendAppLogs() }
    }

    override fun onScreenFocus(restoring: Boolean) {
        val c = content
        if (c == null) {
            statusView?.requestFocus() ?: super.onScreenFocus(restoring)
            return
        }
        c.focusDefault()
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean = content?.onKeyEvent(event) ?: false

    override fun onBackPressed(): Boolean = content?.onBackPressed() ?: false

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // The old page released the player when it left the screen (LifecycleStartEffect onStopOrDispose)
        if (hidden) release()
    }

    override fun onStop() {
        super.onStop()
        release()
    }

    override fun onDestroyView() {
        content?.destroy()
        content = null
        statusView = null
        super.onDestroyView()
    }

    private fun release() {
        if (released) return
        released = true
        content?.dismissDialogs()
        viewModel.release()
    }

    override fun onStart() {
        super.onStart()
        released = false
    }

    /** Everything on screen for one player instance */
    private inner class Content(
        val instance: PlayerInstance,
    ) {
        private val ctx: Context = requireContext()
        private val player: Player = instance.player
        private val prefs: UserPreferences = preferences
        private val playback = prefs.appPreferences.playbackPreferences
        private val cvs: ControllerViewState = viewModel.controllerViewState
        private val dpadSeekMode = playback.dpadSeekMode
        private val seekForwardAmount = playback.skipForwardMs.milliseconds
        private val seekBackAmount = playback.skipBackMs.milliseconds

        private val playerFrame = FractionBox(ctx)
        val playerBox = FrameLayout(ctx)
        private val surface = VideoSurfaceLayout(ctx)
        private val cover = FrameLayout(ctx)
        private val buffering = FrameLayout(ctx)
        private val dpadSeek = DpadSeekOverlayView(ctx)
        private val skipIndicator = SkipIndicatorView(ctx)
        private val minimalBar = View(ctx)
        private val minimalBarHolder = FrameLayout(ctx)
        private val pause = PauseIndicatorView(ctx)
        private val overlay: PlaybackOverlayView
        private val subtitleFrame = FractionBox(ctx)
        private val subtitleView = SubtitleView(ctx)
        private var assView: AssSubtitleView? = null
        private val segmentHolder = FrameLayout(ctx)
        private var segmentButton: SurfaceTextView? = null
        private var nextUpView: NextUpView? = null
        private val keys: PlaybackKeyController

        private var state: PlaybackState = PlaybackState()
        private var accent: Int = pageStyle.accentFor(null)
        private var showDebugInfo = playback.showDebugInfo
        private var videoScale: VideoScale =
            if (instance.backend ==
                PlayerBackend.MPV
            ) {
                VideoScale.FILL
            } else {
                VideoScale.from(playback.globalContentScale)
            }
        private var playbackSpeed = 1.0f
        private var controlsVisible = false
        private var skipIndicatorDuration = 0L
        private var skipPosition = 0L
        private var coverSurface = true
        var hasSubtitleDownloadPermission = false

        // Dialogs
        private var playbackDialogType: PlaybackDialogType? = null
        private var dialog: Dialog? = null
        private var delayDialog: SubtitleDelayDialog? = null
        private var searchDialog: Dialog? = null
        private var searchView: DownloadSubtitlesView? = null
        private var searchStatus: SubtitleSearchStatus = SubtitleSearchStatus.Inactive
        private var searchWasPlaying = false

        // Subtitles
        private var subtitleSettings: SubtitlePreferences? = null
        private var cueCount = 0
        private var subtitleFraction = 1f
        private var subtitleAnimator: android.animation.ValueAnimator? = null

        // Next up
        private var nextUpItem: BaseItem? = null
        private var autoPlayEnabled = false
        private var timeLeft = 0L
        private var countdownJob: Job? = null
        private var playerFraction = 1f
        private var playerAnimator: android.animation.ValueAnimator? = null

        // Segment prompt
        private var segmentShownId: java.util.UUID? = null
        private var segmentJob: Job? = null

        private val seekDebounce = Runnable { pendingSeek?.let { player.seekTo(it) } }
        private var pendingSeek: Long? = null
        private val dpadSeekClear = Runnable { setSkip(0L) }
        private val bufferingShow =
            Runnable {
                // The loading picture is made when first needed (each one advances the loading artwork rotation)
                if (buffering.childCount == 0) {
                    buffering.addView(
                        PlaybackLoadingView(ctx, focusEnabled = false),
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                    )
                }
                PlaybackAnim.fade(buffering, true, 150L)
            }

        private val playerListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateBuffering()
                }
            }

        init {
            // The player box: focus holder for the keys, the surface and everything over the video
            playerBox.isFocusable = true
            playerBox.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) playerBox.defaultFocusHighlightEnabled = false
            playerBox.clipChildren = false
            playerBox.addView(
                surface,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            surface.scale = videoScale

            cover.setBackgroundColor(Color.BLACK)
            cover.addView(
                PlaybackLoadingView(ctx, focusEnabled = false),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            playerBox.addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            buffering.setBackgroundColor(PlaybackColors.BLACK_25)
            buffering.visibility = View.GONE
            playerBox.addView(
                buffering,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )

            // D-pad seek overlay: bottom centre, 16dp up, 95 % wide
            playerBox.addView(
                FractionWidthLayout(ctx, .95f, Gravity.CENTER_HORIZONTAL).apply {
                    addView(dpadSeek, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                },
                FrameLayout
                    .LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM,
                    ).apply {
                        bottomMargin = ctx.dp(16)
                    },
            )
            dpadSeek.setTrickplay(null) { viewModel.getTrickplayUrl(it) }

            // Skip indicator: 55dp, 70dp above the bottom; the minimal 3dp progress line along the bottom
            playerBox.addView(
                skipIndicator,
                FrameLayout.LayoutParams(ctx.dp(55), ctx.dp(55), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin =
                        ctx.dp(70)
                },
            )
            skipIndicator.onFinish = { setSkip(0L) }
            minimalBarHolder.layoutDirection = View.LAYOUT_DIRECTION_LTR
            minimalBar.setBackgroundColor(AppTheme.palette.border)
            minimalBarHolder.addView(minimalBar, FrameLayout.LayoutParams(0, ctx.dp(3), Gravity.BOTTOM or Gravity.START))
            minimalBarHolder.visibility = View.GONE
            playerBox.addView(minimalBarHolder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ctx.dp(3), Gravity.BOTTOM))

            playerBox.addView(pause, FrameLayout.LayoutParams(ctx.dp(64), ctx.dp(64), Gravity.CENTER))
            pause.setPlayer(player)

            // The playback controls
            overlay =
                PlaybackOverlayView(
                    ctx,
                    OverlayConfig(
                        player = player,
                        controllerViewState = cvs,
                        imageUrlService = imageUrlService,
                        appearance = pageStyle.homeCardAppearance,
                        seekBack = seekBackAmount,
                        seekForward = seekForwardAmount,
                        skipBackOnResume = playback.skipBackOnResume,
                        showClock = prefs.appPreferences.interfacePreferences.showClock,
                        accentFor = { pageStyle.accentFor(it) },
                        onAction = ::onPlaybackAction,
                        onDialog = ::openDialog,
                        onSeekBarChange = ::onSeekBarChange,
                        onClickPlaylist = { viewModel.playItemInPlaylist(it) },
                    ),
                )
            ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            playerBox.addView(
                overlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )

            // Subtitles
            subtitleView.setBackgroundColor(Color.TRANSPARENT)
            instance.assHandler?.let { assHandler ->
                if (playback.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS) {
                    Timber.v("Adding AssSubtitleView")
                    val v = AssSubtitleView(ctx, assHandler)
                    subtitleView.addView(
                        v,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ),
                    )
                    assView = v
                }
            }
            subtitleFrame.addView(subtitleView)
            playerBox.addView(
                subtitleFrame,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            surface.onSurfaceSizeChanged = { w, h -> resizeAss(w, h) }
            surface.onCoverChanged = {
                coverSurface = it
                cover.isVisible = it
                updateBuffering()
                updateSubtitleAlpha()
            }

            playerFrame.addView(playerBox)

            // Ask to skip intros, etc: bottom end, 40dp in
            segmentHolder.setPadding(ctx.dp(40), ctx.dp(40), ctx.dp(40), ctx.dp(40))
            segmentHolder.clipChildren = false
            segmentHolder.clipToPadding = false

            keys =
                PlaybackKeyController(
                    player = player,
                    skipWithLeftRight = true,
                    seekBack = seekBackAmount,
                    seekForward = seekForwardAmount,
                    getDurationMs = { player.duration.coerceAtLeast(0L) },
                    controllerViewState = cvs,
                    updateSkipIndicator = ::updateSkipIndicator,
                    clearSkipIndicator = { setSkip(0L) },
                    skipBackOnResume = playback.skipBackOnResume,
                    oneClickPause = playback.oneClickPause,
                    onInteraction = viewModel::reportInteraction,
                    onStop = {
                        player.stop()
                        viewModel.navigationManager.goBack()
                    },
                    onPlaybackDialogTypeClick = ::openDialog,
                    isDpadSeekVisible = { dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY && skipIndicatorDuration != 0L },
                    onDpadSeek = ::onDpadSeek,
                    dpadSeekMode = dpadSeekMode,
                )
        }

        fun attach() {
            root.setBackgroundColor(Color.BLACK)
            root.addView(
                playerFrame,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            root.addView(
                segmentHolder,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ),
            )
            surface.setPlayer(player)
            player.addListener(playerListener)
            // LaunchedEffect(playbackSpeed)
            player.setPlaybackSpeed(playbackSpeed)
            controlsVisible = cvs.controlsVisible
            overlay.setControlsVisible(controlsVisible)
            refresh()
        }

        fun destroy() {
            dismissDialogs()
            searchDialog?.dismiss()
            searchDialog = null
            countdownJob?.cancel()
            segmentJob?.cancel()
            root.removeCallbacks(seekDebounce)
            root.removeCallbacks(dpadSeekClear)
            root.removeCallbacks(bufferingShow)
            player.removeListener(playerListener)
            pause.setPlayer(null)
            surface.release()
            subtitleView.setCues(null)
            root.removeView(playerFrame)
            root.removeView(segmentHolder)
            nextUpView?.let { root.removeView(it) }
        }

        fun dismissDialogs() {
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            dialog = null
            delayDialog?.dialog?.setOnCancelListener(null)
            delayDialog?.dialog?.dismiss()
            delayDialog = null
            playbackDialogType = null
        }

        /** Where focus goes when the page shows */
        fun focusDefault() {
            when {
                nextUpView?.isVisible == true -> {
                    nextUpView?.card?.requestFocus()
                }

                segmentButton != null -> {
                    segmentButton?.requestFocus()
                }

                controlsVisible && overlay.hasControlsFocus() -> {}

                else -> {
                    playerBox.requestFocus()
                }
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // State

        fun bind(newState: PlaybackState) {
            val old = state
            state = newState
            accent = pageStyle.accentFor(newState.currentPlayback?.item)
            if (old.currentMediaInfo.trickPlayInfo != newState.currentMediaInfo.trickPlayInfo || old === newState) {
                overlay.setTrickplay(newState.currentMediaInfo.trickPlayInfo) { viewModel.getTrickplayUrl(it) }
                dpadSeek.setTrickplay(newState.currentMediaInfo.trickPlayInfo) { viewModel.getTrickplayUrl(it) }
            }
            overlay.bind(
                item = newState.currentPlayback?.item,
                chapters = newState.currentMediaInfo.chapters,
                queue = newState.upcomingItems,
                nextEnabled = newState.hasNext,
                currentSegment = newState.currentSegment?.segment,
                showDebug = showDebugInfo,
                currentPlayback = newState.currentPlayback,
                analyticsState = newState.analyticsState,
            )
            // LaunchedEffect(subtitleDelay)
            if (old.currentPlayback?.subtitleDelay != newState.currentPlayback?.subtitleDelay || old === newState) {
                (player as? MpvPlayer)?.subtitleDelay = newState.currentPlayback?.subtitleDelay ?: Duration.ZERO
            }
            delayDialog?.setDelay(newState.currentPlayback?.subtitleDelay ?: Duration.ZERO)
            bindSubtitles(old, newState)
            bindNextUp(newState)
            refresh()
        }

        private fun bindSubtitles(
            old: PlaybackState,
            newState: PlaybackState,
        ) {
            if (subtitleSettings == null || old.currentMediaInfo != newState.currentMediaInfo) {
                val iface = prefs.appPreferences.interfacePreferences
                val hdr = newState.currentMediaInfo.videoStream?.hdr == true
                Timber.v("subtitle choice: %s", newState.currentMediaInfo.videoStream?.hdr)
                val settings =
                    if (iface.subtitlesPreferences.useSeparateHdr && hdr) iface.hdrSubtitlesPreferences else iface.subtitlesPreferences
                if (settings != subtitleSettings) {
                    // First time, or the media switched between SDR and HDR: (re)apply the subtitle style
                    PlaybackSubtitleStyle.applyTo(settings, subtitleView)
                    subtitleSettings = settings
                }
            }
            if (old.subtitleCues !== newState.subtitleCues || old === newState) {
                subtitleView.setCues(newState.subtitleCues)
                if (newState.subtitleCues.size > cueCount) {
                    // The output creates a painter for each cue, so apply the changes when the number of cues increases
                    Media3SubtitleOverride(PlaybackSubtitleStyle.edgeSizePx(subtitleSettings!!, resources.displayMetrics.density))
                        .apply(subtitleView)
                    cueCount = newState.subtitleCues.size
                }
            }
            updateSubtitleAlpha()
        }

        private fun resizeAss(
            w: Int,
            h: Int,
        ) {
            val v = assView ?: return
            val lp = v.layoutParams
            if (w > 0 && h > 0 && (lp.width != w || lp.height != h)) {
                Timber.v("Resizing AssSubtitleView: %sx%s", w, h)
                // Called from the surface's layout pass: apply on the next frame
                v.post { v.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER) }
            }
        }

        private fun isImageSubtitles(cues: List<Cue>): Boolean = cues.firstOrNull()?.bitmap != null

        private fun updateSubtitleAlpha() {
            val visible =
                skipIndicatorDuration == 0L &&
                    state.currentPlayback?.subtitleIndexEnabled == true &&
                    !coverSurface
            subtitleView.alpha =
                when {
                    !visible -> 0f
                    isImageSubtitles(state.subtitleCues) -> (subtitleSettings?.imageSubtitleOpacity ?: 100) / 100f
                    else -> 1f
                }
        }

        /** Recompute what depends on the controls, the skip indicator, dialogs and segments */
        private fun refresh() {
            val trickplay = dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY
            val skipping = !controlsVisible && skipIndicatorDuration != 0L
            // D-pad seek overlay
            dpadSeek.setShown(skipping && trickplay)
            if (skipping && trickplay) dpadSeek.bind(player, skipPosition, accent)
            // Skip indicator and the minimal progress line
            if (skipping && !trickplay) {
                if (dpadSeekMode == DpadSeekMode.SEEKBAR_MINIMAL) {
                    minimalBarHolder.isVisible = true
                    val percent = skipPosition.toFloat() / player.duration.toFloat()
                    minimalBarHolder.post {
                        val lp = minimalBar.layoutParams
                        lp.width = (minimalBarHolder.width * percent.coerceIn(0f, 1f)).toInt()
                        minimalBar.layoutParams = lp
                    }
                } else {
                    minimalBarHolder.isVisible = false
                }
            } else {
                skipIndicator.hide()
                minimalBarHolder.isVisible = false
            }
            // Pause indicator only over the bare video
            val coveredByControls =
                controlsVisible || playbackDialogType != null || searchStatus != SubtitleSearchStatus.Inactive
            pause.setActive(!coveredByControls && skipIndicatorDuration == 0L)
            updateSubtitleAlpha()
            updateSegment()
            root.setBackgroundColor(if (state.nextUp == null) Color.BLACK else AppTheme.palette.background)
        }

        private fun updateBuffering() {
            root.removeCallbacks(bufferingShow)
            if (!coverSurface && player.playbackState == Player.STATE_BUFFERING) {
                // Delay before showing the loading indicator, so if buffering is quick the UI won't flash
                root.postDelayed(bufferingShow, 250L)
            } else {
                PlaybackAnim.fade(buffering, false, 150L)
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Controls

        fun onControlsVisible(visible: Boolean) {
            if (visible == controlsVisible) return
            controlsVisible = visible
            // If the controller shows/hides, immediately cancel the skip indicator
            skipIndicatorDuration = 0L
            if (!visible && overlay.hasControlsFocus()) playerBox.requestFocus()
            overlay.setControlsVisible(visible)
            animateSubtitles(if (visible) .7f else 1f)
            refresh()
        }

        private fun animateSubtitles(target: Float) {
            subtitleAnimator?.cancel()
            subtitleAnimator =
                PlaybackAnim.value(subtitleFraction, target, 250L) {
                    subtitleFraction = it
                    subtitleFrame.fraction = it
                }
        }

        private fun onSeekBarChange(positionMs: Long) {
            // SeekBarState: debounce 750ms, then seek
            pendingSeek = positionMs
            root.removeCallbacks(seekDebounce)
            root.postDelayed(seekDebounce, 750L)
        }

        private fun setSkip(value: Long) {
            if (skipIndicatorDuration == value) return
            skipIndicatorDuration = value
            refresh()
        }

        private fun updateSkipIndicator(delta: Long) {
            if ((skipIndicatorDuration > 0 && delta < 0) || (skipIndicatorDuration < 0 && delta > 0)) {
                skipIndicatorDuration = 0
            }
            skipIndicatorDuration += delta
            skipPosition = player.currentPosition
            afterSkipChange()
        }

        private fun onDpadSeek(deltaMs: Long) {
            if (skipIndicatorDuration == 0L) skipPosition = player.currentPosition
            if ((skipIndicatorDuration > 0 && deltaMs < 0) || (skipIndicatorDuration < 0 && deltaMs > 0)) {
                skipIndicatorDuration = 0L
            }
            skipIndicatorDuration += deltaMs
            skipPosition = (skipPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
            onSeekBarChange(skipPosition)
            afterSkipChange()
        }

        private fun afterSkipChange() {
            refresh()
            if (controlsVisible || skipIndicatorDuration == 0L) return
            if (dpadSeekMode == DpadSeekMode.SEEKBAR_TRICKPLAY) {
                // Clear the overlay 1.5s after the last change
                root.removeCallbacks(dpadSeekClear)
                root.postDelayed(dpadSeekClear, 1500L)
            } else {
                skipIndicator.show(skipIndicatorDuration)
            }
        }

        private fun onPlaybackAction(action: PlaybackViewAction) {
            when (action) {
                is PlaybackViewAction.PlaybackSpeed -> {
                    playbackSpeed = action.value
                    player.setPlaybackSpeed(playbackSpeed)
                }

                is PlaybackViewAction.Scale -> {
                    videoScale = action.scale
                    surface.scale = videoScale
                }

                PlaybackViewAction.ShowDebug -> {
                    showDebugInfo = !showDebugInfo
                    bind(state)
                }

                is PlaybackViewAction.ToggleAudio -> {
                    viewModel.changeAudioStream(action.index)
                }

                is PlaybackViewAction.ToggleCaptions -> {
                    viewModel.changeSubtitleStream(action.index)
                }

                PlaybackViewAction.SearchCaptions -> {
                    cvs.hideControls()
                    viewModel.searchForSubtitles()
                }

                PlaybackViewAction.Next -> {
                    viewModel.playNextUp()
                }

                PlaybackViewAction.Previous -> {
                    val pos = player.currentPosition
                    if (pos < player.maxSeekToPreviousPosition && state.hasPrevious) {
                        viewModel.playPrevious()
                    } else {
                        player.seekToPrevious()
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Keys

        fun onKeyEvent(event: KeyEvent): Boolean {
            // The old handler sat on the player box: it saw keys only while focus was in it (not on next up or the
            // skip segment button), after the focused control
            if (!playerBox.hasFocus()) return false
            return keys.onKeyEvent(event)
        }

        fun onBackPressed(): Boolean {
            if (controlsVisible && playerBox.hasFocus()) {
                cvs.hideControls()
                return true
            }
            if (state.nextUp != null) {
                if (timeLeft > 0 && autoPlayEnabled) {
                    // Cancel the countdown; the card stays with a play icon
                    timeLeft = -1
                    autoPlayEnabled = false
                    countdownJob?.cancel()
                    nextUpView?.setTimeLeft(null)
                } else if (player.isPlaying) {
                    viewLifecycleOwner.lifecycleScope.launch(ExceptionHandler()) { viewModel.cancelUpNextEpisode() }
                } else {
                    viewModel.navigationManager.goBack()
                }
                return true
            }
            if (showSegment()) {
                viewModel.updateSegment(state.currentSegment?.segment?.id, true)
                return true
            }
            return false
        }

        // ---------------------------------------------------------------------------------------------------------
        // Skip segment prompt

        private fun showSegment(): Boolean =
            state.currentSegment?.interacted == false &&
                state.nextUp == null &&
                !controlsVisible &&
                skipIndicatorDuration == 0L

        private fun updateSegment() {
            val segment = state.currentSegment
            if (showSegment() && segment != null) {
                if (segmentShownId != segment.segment.id || segmentButton == null) {
                    segmentShownId = segment.segment.id
                    segmentHolder.removeAllViews()
                    val button =
                        OutlineButton.text(ctx, getString(segment.segment.type.skipStringRes), accent).apply {
                            setOnClickListener { viewModel.updateSegment(segment.segment.id, false) }
                        }
                    segmentButton = button
                    segmentHolder.addView(
                        button,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                    )
                    button.alpha = 0f
                    button
                        .animate()
                        .alpha(1f)
                        .setDuration(220L)
                        .start()
                    button.requestFocus()
                    // Dismiss the prompt after 10 seconds
                    segmentJob?.cancel()
                    segmentJob =
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(10.seconds)
                            viewModel.updateSegment(segment.segment.id, true)
                        }
                }
            } else if (segmentButton != null) {
                val hadFocus = segmentButton?.hasFocus() == true
                segmentJob?.cancel()
                segmentButton = null
                segmentShownId = null
                val old = segmentHolder.getChildAt(0)
                if (hadFocus) playerBox.requestFocus()
                old?.isFocusable = false
                old
                    ?.animate()
                    ?.alpha(0f)
                    ?.setDuration(220L)
                    ?.withEndAction { segmentHolder.removeView(old) }
                    ?.start()
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Next up

        private fun bindNextUp(newState: PlaybackState) {
            val next = newState.nextUp
            if (next == nextUpItem) return
            nextUpItem = next
            countdownJob?.cancel()
            if (next != null) {
                val v =
                    nextUpView ?: NextUpView(ctx, pageStyle.homeCardAppearance, accent).also { view ->
                        nextUpView = view
                        root.addView(view, FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                        view.card.setOnClickListener {
                            viewModel.reportInteraction()
                            cvs.hideControls()
                            viewModel.playNextUp()
                        }
                    }
                v.bind(
                    titleText = listOfNotNull(next.data.seasonEpisode, next.name).joinToString(" - "),
                    descriptionText = next.data.overview,
                    imageUrl = imageUrlService.getItemImageUrl(next, org.jellyfin.sdk.model.api.ImageType.PRIMARY),
                    aspect = next.aspectRatio ?: AspectRatios.WIDE,
                    runtime = next.data.runTimeTicks?.ticks,
                )
                autoPlayEnabled = viewModel.shouldAutoPlayNextUp()
                timeLeft = playback.autoPlayNextDelaySeconds
                v.setTimeLeft(if (autoPlayEnabled) timeLeft.seconds else null)
                v.alpha = 0f
                v.isVisible = true
                v
                    .animate()
                    .alpha(1f)
                    .setDuration(300L)
                    .start()
                animatePlayer(.6f)
                v.card.requestFocus()
                if (autoPlayEnabled) {
                    countdownJob =
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (timeLeft == 0L) {
                                viewModel.playNextUp()
                            } else {
                                while (timeLeft > 0) {
                                    delay(1.seconds)
                                    timeLeft--
                                    nextUpView?.setTimeLeft(if (autoPlayEnabled) timeLeft.seconds else null)
                                }
                                if (timeLeft == 0L && autoPlayEnabled) viewModel.playNextUp()
                            }
                        }
                }
            } else {
                nextUpView?.let { v ->
                    val hadFocus = v.hasFocus()
                    v.card.isFocusable = false
                    if (hadFocus) playerBox.requestFocus()
                    v
                        .animate()
                        .alpha(0f)
                        .setDuration(300L)
                        .withEndAction {
                            if (nextUpItem == null) {
                                v.isVisible = false
                                v.card.isFocusable = true
                            }
                        }.start()
                }
                animatePlayer(1f)
            }
        }

        private fun animatePlayer(target: Float) {
            playerAnimator?.cancel()
            playerAnimator =
                PlaybackAnim.value(playerFraction, target, 300L) {
                    playerFraction = it
                    playerFrame.fraction = it
                    layoutNextUp()
                }
        }

        /** padding(8dp).fillMaxHeight(1 - playerSize).fillMaxWidth(.66f), bottom centre */
        private fun layoutNextUp() {
            val v = nextUpView ?: return
            val m = ctx.dp(8)
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val w = ((root.width - m * 2) * .66f).toInt()
            val h = ((root.height - m * 2) * (1f - playerFraction)).toInt()
            if (lp.width != w || lp.height != h || lp.bottomMargin != m) {
                lp.width = w
                lp.height = h
                lp.bottomMargin = m
                v.layoutParams = lp
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Dialogs

        private fun pulseIfVisible() {
            if (cvs.controlsVisible) cvs.pulseControls()
        }

        /** The page's onDismissRequest: speed and scale go back to the settings dialog */
        private fun onDialogDismissed(type: PlaybackDialogType) {
            dialog = null
            delayDialog = null
            playbackDialogType = null
            val next =
                when (type) {
                    PlaybackDialogType.PLAYBACK_SPEED, PlaybackDialogType.VIDEO_SCALE -> PlaybackDialogType.SETTINGS
                    else -> null
                }
            pulseIfVisible()
            if (next != null) openDialog(next) else refresh()
        }

        private fun controllerInteraction() = cvs.pulseControls(Long.MAX_VALUE)

        fun openDialog(type: PlaybackDialogType) {
            if (type == PlaybackDialogType.SUBTITLE_DELAY) {
                // Hide controls so subtitles are fully visible
                cvs.hideControls()
            }
            dismissDialogs()
            playbackDialogType = type
            val current = state.currentPlayback
            val media = state.currentMediaInfo
            val isMpv = player is MpvPlayer
            when (type) {
                PlaybackDialogType.DEBUG -> {
                    throw IllegalStateException("Should not open a dialog with " + PlaybackDialogType.DEBUG)
                }

                PlaybackDialogType.CAPTIONS -> {
                    dialog =
                        showSubtitleDialog(
                            context = ctx,
                            accent = accent,
                            choices = media.subtitleStreams,
                            currentChoice = current?.subtitleIndex,
                            hasDownloadPermission = hasSubtitleDownloadPermission,
                            onCancel = {
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                            onSelect = { index ->
                                onDialogDismissed(type)
                                onPlaybackAction(PlaybackViewAction.ToggleCaptions(index))
                            },
                            onSearch = {
                                onDialogDismissed(type)
                                onPlaybackAction(PlaybackViewAction.SearchCaptions)
                            },
                        )
                }

                PlaybackDialogType.SETTINGS -> {
                    val options =
                        buildList {
                            add(
                                BottomChoice(
                                    PlaybackDialogType.PLAYBACK_SPEED,
                                    getString(R.string.playback_speed),
                                    playbackSpeed.toString(),
                                    // TODO Passing through audio prevents changing playback speed (Wholphin #164)
                                    enabled = instance.backend == PlayerBackend.MPV || current?.audioDecoder != null,
                                ),
                            )
                            if (!isMpv) {
                                add(
                                    BottomChoice(
                                        PlaybackDialogType.VIDEO_SCALE,
                                        getString(R.string.video_scale),
                                        getString(videoScale.label),
                                    ),
                                )
                            }
                            if (isMpv) {
                                add(
                                    BottomChoice(
                                        PlaybackDialogType.SUBTITLE_DELAY,
                                        getString(R.string.subtitle_delay),
                                        (current?.subtitleDelay ?: Duration.ZERO).toString(),
                                    ),
                                )
                            }
                            add(
                                BottomChoice(
                                    PlaybackDialogType.DEBUG,
                                    getString(if (showDebugInfo) R.string.hide_debug_info else R.string.show_debug_info),
                                    null,
                                ),
                            )
                        }
                    dialog =
                        showBottomDialog(
                            context = ctx,
                            accent = accent,
                            choices = options,
                            current = null,
                            gravity = Gravity.START,
                            elevationDp = 1f,
                            onCancel = { onDialogDismissed(type) },
                            onSelect = { _, choice ->
                                onDialogDismissed(type)
                                if (choice.data == PlaybackDialogType.DEBUG) {
                                    onPlaybackAction(PlaybackViewAction.ShowDebug)
                                } else {
                                    openDialog(choice.data)
                                }
                            },
                        )
                }

                PlaybackDialogType.AUDIO -> {
                    val choices =
                        media.audioStreams.map {
                            BottomChoice(
                                it,
                                it.streamTitle ?: it.displayTitle,
                                it.displayTitle.takeIf { _ ->
                                    it.streamTitle !=
                                        null
                                },
                            )
                        }
                    dialog =
                        showBottomDialog(
                            context = ctx,
                            accent = accent,
                            choices = choices,
                            current = choices.firstOrNull { it.data.index == current?.audioIndex },
                            gravity = Gravity.END,
                            elevationDp = 3f,
                            onCancel = {
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                            onSelect = { _, choice ->
                                controllerInteraction()
                                onDialogDismissed(type)
                                onPlaybackAction(PlaybackViewAction.ToggleAudio(choice.data.index))
                            },
                        )
                }

                PlaybackDialogType.PLAYBACK_SPEED -> {
                    val choices = playbackSpeedOptions.map { BottomChoice(it.toFloat(), it, null) }
                    dialog =
                        showBottomDialog(
                            context = ctx,
                            accent = accent,
                            choices = choices,
                            current = choices.firstOrNull { it.data == playbackSpeed },
                            gravity = Gravity.START,
                            elevationDp = 1f,
                            onCancel = {
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                            onSelect = { _, choice ->
                                // Apply first so the settings dialog it goes back to shows the new speed
                                onPlaybackAction(PlaybackViewAction.PlaybackSpeed(choice.data))
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                        )
                }

                PlaybackDialogType.VIDEO_SCALE -> {
                    val choices = VideoScale.entries.map { BottomChoice(it, getString(it.label), null) }
                    dialog =
                        showBottomDialog(
                            context = ctx,
                            accent = accent,
                            choices = choices,
                            current = choices.firstOrNull { it.data == videoScale },
                            gravity = Gravity.START,
                            elevationDp = 1f,
                            onCancel = {
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                            onSelect = { _, choice ->
                                onPlaybackAction(PlaybackViewAction.Scale(choice.data))
                                controllerInteraction()
                                onDialogDismissed(type)
                            },
                        )
                }

                PlaybackDialogType.SUBTITLE_DELAY -> {
                    val d =
                        SubtitleDelayDialog(
                            ctx,
                            accent,
                            onChangeDelay = { viewModel.updateSubtitleDelay(it) },
                            onCancel = { onDialogDismissed(type) },
                        )
                    d.setDelay(current?.subtitleDelay ?: Duration.ZERO)
                    delayDialog = d
                    d.show()
                }
            }
            refresh()
        }

        // ---------------------------------------------------------------------------------------------------------
        // Subtitle search

        fun onSubtitleSearch(search: SubtitleSearchState) {
            val was = searchStatus
            searchStatus = search.status
            if (search.status == SubtitleSearchStatus.Inactive) {
                searchDialog?.setOnCancelListener(null)
                searchDialog?.dismiss()
                searchDialog = null
                searchView = null
                if (was != SubtitleSearchStatus.Inactive) refresh()
                return
            }
            if (searchDialog == null) {
                searchWasPlaying = player.isPlaying
                player.pause()
                val view =
                    DownloadSubtitlesView(
                        ctx,
                        accent,
                        onSearch = { lang -> viewModel.searchForSubtitles(lang) },
                        onClickDownload = { viewModel.downloadAndSwitchSubtitles(it.id, searchWasPlaying) },
                        onSendLogs = ::sendLogs,
                    )
                val d = playbackDialog(ctx, Gravity.CENTER, ViewGroup.LayoutParams.MATCH_PARENT)
                val holder = FrameLayout(ctx)
                holder.addView(
                    DownloadSubtitlesView.panel(ctx, accent, view),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
                d.setContentView(holder)
                // BasicDialog keeps the platform dialog dim
                d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                d.window?.setDimAmount(.6f)
                d.setOnCancelListener {
                    if (searchWasPlaying) player.play()
                    viewModel.cancelSubtitleSearch()
                }
                d.show()
                searchDialog = d
                searchView = view
            }
            searchView?.bind(search.status, search.language)
            if (was == SubtitleSearchStatus.Inactive) refresh()
        }
    }
}
