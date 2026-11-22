package com.github.damontecres.wholphin.leanback.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonGlowSpec
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyNeonEyebrow
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.text.FittedTitleView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.leanback.widget.text.TimeDisplayView

/*
 * Pieces shared by this area's grid pages (MoreHomeRow, Watchlist, Favorites): private versions of LIBRARY's
 * `GridTitle` and `ExpandableFaButton` (runbooks/no-compose/CONTRACT.md section 1: LIBRARY's View versions are not in
 * this checkout).
 */

/**
 * `GridTitle`. WeaselTV: the page head (eyebrow, default "Library", 16dp in; the uppercase 40sp title fitted to the
 * width left of the clock; the section rule 6dp below). [collectionsOptions] (the Watchlist / Collections head) puts
 * it 27dp from the top with no inset and takes its rule from those options. Other themes: displaySmall, centred, one
 * line.
 */
class GridTitleView(
    context: Context,
) : LinearLayout(context) {
    private val eyebrow = AppCompatTextView(context)
    private val title = FittedTitleView(context, maxLines = 3)
    private val rule = NeonRuleView(context)
    private val plain = AppCompatTextView(context)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    fun bind(
        titleText: String,
        accent: Int,
        eyebrowText: String? = null,
        collectionsOptions: HomeRowViewOptions? = null,
    ) {
        removeAllViews()
        if (!AppTheme.isNeon) {
            plain.applyTextRole(TextRole.DISPLAY_SMALL)
            plain.setTextColor(AppTheme.palette.onBackground)
            plain.gravity = Gravity.CENTER_HORIZONTAL
            plain.maxLines = 1
            plain.ellipsize = TextUtils.TruncateAt.END
            plain.text = titleText
            addView(plain, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        val inset = if (collectionsOptions != null) 0 else context.dp(16)
        setPadding(0, if (collectionsOptions != null) context.dp(27) else 0, 0, 0)
        eyebrow.applyNeonEyebrow(eyebrowText ?: context.getString(R.string.library), accent)
        addView(
            eyebrow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
            },
        )
        title.setTextColor(AppTheme.palette.onSurface)
        title.setStyledText(titleText.uppercase(), NeonType.pageTitle())
        addView(
            title,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = TimeDisplayView.headerEndPaddingPx(context)
                topMargin = context.dp(2)
            },
        )
        rule.section = true
        rule.ruleAccent = accent
        rule.thicknessDp = collectionsOptions?.dividerThicknessDp?.toFloat() ?: NeonSizes.RULE
        rule.glowHeightDp = collectionsOptions?.dividerGlowDp?.toFloat() ?: NeonGlowSpec.RULE
        rule.glowStrength = collectionsOptions?.dividerGlowStrength?.div(100f) ?: .35f
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
    }
}

/**
 * `ExpandableFaButton`: a Font Awesome glyph (16sp in 32dp) and its label in a 40dp button. WeaselTV (`allCaps`): the
 * uppercase label always shows; other themes: the label shows while focused.
 */
fun faButton(
    context: Context,
    @StringRes title: Int,
    @StringRes glyph: Int,
    allCaps: Boolean = AppTheme.isNeon,
    onClick: () -> Unit,
): AppButton =
    AppButton(context).apply {
        setGlyph(glyph)
        contentHeightDp = 40f
        exactContentHeight = true
        minimumWidth = context.dp(40)
        if (allCaps) {
            setContentPaddingDp(4f, 0f, 4f, 0f)
            setLabel(title, allCaps = true)
            setLabelPaddingDp(2f, 8f)
        } else {
            setLabel(title)
            setLabelPaddingDp(0f, 4f)
            labelOnlyWhenFocused = true
        }
        setOnClickListener { onClick() }
    }

/** Shows [view] only while [visible] (GONE otherwise, so focus search skips it) */
internal fun View.showIf(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
