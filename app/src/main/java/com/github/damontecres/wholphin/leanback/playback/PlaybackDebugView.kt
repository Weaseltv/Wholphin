package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.formatBitrate
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.util.TrackSupport
import timber.log.Timber
import java.util.Locale

/**
 * The debug overlay (`PlaybackDebugOverlay` + `PlaybackTrackInfo`), no-Compose: backend, decoders, display mode,
 * bitrates, dropped frames and memory, transcoding details, and the track support table with "Show more".
 * 10sp text on 50 % black, capped at the space above the controller.
 */
internal class PlaybackDebugView(
    context: Context,
    private val onFocusInside: (Boolean) -> Unit,
) : LinearLayout(context) {
    /** heightIn(max = screen height - controller height) */
    var maxHeightPx: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    private val tables = LinearLayout(context)
    private val trackInfo = TrackInfoView(context)

    /** The page accent (`LocalNeonAccent`) for the focusable rows and the button */
    var accent: Int
        get() = trackInfo.accent
        set(value) {
            trackInfo.accent = value
        }
    private var currentPlayback: CurrentPlayback? = null
    private var analytics = AnalyticsState()
    private var displayMode: String? = null
    private var memory = ""
    private var running = false
    private var bound = false

    private val display: Display? =
        try {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (ex: Exception) {
            Timber.e(ex)
            null
        }

    private val displayTicker =
        object : Runnable {
            override fun run() {
                if (!running) return
                displayMode =
                    display?.mode?.let {
                        val rate = String.format(Locale.getDefault(), "%.3f", it.refreshRate)
                        "${it.physicalWidth}x${it.physicalHeight}@${rate}fps, id=${it.modeId}"
                    }
                rebuildTables()
                postDelayed(this, 10_000L)
            }
        }

    private val memoryTicker =
        object : Runnable {
            override fun run() {
                if (!running) return
                val runtime = Runtime.getRuntime()
                val total = runtime.totalMemory()
                val used = total - runtime.freeMemory()
                memory = "${formatBytes(used)} / ${formatBytes(total)}"
                rebuildTables()
                postDelayed(this, 2_000L)
            }
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(PlaybackColors.BLACK_50)
        val p8 = context.dp(8)
        setPadding(p8, p8, p8, p8)
        tables.orientation = HORIZONTAL
        tables.setPadding(p8, p8, 0, 0)
        addView(tables, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(trackInfo, LayoutParams(LayoutParams.WRAP_CONTENT, 0, 1f).apply { topMargin = p8 })
        viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            val wasInside = old != null && isDescendant(old)
            val isInside = new != null && isDescendant(new)
            if (wasInside != isInside) onFocusInside(isInside)
        }
    }

    private fun isDescendant(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        // Leave room under it (padding bottom 16dp in the old modifier chain)
        val cap = if (maxHeightPx > 0) (maxHeightPx - context.dp(16)).coerceAtLeast(0) else MeasureSpec.getSize(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec).let { if (it == 0) cap else minOf(it, cap) }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST))
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        val shown = isShown
        if (shown != running) {
            running = shown
            removeCallbacks(displayTicker)
            removeCallbacks(memoryTicker)
            if (shown) {
                displayTicker.run()
                memoryTicker.run()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(displayTicker)
        removeCallbacks(memoryTicker)
    }

    fun bind(
        currentPlayback: CurrentPlayback?,
        analyticsState: AnalyticsState,
    ) {
        if (currentPlayback == this.currentPlayback && analyticsState == this.analytics && bound) return
        bound = true
        val tracksChanged = currentPlayback?.tracks != this.currentPlayback?.tracks
        this.currentPlayback = currentPlayback
        this.analytics = analyticsState
        rebuildTables()
        if (tracksChanged) {
            val tracks = currentPlayback?.tracks.orEmpty()
            trackInfo.isVisible = tracks.isNotEmpty()
            trackInfo.bind(tracks)
        }
    }

    private fun rebuildTables() {
        tables.removeAllViews()
        val cp = currentPlayback
        val first =
            buildList {
                add("Backend:" to cp?.backend?.toString())
                add("Play method:" to cp?.playMethod?.serialName)
                if (cp?.backend == PlayerBackend.EXO_PLAYER) {
                    add("Video Decoder:" to cp.videoDecoder)
                    add("Audio Decoder:" to (cp.audioDecoder ?: "Non-ExoPlayer"))
                }
                add("Display Mode: " to displayMode)
            }
        addTable(first, 80)
        addTable(
            listOf(
                "Bitrate: " to analytics.bitrate,
                "Bitrate (est): " to analytics.bitrateEstimate,
                "Dropped frames: " to analytics.droppedFrames,
                "Memory" to memory,
            ),
            80,
        )
        cp?.transcodeInfo?.let { info ->
            addTable(
                listOf(
                    "Reason:" to info.transcodeReasons.joinToString(", "),
                    "HW Accel:" to info.hardwareAccelerationType?.toString(),
                    "Container:" to info.container,
                    "Bitrate:" to info.bitrate?.let { formatBitrate(it) },
                ),
                64,
            )
            addTable(
                listOf(
                    "Video:" to "${info.videoCodec}, ${info.width}x${info.height}",
                    "Video Direct:" to info.isVideoDirect.toString(),
                    "Audio:" to "${info.audioCodec}, ch=${info.audioChannels}",
                    "Audio Direct:" to info.isAudioDirect.toString(),
                ),
                64,
            )
        }
    }

    /** SimpleTable: key column of [keyWidthDp], 4dp between rows and columns, tables 16dp apart */
    private fun addTable(
        rows: List<Pair<String, Any?>>,
        keyWidthDp: Int,
    ) {
        val table = LinearLayout(context).apply { orientation = VERTICAL }
        rows.forEachIndexed { i, (key, value) ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.addView(debugText(context, key), LayoutParams(context.dp(keyWidthDp), LayoutParams.WRAP_CONTENT))
            row.addView(
                debugText(context, value.toString()),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart =
                        context.dp(4)
                },
            )
            table.addView(
                row,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i >
                        0
                    ) {
                        topMargin = context.dp(4)
                    }
                },
            )
        }
        tables.addView(
            table,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (tables.childCount >
                    0
                ) {
                    marginStart = context.dp(16)
                }
            },
        )
    }
}

/** bodySmall at 10sp in onSurface */
private fun debugText(
    context: Context,
    text: String,
): TextView =
    context.textView(TextRole.BODY_SMALL).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        this.text = text
    }

/**
 * Debug info about the current playback tracks (`PlaybackTrackInfo`): the selected tracks, or all of them after
 * "Show more"; rows are focusable outline surfaces, the last row moves down/right to the button, the button up to the
 * first row.
 */
private class TrackInfoView(
    context: Context,
) : LinearLayout(context) {
    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private val buttonRow = FrameLayout(context)
    private var button: SurfaceTextView? = null
    private var tracks: List<TrackSupport> = emptyList()
    private var expanded = false
    private val weights = listOf(.25f, .4f, .5f, 1f, 1f)
    private val selectedWeight = .5f
    private val rows = mutableListOf<View>()
    var accent: Int = NeonColors.VOLT

    init {
        orientation = VERTICAL
        setPadding(context.dp(16), 0, context.dp(16), 0)
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        list.orientation = VERTICAL
        list.setPadding(0, context.dp(20), 0, context.dp(20))
        scroll.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 1f))
        addView(buttonRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(trackSupport: List<TrackSupport>) {
        tracks = trackSupport
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        rows.clear()
        val selected = tracks.filter { it.selected }
        list.addView(headerRow())
        val shown = if (expanded) tracks else selected
        shown.forEach { track ->
            val row = trackRow(track)
            rows.add(row)
            list.addView(row)
        }
        buttonRow.removeAllViews()
        button = null
        if (tracks.size > selected.size) {
            val text =
                if (expanded) {
                    context.getString(R.string.hide)
                } else {
                    context.getString(R.string.show_more, tracks.size - selected.size)
                }
            val b =
                OutlineButton.text(context, text, accent, textSizeSp = 10f).apply {
                    setOnClickListener {
                        expanded = !expanded
                        rebuild()
                        button?.requestFocus()
                    }
                    setOnKeyListener { _, _, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && PlaybackKeys.isUp(event)) {
                            rows.firstOrNull()?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                }
            button = b
            buttonRow.addView(
                b,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END),
            )
        }
        rows.lastOrNull()?.setOnKeyListener { _, _, event ->
            val b = button
            if (b != null && event.action == KeyEvent.ACTION_DOWN &&
                (PlaybackKeys.isDown(event) || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                b.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun cells(
        row: LinearLayout,
        first: View,
        texts: List<String>,
    ) {
        row.addView(
            FrameLayout(context).apply {
                addView(
                    first,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, selectedWeight),
        )
        texts.forEachIndexed { index, text ->
            row.addView(
                debugText(context, text).apply { isDuplicateParentStateEnabled = true },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[index]).apply { marginStart = context.dp(4) },
            )
        }
    }

    private fun headerRow(): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            cells(this, debugText(context, "Selected"), listOf("ID", "Type", "Codec", "Supported", "Labels"))
        }

    private fun trackRow(track: TrackSupport): View {
        val neon = AppTheme.isNeon
        val p = AppTheme.palette
        val row =
            SurfaceFrameLayout(context).apply {
                isFocusable = true
                isClickable = true
                background =
                    SurfaceDrawable(
                        if (neon) {
                            val focused = Stroke(context.dpF(1f), accent)
                            SurfaceStyle(
                                container = Color.TRANSPARENT,
                                focusedContainer = NeonColors.chipOn(accent),
                                focusedBorder = focused,
                                glow = Glow(context.dpF(10f), withAlpha(neonGlowAccent(accent), .85f)),
                            )
                        } else {
                            SurfaceStyle(
                                container = Color.TRANSPARENT,
                                focusedContainer = withAlpha(p.tertiary, .25f),
                                corner = context.dpF(8f),
                            )
                        },
                    )
            }
        val inner =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                val pad = context.dp(4)
                setPadding(pad, pad, pad, pad)
                if (track.selected) setBackgroundColor(withAlpha(p.border, .25f))
            }
        val first: View =
            if (track.selected) {
                ImageView(context).apply {
                    setImageResource(R.drawable.lb_playback_ic_check)
                    imageTintList =
                        android.content.res.ColorStateList
                            .valueOf(p.onBackground)
                    layoutParams = FrameLayout.LayoutParams(context.dp(12), context.dp(12))
                }
            } else {
                debugText(context, "-")
            }
        cells(
            inner,
            first,
            listOf(track.id ?: "", track.type.name, track.codecs ?: "", track.supported.name, track.labels.joinToString(", ")),
        )
        row.addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return row
    }
}
