package com.github.damontecres.wholphin.leanback.playback

import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.load
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.VideoFilter
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.slideshow.ImageLoadingState
import com.github.damontecres.wholphin.ui.slideshow.ImageState
import com.github.damontecres.wholphin.ui.slideshow.SlideshowState
import com.github.damontecres.wholphin.ui.slideshow.SlideshowViewModel
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

/**
 * The photo slideshow (no-Compose rewrite of `SlideshowPage`, 2026-11-22): photos (with zoom, pan, rotation and the
 * colour filter) and video clips, slideshow mode, the details overlay and the filter dialog.
 *
 * Keys as the old page: LEFT/RIGHT change image, a held UP/DOWN zooms, D-pad pans while zoomed, OK or D-pad shows the
 * overlay; BACK hides the overlay, then resets the zoom, then leaves.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class SlideshowFragment : DestinationFragment() {
    @Inject
    lateinit var serverReportService: ServerReportService

    private val viewModel: SlideshowViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<SlideshowViewModel.Factory> {
                it.create(requireDestination<Destination.Slideshow>())
            }
        },
    )

    private lateinit var root: FrameLayout
    private var statusView: View? = null
    private var page: Page? = null

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
        combine(viewModel.state, viewModel.imageFilter) { s, f -> s to f }.collectWhileStarted { (state, filter) -> render(state, filter) }
    }

    private fun render(
        state: SlideshowState,
        filter: VideoFilter,
    ) {
        when (val loading = state.loading) {
            is LoadingState.Error -> {
                page?.destroy()
                page = null
                showStatus(PlaybackErrorView(requireContext(), loading.message, loading.exception, pageStyle.accentFor(null), ::sendLogs))
            }

            LoadingState.Loading, LoadingState.Pending -> {
                if (statusView !is PlaybackLoadingView) showStatus(PlaybackLoadingView(requireContext()))
            }

            LoadingState.Success -> {
                if (page == null) {
                    statusView?.let { root.removeView(it) }
                    statusView = null
                    page = Page().also { it.attach() }
                    page?.root?.requestFocus()
                }
                page?.bind(state, filter)
            }
        }
    }

    private fun showStatus(v: View) {
        statusView?.let { root.removeView(it) }
        statusView = v
        root.addView(
            v,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        v.requestFocus()
    }

    private fun sendLogs() {
        viewLifecycleOwner.lifecycleScope.launchIO { serverReportService.sendAppLogs() }
    }

    override fun onScreenFocus(restoring: Boolean) {
        val p = page
        if (p == null) {
            statusView?.requestFocus() ?: super.onScreenFocus(restoring)
        } else if (!(restoring && p.restoreFocus())) {
            p.root.requestFocus()
        }
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean = page?.onKeyEvent(event) ?: false

    override fun onBackPressed(): Boolean = page?.onBackPressed() ?: false

    override fun onStop() {
        super.onStop()
        // LifecycleStartEffect: stop a playing clip
        page?.stopClip()
    }

    override fun onDestroyView() {
        page?.destroy()
        page = null
        statusView = null
        super.onDestroyView()
    }

    /** The loaded slideshow page */
    private inner class Page {
        private val ctx = requireContext()
        private val player: Player = viewModel.player
        val root = FrameLayout(ctx)
        private val stage = FrameLayout(ctx)
        private val imageContainer = FrameLayout(ctx)
        private val imageView = ImageView(ctx)
        private val videoContainer = FrameLayout(ctx)
        private val surface = VideoSurfaceLayout(ctx)
        private val videoCover = View(ctx)
        private var stageStatus: View? = null
        private var stageErrorFor: java.util.UUID? = null
        private val overlay: ImageOverlayView
        private var filterDialog: ImageFilterDialog? = null
        private val displayToggles: Set<DisplayToggle> =
            preferences.appPreferences.interfacePreferences.displayTogglesList
                .toSet()

        private var state = SlideshowState()
        private var filter = VideoFilter()
        private var zoomFactor = 1f
        private var rotation = 0f
        private var panX = 0f
        private var panY = 0f
        private var showOverlay = false
        private var showFilterDialog = false
        private var longPressing = false
        private var currentImageId: java.util.UUID? = null
        private var clipItemId: java.util.UUID? = null
        private var imageRequest: Disposable? = null
        private var requestedOriginal = false

        private val isZoomed: Boolean get() = zoomFactor * 100 > 102

        private val playerListener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    // As the old header: its "isPlaying" was the play/pause state's showPlay
                    overlay.setPlaying(Util.shouldShowPlayButton(player))
                }
            }

        init {
            root.isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) root.defaultFocusHighlightEnabled = false
            root.setBackgroundColor(Color.BLACK)
            root.clipChildren = false

            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageContainer.addView(
                imageView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            stage.addView(
                imageContainer,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )

            surface.scale = VideoScale.FIT
            videoContainer.addView(
                surface,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            videoCover.setBackgroundColor(Color.BLACK)
            videoContainer.addView(
                videoCover,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            surface.onCoverChanged = { videoCover.isVisible = it }
            videoContainer.visibility = View.GONE
            stage.addView(
                videoContainer,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            root.addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            overlay =
                ImageOverlayView(
                    ctx,
                    pageStyle.accentFor(null),
                    displayToggles,
                    object : ImageOverlayView.Callbacks {
                        override fun onSlideshowButton(enabled: Boolean) {
                            if (enabled) {
                                viewModel.stopSlideshow()
                            } else {
                                // SlideshowControls.startSlideshow hides the overlay
                                setOverlay(false)
                                viewModel.startSlideshow()
                            }
                        }

                        override fun onRotate(degrees: Int) {
                            rotation += degrees
                            applyTransforms()
                        }

                        override fun onZoom(factor: Float) = zoom(factor)

                        override fun onReset() = reset(true)

                        override fun onShowFilter() {
                            showFilterDialog = true
                            setOverlay(false)
                            viewModel.pauseSlideshow()
                            openFilterDialog()
                        }

                        override fun onPlayPause() {
                            Util.handlePlayPauseButtonAction(player)
                        }
                    },
                )
            overlay.visibility = View.GONE
            root.addView(
                overlay,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        }

        fun attach() {
            this@SlideshowFragment.root.addView(
                root,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            player.addListener(playerListener)
        }

        fun destroy() {
            filterDialog?.dialog?.setOnCancelListener(null)
            filterDialog?.dialog?.dismiss()
            filterDialog = null
            imageRequest?.dispose()
            player.removeListener(playerListener)
            stopClip()
            surface.release()
            this@SlideshowFragment.root.removeView(root)
        }

        fun stopClip() {
            if (clipItemId != null) player.stop()
        }

        fun restoreFocus(): Boolean = showOverlay && overlay.hasFocus()

        fun bind(
            newState: SlideshowState,
            newFilter: VideoFilter,
        ) {
            val oldState = state
            val filterChanged = newFilter != filter
            state = newState
            filter = newFilter
            // LaunchedEffect(slideshowActive)
            if (oldState.enabled != newState.enabled || oldState.paused != newState.paused) {
                player.repeatMode = if (newState.enabled) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            }
            when (val loading = newState.imageLoading) {
                ImageLoadingState.Error -> {
                    showStageStatus(
                        PlaybackErrorView(ctx, "Error loading image", null, pageStyle.accentFor(null), ::sendLogs),
                    )
                }

                ImageLoadingState.Loading -> {
                    if (stageStatus !is PlaybackLoadingView) showStageStatus(PlaybackLoadingView(ctx, focusEnabled = false))
                }

                is ImageLoadingState.Success -> {
                    // Keep the image's error text until another image shows
                    if (stageStatus != null && (stageErrorFor == null || stageErrorFor != loading.image.id)) showStageStatus(null)
                    showImage(loading.image, filterChanged)
                    overlay.bind(loading.image, newState.position, newState.items.size, newState.enabled, Util.shouldShowPlayButton(player))
                }
            }
            filterDialog?.bind(newFilter)
        }

        private fun showStageStatus(v: View?) {
            stageStatus?.let { stage.removeView(it) }
            stageStatus = v
            stageErrorFor = null
            if (v != null) {
                imageContainer.isVisible = false
                videoContainer.isVisible = false
                stage.addView(
                    v,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )
            }
        }

        private fun showImage(
            image: ImageState,
            filterChanged: Boolean,
        ) {
            val newImage = image.id != currentImageId
            if (newImage) {
                currentImageId = image.id
                // LaunchedEffect(imageState): reset zoom, pan and rotation
                reset(true, animate = false)
            }
            if (image.image.data.mediaType == org.jellyfin.sdk.model.api.MediaType.VIDEO) {
                imageContainer.isVisible = false
                videoContainer.isVisible = true
                imageRequest?.dispose()
                imageView.setImageDrawable(null)
                if (clipItemId != image.id) {
                    clipItemId = image.id
                    surface.setPlayer(player)
                    player.setMediaItem(MediaItem.Builder().setUri(image.url).build())
                    player.repeatMode = if (state.enabled) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                    player.prepare()
                    player.play()
                    viewModel.pulseSlideshow(Long.MAX_VALUE)
                }
            } else {
                if (clipItemId != null) {
                    // The clip left the screen: LifecycleStartEffect onStopOrDispose
                    player.stop()
                    clipItemId = null
                    surface.setPlayer(null)
                }
                videoContainer.isVisible = false
                imageContainer.isVisible = true
                imageView.colorFilter = if (filter.hasImageFilter()) ColorMatrixColorFilter(filter.colorMatrix) else null
                if (newImage || (isZoomed && !requestedOriginal)) loadImage(image)
            }
            if (filterChanged && !newImage) {
                imageView.colorFilter = if (filter.hasImageFilter()) ColorMatrixColorFilter(filter.colorMatrix) else null
            }
        }

        private fun loadImage(image: ImageState) {
            imageRequest?.dispose()
            // Fixes images not being full size when resolutions change (Wholphin #1089)
            requestedOriginal = isZoomed
            imageRequest =
                imageView.load(image.url) {
                    if (requestedOriginal) size(Size.ORIGINAL)
                    crossfade(750)
                    listener(
                        onStart = {
                            // Ensure that if an image takes a long time to load, it won't be skipped
                            viewModel.pulseSlideshow(Long.MAX_VALUE)
                        },
                        onSuccess = { _, _ -> viewModel.pulseSlideshow() },
                        onError = { _: ImageRequest, result ->
                            Timber.e(result.throwable, "Error loading image ${image.id}")
                            Toast.makeText(ctx, "Error loading image: ${result.throwable.localizedMessage}", Toast.LENGTH_LONG).show()
                            showStageError()
                            viewModel.pulseSlideshow()
                        },
                    )
                }
        }

        /** SubcomposeAsyncImage's error slot: "Error loading image" in the middle */
        private fun showStageError() {
            val text =
                ctx.textView(TextRole.BODY_LARGE, AppTheme.palette.onBackground).apply {
                    text = "Error loading image"
                    gravity = Gravity.CENTER
                }
            stageStatus?.let { stage.removeView(it) }
            stageStatus = text
            stageErrorFor = currentImageId
            stage.addView(
                text,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }

        // -----------------------------------------------------------------------------------------------------
        // Zoom, pan, rotate

        private fun reset(
            resetRotate: Boolean,
            animate: Boolean = true,
        ) {
            zoomFactor = 1f
            panX = 0f
            panY = 0f
            if (resetRotate) rotation = 0f
            applyTransforms(animate)
        }

        private fun pan(
            xFactor: Int,
            yFactor: Int,
        ) {
            val maxPanX = root.width * .75f
            val maxPanY = root.height * .75f
            if (xFactor != 0) panX = (panX + ctx.dpF(xFactor.toFloat())).coerceIn(-maxPanX, maxPanX)
            if (yFactor != 0) panY = (panY + ctx.dpF(yFactor.toFloat())).coerceIn(-maxPanY, maxPanY)
            applyTransforms()
        }

        private fun zoom(factor: Float) {
            if (factor < 0) {
                // Zooming out: bring the pan back in proportion
                val diffFactor = factor / (zoomFactor - 1f)
                val panXDiff = abs(panX * diffFactor)
                val panYDiff = abs(panY * diffFactor)
                if (panX > 0f) {
                    panX -= panXDiff
                } else if (panX < 0f) {
                    panX += panXDiff
                }
                if (panY > 0f) {
                    panY -= panYDiff
                } else if (panY < 0f) {
                    panY += panYDiff
                }
            }
            zoomFactor = (zoomFactor + factor).coerceIn(1f, 5f)
            if (!isZoomed) {
                // Always reset if not zoomed
                panX = 0f
                panY = 0f
            }
            applyTransforms()
            // A zoomed photo reloads at its original size
            val image = (state.imageLoading as? ImageLoadingState.Success)?.image
            if (image != null && isZoomed && !requestedOriginal &&
                image.image.data.mediaType != org.jellyfin.sdk.model.api.MediaType.VIDEO
            ) {
                loadImage(image)
            }
        }

        /**
         * graphicsLayer(scale, translation, origin from the pan) around rotate(): the photo rotates about its centre
         * inside a container that scales and moves; the clip scales about its centre.
         */
        private fun applyTransforms(animate: Boolean = true) {
            val w = root.width.toFloat().takeIf { it > 0 } ?: 1f
            val h = root.height.toFloat().takeIf { it > 0 } ?: 1f
            imageContainer.pivotX = ((w - panX) / (w * 2)) * imageContainer.width
            imageContainer.pivotY = ((h - panY) / (h * 2)) * imageContainer.height
            listOf(imageContainer, videoContainer).forEach { v ->
                v.animate().cancel()
                if (animate) {
                    v
                        .animate()
                        .scaleX(
                            zoomFactor,
                        ).scaleY(zoomFactor)
                        .translationX(panX)
                        .translationY(panY)
                        .setDuration(300L)
                        .setInterpolator(FastOutSlowIn)
                        .start()
                } else {
                    v.scaleX = zoomFactor
                    v.scaleY = zoomFactor
                    v.translationX = panX
                    v.translationY = panY
                }
            }
            listOf(imageView, surface).forEach { v ->
                v.animate().cancel()
                if (animate) {
                    v
                        .animate()
                        .rotation(rotation)
                        .setDuration(300L)
                        .setInterpolator(FastOutSlowIn)
                        .start()
                } else {
                    v.rotation =
                        rotation
                }
            }
        }

        // -----------------------------------------------------------------------------------------------------
        // Overlay and filter

        private fun setOverlay(show: Boolean) {
            if (show == showOverlay) return
            showOverlay = show
            overlay.animate().cancel()
            if (show) {
                overlay.visibility = View.VISIBLE
                overlay.post {
                    overlay.translationY = overlay.height.toFloat()
                    overlay
                        .animate()
                        .translationY(0f)
                        .setDuration(300L)
                        .setInterpolator(FastOutSlowIn)
                        .start()
                }
                overlay.focusFirst()
            } else {
                if (overlay.hasFocus()) root.requestFocus()
                overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                overlay
                    .animate()
                    .translationY(overlay.height.toFloat())
                    .setDuration(300L)
                    .setInterpolator(FastOutSlowIn)
                    .withEndAction {
                        if (!showOverlay) overlay.visibility = View.GONE
                    }.start()
            }
            if (show) overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        private fun openFilterDialog() {
            val d =
                ImageFilterDialog(
                    ctx,
                    accent = pageStyle.accentFor(null),
                    showVideoOptions = false,
                    showSaveGalleryButton = true,
                    onChange = viewModel::updateImageFilter,
                    onClickSave = viewModel::saveImageFilter,
                    onClickSaveGallery = viewModel::saveGalleryFilter,
                    onDismiss = {
                        filterDialog = null
                        showFilterDialog = false
                        viewModel.unpauseSlideshow()
                        viewModel.pulseSlideshow()
                    },
                )
            d.bind(filter)
            filterDialog = d
            d.show()
        }

        // -----------------------------------------------------------------------------------------------------
        // Keys

        fun onKeyEvent(event: KeyEvent): Boolean {
            val overlayShowing = showOverlay || showFilterDialog
            // With the overlay up its buttons handle the keys (BACK goes through onBackPressed)
            if (overlayShowing) return false
            if (PlaybackKeys.isBackKey(event)) return false
            val keyUp = event.action == KeyEvent.ACTION_UP
            if (longPressing && keyUp) {
                // The user stopped long pressing: cancel the zooming but still consume it so the image doesn't move
                longPressing = false
                return true
            }
            longPressing = event.isLongPress || event.repeatCount > 0
            if (longPressing) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> zoom(.05f)
                    KeyEvent.KEYCODE_DPAD_DOWN -> zoom(-.05f)
                }
                return true
            }
            var result = false
            if (!keyUp) {
                result = false
            } else if (isZoomed && PlaybackKeys.isDirectionalDpad(event)) {
                // Image is zoomed in
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> pan(30, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> pan(-30, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> pan(0, 30)
                    KeyEvent.KEYCODE_DPAD_DOWN -> pan(0, -30)
                }
                result = true
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (!viewModel.previousImage()) Toast.makeText(ctx, R.string.slideshow_at_beginning, Toast.LENGTH_SHORT).show()
            } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!viewModel.nextImage()) Toast.makeText(ctx, R.string.no_more_images, Toast.LENGTH_SHORT).show()
            } else if (PlaybackKeys.isDpad(event) || PlaybackKeys.isEnterKey(event)) {
                setOverlay(true)
                viewModel.pauseSlideshow()
                result = true
            }
            // Handled the key, so reset the slideshow timer
            if (result) viewModel.pulseSlideshow()
            return result
        }

        fun onBackPressed(): Boolean {
            if (showOverlay) {
                setOverlay(false)
                viewModel.unpauseSlideshow()
                viewModel.pulseSlideshow()
                return true
            }
            if (isZoomed) {
                reset(false)
                viewModel.pulseSlideshow()
                return true
            }
            return false
        }
    }
}
