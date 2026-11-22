package com.github.damontecres.wholphin.leanback.playback

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.VideoFilter
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.compositeOver
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.DisplayToggle
import com.github.damontecres.wholphin.ui.playback.audioStreamCount
import com.github.damontecres.wholphin.ui.playback.embeddedSubtitleCount
import com.github.damontecres.wholphin.ui.playback.externalSubtitlesCount
import com.github.damontecres.wholphin.ui.slideshow.ImageState
import com.github.damontecres.wholphin.ui.util.StreamFormatting.concatWithSpace
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatAudioCodec
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatSubtitleCodec
import com.github.damontecres.wholphin.ui.util.StreamFormatting.formatVideoRange
import com.github.damontecres.wholphin.ui.util.StreamFormatting.resolutionString
import com.github.damontecres.wholphin.util.languageName
import org.jellyfin.sdk.model.api.MediaType

/*
 * The slideshow's overlay and filter dialog as Views (no-Compose rewrite of `ImageOverlay`, `ImageDetailsHeader`,
 * `ImageControlsOverlay` and `ImageFilterSliders`, 2026-11-22).
 */

/**
 * `ExpandablePlayButton` / `ExpandableFaButton`: a 40dp button with an icon; the label is always shown (uppercase,
 * button font) on the WeaselTV theme and appears when focused elsewhere.
 */
internal class ExpandableButton(
    context: Context,
    accent: Int,
    @StringRes title: Int,
    iconRes: Int? = null,
    faGlyph: String? = null,
) : SurfaceFrameLayout(context) {
    private val label: TextView
    private val neon = AppTheme.isNeon
    private val icon: View

    init {
        isFocusable = true
        isClickable = true
        // The slideshow button is not Play/Resume/Restart, so it is an outline button on the WeaselTV theme too; off it,
        // the primary fallback is Button's own colours, which the outline style also is there
        background = SurfaceDrawable(OutlineButton.style(context, accent))
        val colors = OutlineButton.contentColors(accent)
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isDuplicateParentStateEnabled = true
            }
        icon =
            if (iconRes != null) {
                ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = colors
                    isDuplicateParentStateEnabled = true
                }
            } else {
                TextView(context).apply {
                    text = faGlyph
                    typeface = AppFonts.icons(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setTextColor(colors)
                    includeFontPadding = false
                    isDuplicateParentStateEnabled = true
                }
            }
        val iconBox = FrameLayout(context).apply { isDuplicateParentStateEnabled = true }
        val iconSize = if (iconRes != null) 28 else 32
        iconBox.addView(icon, FrameLayout.LayoutParams(context.dp(iconSize), context.dp(iconSize), Gravity.CENTER))
        row.addView(
            iconBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(40)).apply {
                if (iconRes != null) marginStart = context.dp(2)
            },
        )
        label =
            TextView(context).apply {
                text = if (neon) context.getString(title).uppercase() else context.getString(title)
                if (neon) PlaybackText.button(this) else applyTextRole(TextRole.TITLE_SMALL)
                setTextColor(colors)
                maxLines = 1
                includeFontPadding = false
                isDuplicateParentStateEnabled = true
            }
        val lp =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (neon) {
                    marginStart = context.dp(if (iconRes != null) 6 else 2)
                    marginEnd = context.dp(8)
                } else {
                    marginStart = context.dp(if (iconRes != null) 8 else 0)
                    marginEnd = context.dp(4)
                }
            }
        row.addView(label, lp)
        label.isVisible = neon
        val pad = context.dp(4)
        setPadding(pad, if (neon && iconRes == null) 0 else pad, pad, if (neon && iconRes == null) 0 else pad)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(40)))
        minimumWidth = context.dp(40)
        bindFocus(1.1f) { focused -> if (!neon) label.isVisible = focused }
    }

    fun setIcon(iconRes: Int) {
        (icon as? ImageView)?.setImageResource(iconRes)
    }

    fun setTitle(
        @StringRes title: Int,
    ) {
        label.text = if (neon) context.getString(title).uppercase() else context.getString(title)
    }
}

/** A small rounded label (`StreamLabel`): 12sp semibold on secondaryContainer 50 % */
internal fun streamLabel(
    context: Context,
    text: String,
    faIcon: String? = null,
    count: Int = 0,
    disabled: Boolean = false,
    maxWidthDp: Int? = null,
): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background =
            GradientDrawable().apply {
                setColor(withAlpha(AppTheme.palette.secondaryContainer, .5f))
                cornerRadius = context.dpF(4f)
            }
        setPadding(context.dp(6), context.dp(4), context.dp(6), context.dp(4))

        fun t(s: String) =
            TextView(context).apply {
                this.text = s
                setTextColor(AppTheme.palette.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AppFonts.body(context, 600)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        if (faIcon != null) {
            addView(t(faIcon).apply { typeface = AppFonts.icons(context) })
        }
        val countToUse = if (disabled) count else count - 1
        val label = t(if (countToUse > 0) "$text (+$countToUse)" else text)
        if (maxWidthDp != null) label.maxWidth = context.dp(maxWidthDp)
        addView(
            label,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (faIcon != null) marginStart = context.dp(2)
            },
        )
    }

/**
 * The slideshow overlay (`ImageOverlay` + `ImageDetailsHeader` + `ImageControlsOverlay`): title, quick details,
 * position and size labels, the description, and the controls row (slideshow, then rotate / zoom / reset / filter
 * for a photo or play/pause for a clip). 16dp padding on 50 % black.
 */
internal class ImageOverlayView(
    context: Context,
    private val accent: Int,
    private val displayToggles: Set<DisplayToggle>,
    private val callbacks: Callbacks,
) : LinearLayout(context) {
    /** What the overlay's buttons do */
    interface Callbacks {
        fun onSlideshowButton(enabled: Boolean)

        fun onRotate(degrees: Int)

        fun onZoom(factor: Float)

        fun onReset()

        fun onShowFilter()

        fun onPlayPause()
    }

    private val title: TextView
    private val quickDetails: TextView
    private val labels = LinearLayout(context)
    private val overview: TextView
    private val buttons = LinearLayout(context)
    private val buttonScroll = HorizontalScrollView(context)
    private var slideshowButton: ExpandableButton? = null
    private var playPauseButton: SurfaceFrameLayout? = null
    private var isClip: Boolean? = null

    init {
        orientation = VERTICAL
        noClip()
        setBackgroundColor(PlaybackColors.BLACK_50)
        val p16 = context.dp(16)
        setPadding(p16, p16, p16, p16)
        val p = AppTheme.palette
        title =
            context.textView(TextRole.HEADLINE_MEDIUM, p.onBackground, maxLines = 1, ellipsize = TextUtils.TruncateAt.END).apply {
                applyWeight(600)
            }
        addView(
            FractionWidthLayout(context, .75f).apply { addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) },
        )
        quickDetails = context.textView(TextRole.TITLE_SMALL, maxLines = 2, ellipsize = TextUtils.TruncateAt.END)
        addView(quickDetails, spaced())
        labels.orientation = HORIZONTAL
        addView(labels, spaced())
        val neon = AppTheme.isNeon
        overview =
            context.textView(TextRole.BODY_MEDIUM, maxLines = 3, ellipsize = TextUtils.TruncateAt.END).apply {
                isSingleLine = false
                val pad = context.dp(8)
                setPadding(pad, pad, pad, pad)
                if (!neon) {
                    // OverviewText off the WeaselTV theme: a focusable box, onPrimary 40 % when focused
                    height = context.dp(3 * 20) + pad * 2
                    isFocusable = true
                    background =
                        android.graphics.drawable.StateListDrawable().apply {
                            addState(
                                intArrayOf(android.R.attr.state_focused),
                                GradientDrawable().apply {
                                    setColor(withAlpha(p.onPrimary, .4f))
                                    cornerRadius = context.dpF(8f)
                                },
                            )
                        }
                }
            }
        addView(
            FractionWidthLayout(context, .75f).apply {
                addView(overview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            },
            spaced(),
        )
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.noClip()
        buttons.setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(12))
        buttonScroll.isHorizontalScrollBarEnabled = false
        buttonScroll.clipChildren = false
        buttonScroll.clipToPadding = false
        buttonScroll.addView(buttons)
        addView(
            buttonScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(8) - context.dp(12)
            },
        )
    }

    private fun spaced() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8) }

    fun bind(
        image: ImageState,
        position: Int,
        count: Int,
        slideshowEnabled: Boolean,
        isPlaying: Boolean,
    ) {
        val item = image.image
        title.text = item.title
        title.isVisible = item.title != null
        quickDetails.text = quickDetailsText(item.ui.quickDetailsText)
        quickDetails.isVisible = quickDetails.text.isNotEmpty()
        labels.removeAllViews()
        addLabel(streamLabel(context, "${position + 1} of $count"))
        val clip = item.data.mediaType == MediaType.VIDEO
        if (clip) {
            videoStreamLabels(image)
        } else {
            val w = item.data.width
            val h = item.data.height
            if (w != null && h != null) addLabel(streamLabel(context, "${w}x$h"))
        }
        overview.text = item.data.overview ?: ""
        if (clip != isClip) {
            isClip = clip
            buildButtons(clip, slideshowEnabled)
        }
        slideshowButton?.setTitle(if (slideshowEnabled) R.string.stop_slideshow else R.string.play_slideshow)
        slideshowButton?.setIcon(if (slideshowEnabled) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24)
        slideshowButton?.tag = slideshowEnabled
        setPlaying(isPlaying)
    }

    fun setPlaying(isPlaying: Boolean) {
        ((playPauseButton?.getChildAt(0)) as? ImageView)?.setImageResource(
            if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
        )
    }

    private fun addLabel(v: View) {
        labels.addView(
            v,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (labels.childCount >
                    0
                ) {
                    marginStart = context.dp(8)
                }
            },
        )
    }

    /** The quick details line: parts joined by " • ", then the ratings the display toggles allow */
    private fun quickDetailsText(q: com.github.damontecres.wholphin.data.model.QuickDetailsText): String =
        buildList {
            addAll(q.parts)
            if (DisplayToggle.OFFICIAL_RATING in displayToggles) q.officialRating?.let(::add)
            if (DisplayToggle.COMMUNITY_RATING in
                displayToggles
            ) {
                q.communityRating?.let { add("★ " + String.format(java.util.Locale.getDefault(), "%.1f", it)) }
            }
            if (DisplayToggle.CRITIC_RATING in displayToggles) q.criticRating?.let { add("${it.toInt()}%") }
        }.joinToString("  •  ")

    /** `VideoStreamDetails(chosenStreams, numberOfVersions = 0)` */
    private fun videoStreamLabels(image: ImageState) {
        val streams = image.chosenStreams ?: return
        val res = resources
        streams.videoStream?.let { v ->
            val w = v.width
            val h = v.height
            val resName = if (w != null && h != null) resolutionString(w, h, v.isInterlaced) else null
            val range = formatVideoRange(res, v.videoRange, v.videoRangeType, v.videoDoViTitle)
            resName.concatWithSpace(range)?.let { addLabel(streamLabel(context, it)) }
            v.codec?.uppercase()?.let { addLabel(streamLabel(context, it)) }
        }
        val source = streams.source
        val audioCount = source?.audioStreamCount ?: 0
        val audioStream = streams.audioStream
        val audio =
            if (audioCount == 0 || audioStream == null) {
                res.getString(R.string.none)
            } else {
                listOfNotNull(
                    languageName(audioStream.language),
                    formatAudioCodec(res, audioStream.codec, audioStream.profile),
                    audioStream.channelLayout,
                ).joinToString(" ")
            }
        addLabel(streamLabel(context, audio, context.getString(R.string.fa_volume_high), audioCount, maxWidthDp = 200))
        val subtitleCount = (source?.embeddedSubtitleCount ?: 0) + (source?.externalSubtitlesCount ?: 0)
        val sub = streams.subtitleStream
        if (subtitleCount > 0 && sub == null) {
            addLabel(
                streamLabel(
                    context,
                    res.getString(R.string.none),
                    context.getString(R.string.fa_closed_captioning),
                    subtitleCount,
                    disabled = true,
                    maxWidthDp = 160,
                ),
            )
        } else if (subtitleCount > 0 && sub != null) {
            val text =
                listOfNotNull(
                    languageName(sub.language),
                    "SDH".takeIf { sub.isHearingImpaired },
                    formatSubtitleCodec(sub.codec),
                ).joinToString(" ")
            addLabel(streamLabel(context, text, context.getString(R.string.fa_closed_captioning), subtitleCount, maxWidthDp = 160))
        }
    }

    private fun buildButtons(
        clip: Boolean,
        slideshowEnabled: Boolean,
    ) {
        buttons.removeAllViews()
        playPauseButton = null
        val slideshow =
            ExpandableButton(
                context,
                accent,
                if (slideshowEnabled) R.string.stop_slideshow else R.string.play_slideshow,
                iconRes = if (slideshowEnabled) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24,
            ).apply {
                tag = slideshowEnabled
                setOnClickListener { callbacks.onSlideshowButton(tag as Boolean) }
            }
        slideshowButton = slideshow
        addButton(slideshow)
        if (clip) {
            val b =
                SurfaceFrameLayout(context).apply {
                    isFocusable = true
                    isClickable = true
                    background = SurfaceDrawable(OutlineButton.style(context, accent))
                    // ButtonWithIconContentPadding
                    setPadding(context.dp(12), context.dp(10), context.dp(16), context.dp(10))
                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.baseline_play_arrow_24)
                            imageTintList = OutlineButton.contentColors(accent)
                            isDuplicateParentStateEnabled = true
                        },
                        LayoutParams(context.dp(24), context.dp(24)),
                    )
                    bindFocus(1.1f)
                    setOnClickListener { callbacks.onPlayPause() }
                }
            playPauseButton = b
            addButton(b)
        } else {
            addButton(
                ExpandableButton(context, accent, R.string.rotate_left, faGlyph = context.getString(R.string.fa_rotate_left)).apply {
                    setOnClickListener { callbacks.onRotate(-90) }
                },
            )
            addButton(
                ExpandableButton(
                    context,
                    accent,
                    R.string.rotate_right,
                    faGlyph = context.getString(R.string.fa_rotate_right),
                ).apply {
                    setOnClickListener { callbacks.onRotate(90) }
                },
            )
            addButton(
                ExpandableButton(
                    context,
                    accent,
                    R.string.zoom_in,
                    faGlyph = context.getString(R.string.fa_magnifying_glass_plus),
                ).apply {
                    setOnClickListener { callbacks.onZoom(.15f) }
                },
            )
            addButton(
                ExpandableButton(
                    context,
                    accent,
                    R.string.zoom_out,
                    faGlyph = context.getString(R.string.fa_magnifying_glass_minus),
                ).apply {
                    setOnClickListener { callbacks.onZoom(-.15f) }
                },
            )
            addButton(
                ExpandableButton(context, accent, R.string.reset, faGlyph = context.getString(R.string.fa_arrows_rotate)).apply {
                    setOnClickListener { callbacks.onReset() }
                },
            )
            addButton(
                ExpandableButton(context, accent, R.string.filter, faGlyph = context.getString(R.string.fa_sliders)).apply {
                    setOnClickListener { callbacks.onShowFilter() }
                },
            )
        }
    }

    private fun addButton(v: View) {
        buttons.addView(
            v,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (buttons.childCount >
                    0
                ) {
                    marginStart = context.dp(16)
                }
            },
        )
    }

    /** Focus the first button (the slideshow button), as the old controls row did when shown */
    fun focusFirst() {
        buttons.getChildAt(0)?.requestFocus()
    }
}

/**
 * The slider of the filter dialog (`SliderBar`): 6dp tall (12dp focused), LEFT/RIGHT step by 1 on key down with
 * wrap-around, OK re-sends the value.
 */
internal class FilterSliderView(
    context: Context,
    private val min: Int,
    private val max: Int,
    private val colors: IntArray?,
    private val accent: Int,
    private val onChange: (Int) -> Unit,
) : View(context) {
    var value: Int = min
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var barHeight = context.dpF(6f)

    init {
        isFocusable = true
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(12))
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        PlaybackAnim.value(barHeight, context.dpF(if (gainFocus) 12f else 6f), 200L) {
            barHeight = it
            invalidate()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        when {
            PlaybackKeys.isDpadLeft(event) -> {
                value = if (value <= min) max else (value - 1).coerceAtLeast(min)
                onChange(value)
            }

            PlaybackKeys.isDpadRight(event) -> {
                value = if (value >= max) min else (value + 1).coerceAtMost(max)
                onChange(value)
            }

            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                onChange(value)
            }

            else -> {
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (PlaybackKeys.isDpadLeft(event) || PlaybackKeys.isDpadRight(event) ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun activeColor(focused: Boolean): Int {
        colors?.let { return if (focused) it[0] else it[1] }
        val p = AppTheme.palette
        return when (AppTheme.theme) {
            AppThemeColors.WEASELTV -> accent
            AppThemeColors.OLED_BLACK -> if (focused) p.primaryContainer else p.border
            else -> p.border
        }
    }

    private fun inactiveColor(focused: Boolean): Int {
        colors?.let { return if (focused) it[2] else it[3] }
        val p = AppTheme.palette
        return when (AppTheme.theme) {
            AppThemeColors.UNRECOGNIZED, AppThemeColors.PURPLE -> {
                withAlpha(compositeOver(withAlpha(p.border, .25f), p.surfaceVariant), .66f)
            }

            AppThemeColors.OLED_BLACK -> {
                if (focused) p.tertiaryContainer else p.primaryContainer
            }

            AppThemeColors.WEASELTV -> {
                NeonColors.LINE2
            }

            else -> {
                withAlpha(p.secondaryContainer, .66f)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val inset = context.dpF(4f)
        val w = width - inset * 2
        val y = height / 2f
        val h = barHeight
        val percent = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(inset, 0f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h
        paint.color = inactiveColor(isFocused)
        canvas.drawLine(0f, y, w, y, paint)
        paint.color = activeColor(isFocused)
        canvas.drawLine(0f, y, w * percent, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(w * percent, y, h + 2, paint)
        canvas.restore()
    }
}

/**
 * The image filter dialog (`ImageFilterDialog` + `ImageFilterSliders`): top end, 40 % wide, at most 480dp tall:
 * brightness, contrast, saturation, red, green and blue sliders, then Save, Save for album and Reset.
 */
internal class ImageFilterDialog(
    context: Context,
    private val accent: Int,
    private val showVideoOptions: Boolean,
    private val showSaveGalleryButton: Boolean,
    private val onChange: (VideoFilter) -> Unit,
    private val onClickSave: () -> Unit,
    private val onClickSaveGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialog: Dialog = playbackDialog(context, Gravity.TOP or Gravity.END, ViewGroup.LayoutParams.MATCH_PARENT)
    private var filter = VideoFilter()
    private val sliders = mutableMapOf<String, Pair<FilterSliderView, TextView>>()

    init {
        val holder =
            FrameLayout(context).apply {
                clipChildren = false
            }
        val panel = basicDialogPanel(context, accent)
        val box =
            FrameLayout(context).apply {
                setBackgroundColor(withAlpha(AppTheme.palette.secondaryContainer, .4f))
            }
        val scroll = MaxHeightScrollView(context, context.dp(480) - context.dp(16))
        scroll.clipToPadding = false
        val list =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), context.dp(20), context.dp(16), context.dp(20))
                clipChildren = false
                clipToPadding = false
            }

        fun percent(v: Int) = "$v%"
        addRow(list, "brightness", R.string.brightness, 0, 200, null, ::percent) { filter.copy(brightness = it) }
        addRow(list, "contrast", R.string.contrast, 0, 200, null, ::percent) { filter.copy(contrast = it) }
        addRow(list, "saturation", R.string.saturation, 0, 200, null, ::percent) { filter.copy(saturation = it) }
        if (showVideoOptions) addRow(list, "hue", R.string.hue, 0, 360, null, { "$it°" }) { filter.copy(hue = it) }
        addRow(list, "red", R.string.red, 0, 200, channelColors(Color.RED), ::percent) { filter.copy(red = it) }
        addRow(list, "green", R.string.green, 0, 200, channelColors(Color.GREEN), ::percent) { filter.copy(green = it) }
        addRow(list, "blue", R.string.blue, 0, 200, channelColors(Color.BLUE), ::percent) { filter.copy(blue = it) }
        if (showVideoOptions) addRow(list, "blur", R.string.blur, 0, 250, null, { "${it}px" }) { filter.copy(blur = it) }
        val buttonRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
            }

        fun button(
            @StringRes text: Int,
            onClick: () -> Unit,
        ) {
            buttonRow.addView(
                OutlineButton.text(context, context.getString(text), accent, paddingDp = intArrayOf(4, 4, 4, 4), minHeightDp = 40).apply {
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (buttonRow.childCount > 0) marginStart = context.dp(8)
                },
            )
        }
        button(R.string.save, onClickSave)
        if (showSaveGalleryButton) button(R.string.save_for_album, onClickSaveGallery)
        button(R.string.reset) { onChange(VideoFilter()) }
        list.addView(
            buttonRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(8)
            },
        )
        scroll.addView(list)
        box.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val inner = FrameLayout(context).apply { setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8)) }
        inner.addView(box)
        panel.addView(inner)
        val width = (context.resources.displayMetrics.widthPixels * .4f).toInt()
        holder.addView(
            panel,
            FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
            },
        )
        dialog.setContentView(holder)
        dialog.setOnCancelListener { onDismiss() }
        dialog.setOnShowListener { sliders["brightness"]?.first?.requestFocus() }
    }

    private fun channelColors(c: Int) = intArrayOf(withAlpha(c, .75f), withAlpha(c, .75f), withAlpha(c, .33f), withAlpha(c, .33f))

    private fun addRow(
        list: LinearLayout,
        key: String,
        @StringRes title: Int,
        min: Int,
        max: Int,
        colors: IntArray?,
        format: (Int) -> String,
        update: (Int) -> VideoFilter,
    ) {
        val ctx = list.context
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ctx.textView(TextRole.BODY_LARGE).apply { text = ctx.getString(title) })
        val row =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val valueText = ctx.textView(TextRole.BODY_LARGE)
        val slider = FilterSliderView(ctx, min, max, colors, accent) { onChange(update(it)) }
        row.addView(slider, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            valueText,
            LinearLayout.LayoutParams(ctx.dp(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8) },
        )
        column.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    ctx.dp(8)
            },
        )
        list.addView(
            column,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (list.childCount > 0) topMargin = ctx.dp(8)
            },
        )
        sliders[key] = slider to valueText
        valueText.tag = format
    }

    /** Show the current filter values */
    @Suppress("UNCHECKED_CAST")
    fun bind(newFilter: VideoFilter) {
        filter = newFilter
        val values =
            mapOf(
                "brightness" to newFilter.brightness,
                "contrast" to newFilter.contrast,
                "saturation" to newFilter.saturation,
                "hue" to newFilter.hue,
                "red" to newFilter.red,
                "green" to newFilter.green,
                "blue" to newFilter.blue,
                "blur" to newFilter.blur,
            )
        sliders.forEach { (key, pair) ->
            val v = values[key] ?: return@forEach
            pair.first.value = v
            pair.second.text = (pair.second.tag as (Int) -> String)(v)
        }
    }

    fun show() = dialog.show()
}
