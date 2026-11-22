package com.github.damontecres.wholphin.leanback.playback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import coil3.load
import coil3.request.Disposable
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.abbreviateNumber
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.ui.playback.SubtitleSearchStatus
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import java.time.LocalDateTime
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The next up panel (`NextUpEpisode` + `NextUpCard`), no-Compose: "Next up..." over the episode card (with the
 * countdown or a play icon in its centre) and the title, description and "runtime • Ends at" beside it, on a rounded
 * elevated surface.
 */
internal class NextUpView(
    context: Context,
    appearance: HomeCardAppearance,
    accent: Int,
) : FrameLayout(context) {
    val card =
        CardFrame(
            context,
            CardFocus.style(context, appearance, accent, AppTheme.palette.surfaceVariant),
            CardFocus.scale(appearance),
        )
    private val image = ImageView(context)
    private val countdown: TextView
    private val playIcon = ImageView(context)
    private val title: TextView
    private val description: TextView
    private val runtimeText: TextView
    private val row = LinearLayout(context)
    private var aspectRatio = AspectRatios.WIDE
    private var runtime: Duration? = null
    private var request: Disposable? = null
    private var ticking = false

    private val clockTicker =
        object : Runnable {
            override fun run() {
                if (!ticking) return
                updateRuntime()
                postDelayed(this, 2_000L)
            }
        }

    init {
        noClip()
        background =
            GradientDrawable().apply {
                setColor(PlaybackColors.surfaceAtElevation(2f))
                cornerRadius = context.dpF(8f)
            }
        val column =
            LinearLayout(context)
                .apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
                }.also { it.noClip() }
        val heading =
            context.textView(TextRole.TITLE_LARGE).apply {
                text = context.getString(R.string.next_up) + "..."
                gravity = Gravity.CENTER_HORIZONTAL
            }
        column.addView(heading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        row.noClip()
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        card.content.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val circle =
            FrameLayout(context).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PlaybackColors.BLACK_50)
                    }
            }
        countdown =
            context.textView(TextRole.TITLE_MEDIUM).apply {
                val pad = context.dp(12)
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER
            }
        circle.addView(countdown, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        playIcon.setImageResource(R.drawable.baseline_play_arrow_24)
        playIcon.imageTintList = ColorStateList.valueOf(AppTheme.palette.onSurface)
        circle.addView(playIcon, LayoutParams(context.dp(60), context.dp(60), Gravity.CENTER))
        card.content.addView(circle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title = context.textView(TextRole.TITLE_MEDIUM, maxLines = 2, ellipsize = TextUtils.TruncateAt.END).apply { isSingleLine = false }
        description =
            context.textView(TextRole.BODY_MEDIUM, maxLines = 4, ellipsize = TextUtils.TruncateAt.END).apply { isSingleLine = false }
        runtimeText = context.textView(TextRole.BODY_SMALL, maxLines = 1, ellipsize = TextUtils.TruncateAt.END)
        texts.addView(title)
        texts.addView(
            description,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(8)
            },
        )
        texts.addView(
            runtimeText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(8 + 4)
            },
        )
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = context.dp(32) })
        column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) })
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        titleText: String,
        descriptionText: String?,
        imageUrl: String?,
        aspect: Float,
        runtime: Duration?,
    ) {
        title.text = titleText
        description.text = descriptionText.orEmpty()
        aspectRatio = aspect.coerceAtLeast(AspectRatios.MIN)
        this.runtime = runtime
        runtimeText.isVisible = runtime != null
        updateRuntime()
        request?.dispose()
        request = image.load(imageUrl)
        requestLayout()
    }

    /** The countdown (as `Duration.toString()`, eg "10s") or the play icon */
    fun setTimeLeft(timeLeft: Duration?) {
        val show = timeLeft != null && timeLeft > Duration.ZERO
        countdown.isVisible = show
        playIcon.isVisible = !show
        if (show) countdown.text = timeLeft.toString()
    }

    private fun updateRuntime() {
        val r = runtime ?: return
        val endsAt = LocalDateTime.now().plus(r.toJavaDuration())
        runtimeText.text =
            resources.formatDuration(r) + "  •  " + resources.getString(R.string.ends_at, formatTime(context, endsAt))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticking = true
        clockTicker.run()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ticking = false
        removeCallbacks(clockTicker)
        request?.dispose()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // The card fills the row's height at the item's aspect ratio (fillMaxHeight().aspectRatio(..))
        val h = row.measuredHeight - row.paddingTop - row.paddingBottom
        if (h > 0) {
            val w = (h * aspectRatio).toInt()
            val lp = card.layoutParams
            if (lp.width != w) {
                lp.width = w
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }
}

/**
 * The subtitle search panel (`DownloadSubtitlesContent`), no-Compose: searching / downloading, the error, or the
 * language field and the results (hash match, provider, forced, SDH, downloads, rating).
 */
internal class DownloadSubtitlesView(
    context: Context,
    private val accent: Int,
    private val onSearch: (String) -> Unit,
    private val onClickDownload: (RemoteSubtitleInfo) -> Unit,
    private val onSendLogs: () -> Unit,
) : FrameLayout(context) {
    private var language: String? = null
    private var status: SubtitleSearchStatus? = null

    fun bind(
        newStatus: SubtitleSearchStatus,
        lang: String,
    ) {
        if (newStatus == status) return
        status = newStatus
        if (language == null) language = lang
        removeAllViews()
        when (newStatus) {
            SubtitleSearchStatus.Inactive -> {}

            SubtitleSearchStatus.Searching -> {
                addView(context.textView(TextRole.TITLE_LARGE).apply { text = context.getString(R.string.searching) })
            }

            SubtitleSearchStatus.Downloading -> {
                addView(context.textView(TextRole.TITLE_LARGE).apply { text = context.getString(R.string.downloading) })
            }

            is SubtitleSearchStatus.Error -> {
                addView(PlaybackErrorView(context, null, newStatus.ex, accent, onSendLogs).also { post { it.requestFocus() } })
            }

            is SubtitleSearchStatus.Success -> {
                addView(success(newStatus.options))
            }
        }
    }

    private fun success(options: List<RemoteSubtitleInfo>): View {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title =
            context.textView().apply {
                text = PlaybackText.upper(context.getString(R.string.search_and_download_subtitles))
                if (AppTheme.isNeon) PlaybackText.dialogTitle(this) else PlaybackText.role(this, TextRole.TITLE_LARGE)
            }
        column.addView(title)
        val langRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        langRow.addView(context.textView(TextRole.TITLE_SMALL).apply { text = context.getString(R.string.language) })
        val edit = languageField()
        langRow.addView(
            edit,
            LinearLayout.LayoutParams(context.dp(240), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart =
                    context.dp(8)
            },
        )
        column.addView(
            langRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    context.dp(8)
            },
        )
        if (options.isEmpty()) {
            column.addView(
                context.textView(TextRole.TITLE_LARGE).apply { text = context.getString(R.string.no_subtitles_found) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin =
                        context.dp(8)
                },
            )
            post { edit.requestFocus() }
        } else {
            val scroll = MaxHeightScrollView(context, context.dp(400))
            scroll.clipToPadding = false
            val list =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = context.dp(20)
                    setPadding(pad, pad, pad, pad)
                    clipChildren = false
                    clipToPadding = false
                }
            var first: View? = null
            options.forEach { info ->
                val item = subtitleItem(info)
                list.addView(
                    item,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(8)
                        bottomMargin = context.dp(8)
                    },
                )
                if (first == null) first = item
            }
            scroll.addView(list)
            column.addView(
                scroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin =
                        context.dp(8)
                },
            )
            post { first?.requestFocus() }
        }
        return column
    }

    private fun languageField(): EditText =
        EditText(context).apply {
            setText(language)
            setTextColor(AppTheme.palette.onSurface)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background =
                GradientDrawable().apply {
                    setColor(if (AppTheme.isNeon) NeonColors.WELL else AppTheme.palette.surfaceVariant)
                    setStroke(context.dp(1), if (AppTheme.isNeon) NeonColors.LINE2 else AppTheme.palette.border)
                    cornerRadius = if (AppTheme.isNeon) 0f else context.dpF(4f)
                }
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
            addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {}

                    override fun afterTextChanged(s: android.text.Editable?) {
                        language = s?.toString()
                    }
                },
            )
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                ) {
                    onSearch(language.orEmpty())
                    true
                } else {
                    false
                }
            }
        }

    private fun subtitleItem(info: RemoteSubtitleInfo): View {
        val item = PlaybackListItem(context, accent, showLeading = false)
        val details =
            buildList {
                info.providerName?.let(::add)
                if (info.forced == true) add(resources.getString(R.string.forced_track))
                if (info.hearingImpaired == true) add(resources.getString(R.string.subtitles_hearing_impaired))
                add(resources.getQuantityString(R.plurals.downloads, info.downloadCount ?: 0, abbreviateNumber(info.downloadCount ?: 0)))
            }.joinToString(" - ")
        item.bind(
            headlineText = info.name ?: "",
            supportingText = details,
            overlineText = if (info.isHashMatch == true) context.getString(R.string.hash_matches) else null,
        )
        item.headline.maxLines = 3
        item.headline.isSingleLine = false
        item.headline.ellipsize = TextUtils.TruncateAt.END
        info.communityRating?.let { rating ->
            item.trailing.addView(
                context.textView(TextRole.LABEL_LARGE).apply {
                    text = rating.toString()
                    isDuplicateParentStateEnabled = true
                },
            )
            item.trailing.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.lb_playback_ic_star)
                    imageTintList = ColorStateList.valueOf(PlaybackColors.GOLDEN_YELLOW)
                },
                LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginStart = context.dp(4) },
            )
        }
        item.setOnClickListener { onClickDownload(info) }
        return item
    }

    companion object {
        /** The dialog around it: BasicDialog with 24dp padding, at most 640x400 */
        fun panel(
            context: Context,
            accent: Int,
            content: DownloadSubtitlesView,
        ): FrameLayout =
            basicDialogPanel(context, accent).apply {
                val pad = context.dp(24)
                setPadding(pad, pad, pad, pad)
                addView(
                    content,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
                )
                content.maxWidthPx = context.dp(640)
                content.maxHeightPx = context.dp(400)
            }
    }

    var maxWidthPx: Int = Int.MAX_VALUE
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec).let { if (it == 0) maxWidthPx else minOf(it, maxWidthPx) }
        val h = MeasureSpec.getSize(heightMeasureSpec).let { if (it == 0) maxHeightPx else minOf(it, maxHeightPx) }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }
}
