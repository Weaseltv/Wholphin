package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.playOnClickSound
import java.util.Locale

/**
 * An item overview (`OverviewText`): [maxLines] lines (fewer, [compactMaxLines], in a compact detail header) with an
 * ellipsis, 8dp padding, bodyMedium onSurface by default.
 *
 * On the WeaselTV theme it is plain text: not focusable, not clickable, measured naturally (owner rule: descriptions
 * cannot be selected or opened in a popup). Other themes keep the old clickable box: `maxLines * 20dp` tall, focusable,
 * a translucent onPrimary fill with 8dp corners when focused, the focus and click sounds, and [onClick] on OK.
 */
class OverviewTextView(
    context: Context,
) : LinearLayout(context) {
    val textView: TextView =
        TextView(context).apply {
            applyTextRole(TextRole.BODY_MEDIUM)
            ellipsize = TextUtils.TruncateAt.END
            val p = context.dp(8)
            setPadding(p, p, p, p)
        }
    private val neon = AppTheme.isNeon
    private val focusFill = GradientDrawable().apply { cornerRadius = context.dpF(8f) }

    var maxLines: Int = 3
        set(value) {
            field = value
            refresh()
        }

    /** Lines shown when the header is compact (the kid page shows more of its description) */
    var compactMaxLines: Int = 2
        set(value) {
            field = value
            refresh()
        }

    /** The detail header is compact (`LocalCompactDetailHeader`) */
    var compact: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    /** Stock themes: the text box height in dp (default `maxLines * 20dp`) */
    var textBoxHeightDp: Float? = null
        set(value) {
            field = value
            refresh()
        }

    var onClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        addView(textView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textView.setTextColor(AppTheme.palette.onSurface)
        if (!neon) {
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            background = focusFill
            setOnClickListener {
                playOnClickSound(context)
                onClick?.invoke()
            }
        }
        refresh()
    }

    fun setText(text: CharSequence?) {
        textView.text = text
    }

    fun setTextStyle(style: ControlTextStyle) = textView.applyTextStyle(style)

    private fun refresh() {
        textView.maxLines = if (compact) minOf(maxLines, compactMaxLines) else maxLines
        if (!neon) {
            val h = context.dp(textBoxHeightDp ?: (maxLines * 20f))
            textView.layoutParams = textView.layoutParams.apply { height = h + textView.paddingTop + textView.paddingBottom }
        }
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (neon) return
        focusFill.setColor(if (gainFocus) withAlpha(AppTheme.palette.onPrimary, .4f) else 0)
        if (gainFocus) {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP)
        }
    }
}

/**
 * A key/value pair stacked vertically (`TitleValueText`): the title in labelMedium (regular weight) at 80 %, the
 * value in labelLarge (regular) on one line with an ellipsis, both onSurface.
 *
 * With [onClick] / [onLongClick] it is focusable: a translucent onPrimary fill with 4dp corners and a 1.1 scale when
 * focused; with [playSoundOnFocus] it plays the click sound on focus and on click.
 */
class TitleValueTextView(
    context: Context,
) : LinearLayout(context) {
    val titleView: TextView =
        TextView(context).apply {
            applyTextRole(TextRole.LABEL_MEDIUM, weight = 400)
            setTextColor(AppTheme.palette.onSurface)
            alpha = .8f
        }
    val valueView: TextView =
        TextView(context).apply {
            applyTextRole(TextRole.LABEL_LARGE, weight = 400)
            setTextColor(AppTheme.palette.onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val focusFill = GradientDrawable().apply { cornerRadius = context.dpF(4f) }

    var playSoundOnFocus: Boolean = false

    var onClick: (() -> Unit)? = null
        set(value) {
            field = value
            updateClickable()
        }

    var onLongClick: (() -> Unit)? = null
        set(value) {
            field = value
            updateClickable()
        }

    init {
        orientation = VERTICAL
        addView(titleView)
        addView(valueView)
        isSoundEffectsEnabled = false
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setOnClickListener {
            if (playSoundOnFocus) playOnClickSound(context)
            onClick?.invoke()
        }
        setOnLongClickListener {
            if (playSoundOnFocus) playOnClickSound(context)
            onLongClick?.invoke()
            true
        }
        updateClickable()
    }

    fun set(
        title: String,
        value: String,
    ) {
        titleView.text = title
        valueView.text = value
    }

    private fun updateClickable() {
        val clickable = onClick != null || onLongClick != null
        isFocusable = clickable
        isClickable = clickable
        isLongClickable = clickable
        background = if (clickable) focusFill else null
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focusFill.setColor(if (gainFocus) withAlpha(AppTheme.palette.onPrimary, .75f) else 0)
        scaleX = if (gainFocus) 1.1f else 1f
        scaleY = scaleX
        if (gainFocus && playSoundOnFocus) playOnClickSound(context)
    }
}

/**
 * A comma separated list of genres (`GenreText`): the first four, then ", +N" for the rest; bodyMedium onSurface by
 * default; one line with an ellipsis in a compact detail header.
 */
class GenreTextView(
    context: Context,
) : androidx.appcompat.widget.AppCompatTextView(context) {
    /** The detail header is compact (`LocalCompactDetailHeader`) */
    var compact: Boolean = false
        set(value) {
            field = value
            maxLines = if (value) 1 else Int.MAX_VALUE
        }

    init {
        applyTextRole(TextRole.BODY_MEDIUM)
        setTextColor(AppTheme.palette.onSurface)
        ellipsize = TextUtils.TruncateAt.END
    }

    fun setGenres(genres: List<String>) {
        text = genreText(genres)
    }

    companion object {
        private const val MAX_TO_SHOW = 4

        fun genreText(genres: List<String>): String =
            if (genres.size <= MAX_TO_SHOW) {
                genres.joinToString(", ")
            } else {
                genres.subList(0, MAX_TO_SHOW).joinToString(", ") + ", +${genres.size - MAX_TO_SHOW}"
            }
    }
}

/**
 * A community rating (`SimpleStarRating`): the rating with one decimal, 2dp, then a star the text's size in
 * [starColor] (yellow), onSurface text. Nothing is shown for a blank rating.
 */
class SimpleStarRatingView(
    context: Context,
) : androidx.appcompat.widget.AppCompatTextView(context) {
    var starColor: Int = FILLED_STAR_COLOR
        set(value) {
            field = value
            render()
        }
    private var rating: String? = null

    init {
        setTextColor(AppTheme.palette.onSurface)
        applyTextRole(TextRole.BODY_LARGE)
        maxLines = 1
    }

    fun setRating(communityRating: Float?) = setRatingText(communityRating?.let { String.format(Locale.getDefault(), "%.1f", it) })

    fun setRatingText(text: String?) {
        rating = text
        render()
    }

    private fun render() {
        val r = rating
        if (!r.isNotNullOrBlank()) {
            text = ""
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        text =
            SpannableStringBuilder(r).apply {
                append(" ")
                appendIcon(context, R.drawable.lb_widget_ctl_ic_star, starColor)
            }
    }
}

/**
 * A critic rating (`TomatoRating`): "N%" (onSurface), 2dp, then the fresh tomato at [threshold] (60) and above or the
 * splat below, 80 % of the text size. Nothing for a null rating.
 */
class TomatoRatingView(
    context: Context,
) : androidx.appcompat.widget.AppCompatTextView(context) {
    var threshold: Float = 60f

    init {
        setTextColor(AppTheme.palette.onSurface)
        applyTextRole(TextRole.BODY_LARGE)
        maxLines = 1
    }

    fun setRating(rating: Float?) {
        if (rating == null) {
            text = ""
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        text =
            SpannableStringBuilder("${rating.toInt()}%").apply {
                append(" ")
                appendIcon(context, if (rating >= threshold) R.drawable.ic_rotten_tomatoes_fresh else R.drawable.ic_rotten_tomatoes_rotten)
            }
    }
}

/**
 * A key/value table row (`TableRowComposable`): the key ("Key:") in 30 % and the value in 70 % of the width, 4dp
 * padding each, bodyLarge onBackground. Focusable (so long tables scroll); with [onClick] clickable, filled with the
 * border colour at 75 % when focused, otherwise onBackground at 25 %.
 */
class TableRowView(
    context: Context,
    key: String,
    value: String,
    private val onClick: (() -> Unit)? = null,
    keyWeight: Float = .3f,
    valueWeight: Float = .7f,
    focusable: Boolean = true,
) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        isFocusable = focusable || onClick != null
        isClickable = onClick != null
        isSoundEffectsEnabled = false
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        onClick?.let { click -> setOnClickListener { click() } }
        val color = AppTheme.palette.onBackground

        fun cell(text: String) =
            TextView(context).apply {
                this.text = text
                applyTextRole(TextRole.BODY_LARGE)
                setTextColor(color)
                val p = context.dp(4)
                setPadding(p, p, p, p)
            }
        addView(cell("$key:"), LayoutParams(0, LayoutParams.WRAP_CONTENT, keyWeight))
        addView(cell(value), LayoutParams(0, LayoutParams.WRAP_CONTENT, valueWeight))
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val p = AppTheme.palette
        setBackgroundColor(
            when {
                gainFocus && onClick != null -> withAlpha(p.border, .75f)
                gainFocus -> withAlpha(p.onBackground, .25f)
                else -> 0
            },
        )
    }

    companion object {
        /** A row only when [value] is not blank (`TableRow.from`) */
        fun from(
            context: Context,
            key: String,
            value: String?,
            onClick: (() -> Unit)? = null,
        ): TableRowView? = if (value.isNotNullOrBlank()) TableRowView(context, key, value!!, onClick) else null
    }
}
