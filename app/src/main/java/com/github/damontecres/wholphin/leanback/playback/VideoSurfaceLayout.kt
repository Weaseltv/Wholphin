package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.PrefContentScale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The player's aspect ratio / zoom modes (the Compose `ContentScale`s the old player offered, same order and labels).
 */
enum class VideoScale(
    @param:StringRes val label: Int,
) {
    FIT(R.string.content_scale_fit),
    NONE(R.string.none),
    CROP(R.string.content_scale_crop),
    FILL(R.string.content_scale_fill),
    FILL_WIDTH(R.string.content_scale_fill_width),
    FILL_HEIGHT(R.string.content_scale_fill_height),
    ;

    /** `ContentScale.computeScaleFactor`: x and y scale of a source of [srcW]x[srcH] into [dstW]x[dstH] */
    fun scaleFactor(
        srcW: Float,
        srcH: Float,
        dstW: Float,
        dstH: Float,
    ): Pair<Float, Float> {
        val sx = dstW / srcW
        val sy = dstH / srcH
        return when (this) {
            FIT -> min(sx, sy).let { it to it }
            NONE -> 1f to 1f
            CROP -> max(sx, sy).let { it to it }
            FILL -> sx to sy
            FILL_WIDTH -> sx to sx
            FILL_HEIGHT -> sy to sy
        }
    }

    companion object {
        /** The global preference's mode (`PrefContentScale.scale`) */
        fun from(pref: PrefContentScale): VideoScale =
            when (pref) {
                PrefContentScale.FIT -> FIT
                PrefContentScale.NONE -> NONE
                PrefContentScale.CROP -> CROP
                PrefContentScale.FILL -> FILL
                PrefContentScale.Fill_WIDTH -> FILL_WIDTH
                PrefContentScale.FILL_HEIGHT -> FILL_HEIGHT
                PrefContentScale.UNRECOGNIZED -> FIT
            }
    }
}

/**
 * Hosts the player's [SurfaceView] for every backend (ExoPlayer and MPV), the way the Compose page hosted it with
 * media3's `PlayerSurface(SURFACE_TYPE_SURFACE_VIEW)` + `resizeWithContentScale(scale, presentationState.videoSizeDp)`:
 * the surface is sized from the video size (pixel aspect applied, taken as dp like `videoSizeDp`) by the [scale] and
 * centred; before a video size is known it fills the box.
 *
 * Also tracks media3's `PresentationState.coverSurface` (black cover until the first frame) through [onCoverChanged].
 */
@OptIn(UnstableApi::class)
class VideoSurfaceLayout(
    context: Context,
) : ViewGroup(context) {
    val surfaceView = SurfaceView(context)

    private var player: Player? = null
    private var videoW = 0f
    private var videoH = 0f

    var scale: VideoScale = VideoScale.FIT
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Whether the cover over the surface shows (no first frame yet, or no video track) */
    var coverSurface: Boolean = true
        private set(value) {
            if (field != value) {
                field = value
                onCoverChanged?.invoke(value)
            }
        }

    var onCoverChanged: ((Boolean) -> Unit)? = null

    /** Called after the surface was laid out, with its size (the AssSubtitleView follows it) */
    var onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                    if (player.videoSize != VideoSize.UNKNOWN && player.playbackState != Player.STATE_IDLE) {
                        updateVideoSize(player)
                    }
                }
                if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
                    coverSurface = false
                }
                if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                    if (!shouldKeepSurfaceVisible(player)) maybeHideSurface(player)
                }
            }
        }

    init {
        addView(surfaceView)
    }

    /** Attach [newPlayer] to the surface (and detach the previous one), like `PlayerSurface` */
    fun setPlayer(newPlayer: Player?) {
        val old = player
        if (old === newPlayer) return
        old?.removeListener(listener)
        old?.clearVideoSurfaceView(surfaceView)
        player = newPlayer
        coverSurface = true
        videoW = 0f
        videoH = 0f
        if (newPlayer != null) {
            newPlayer.setVideoSurfaceView(surfaceView)
            newPlayer.addListener(listener)
            updateVideoSize(newPlayer)
            if (!shouldKeepSurfaceVisible(newPlayer)) maybeHideSurface(newPlayer)
        }
        requestLayout()
    }

    private fun updateVideoSize(player: Player) {
        val size = player.videoSize
        var w = size.width.toFloat()
        var h = size.height.toFloat()
        if (w == 0f || h == 0f) {
            w = 0f
            h = 0f
        } else {
            val par = size.pixelWidthHeightRatio
            if (par < 1f) {
                w *= par
            } else if (par > 1f) {
                h /= par
            }
        }
        if (w != videoW || h != videoH) {
            videoW = w
            videoH = h
            requestLayout()
        }
    }

    private fun shouldKeepSurfaceVisible(player: Player): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) return false
        return hasSelectedVideoTrack(player.currentTracks)
    }

    private fun maybeHideSurface(player: Player) {
        val hasTracks = player.isCommandAvailable(Player.COMMAND_GET_TRACKS) && !player.currentTracks.isEmpty
        if (!hasTracks) coverSurface = true
        if (hasTracks && !hasSelectedVideoTrack(player.currentTracks)) coverSurface = true
    }

    private fun hasSelectedVideoTrack(tracks: Tracks): Boolean = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (sw, sh) = surfaceSize(w, h)
        surfaceView.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
    }

    private fun surfaceSize(
        w: Int,
        h: Int,
    ): Pair<Int, Int> {
        if (videoW <= 0f || videoH <= 0f || w == 0 || h == 0) return w to h
        val density = resources.displayMetrics.density
        val srcW = videoW * density
        val srcH = videoH * density
        val (sx, sy) = scale.scaleFactor(srcW, srcH, w.toFloat(), h.toFloat())
        return (srcW * sx).roundToInt() to (srcH * sy).roundToInt()
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        val w = r - l
        val h = b - t
        val sw = surfaceView.measuredWidth
        val sh = surfaceView.measuredHeight
        val left = (w - sw) / 2
        val top = (h - sh) / 2
        surfaceView.layout(left, top, left + sw, top + sh)
        onSurfaceSizeChanged?.invoke(sw, sh)
    }

    /** Detach from the player (the page is going away) */
    fun release() {
        setPlayer(null)
    }
}
