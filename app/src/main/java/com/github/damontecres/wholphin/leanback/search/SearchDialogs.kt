package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuListView
import com.github.damontecres.wholphin.leanback.widget.text.NeonRuleView
import com.github.damontecres.wholphin.ui.search.PrimarySearchTypes
import com.github.damontecres.wholphin.ui.titleStringRes
import org.jellyfin.sdk.model.api.BaseItemKind

/** A list row with a switch at its end (the dialogs' `ListItem` + `Switch(colors = SwitchColors())`) */
internal class SwitchListItem(
    context: Context,
) : AppListItem(context) {
    val switch = SearchSwitchView(context)

    init {
        setTrailingView(switch)
    }
}

/**
 * `SearchViewOptionsDialog`: a 400dp panel (24dp padding, 16dp between entries) with the title (WeaselTV: uppercase
 * dialog type), "Combined search results" and "Show voice search button" with their switches and current value, and
 * "Include types", which opens [SearchTypeOptionsDialog] on top. Call [update] when the preferences change.
 */
internal class SearchViewOptionsDialog(
    context: Context,
    opener: View?,
    combinedResults: Boolean,
    voiceSearchButtonVisible: Boolean,
    private val onCombinedResultsChange: (Boolean) -> Unit,
    private val onVoiceSearchButtonVisibleChange: (Boolean) -> Unit,
    onClickFilterTypes: () -> Unit,
) : AppDialog(context, DialogOptions(usePlatformDefaultWidth = false, elevationDp = 3f), opener = opener) {
    private val combined = SwitchListItem(context)
    private val voice = SwitchListItem(context)
    private var combinedValue = combinedResults
    private var voiceValue = voiceSearchButtonVisible

    init {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                val pad = context.dp(24)
                setPadding(pad, pad, pad, pad)
            }
        val neon = AppTheme.isNeon
        column.addView(
            TextView(context).apply {
                val title = context.getString(R.string.view_options)
                text = if (neon) title.uppercase() else title
                if (neon) applyTextStyle(NeonType.dialogTitle()) else applyTextRole(TextRole.HEADLINE_SMALL)
                setTextColor(AppTheme.palette.onSurface)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        combined.setHeadline(context.getString(R.string.combined_search_results))
        combined.setOnClickListener { onCombinedResultsChange(!combinedValue) }
        voice.setHeadline(context.getString(R.string.show_voice_search_button))
        voice.setOnClickListener { onVoiceSearchButtonVisibleChange(!voiceValue) }
        val types =
            AppListItem(context).apply {
                setHeadline(context.getString(R.string.include_types))
                setOnClickListener { onClickFilterTypes() }
            }
        listOf(combined, voice, types).forEach { row ->
            column.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(16)
                },
            )
        }
        update(combinedResults, voiceSearchButtonVisible)
        setPanelContent(column, FrameLayout.LayoutParams(context.dp(400), ViewGroup.LayoutParams.WRAP_CONTENT))
        combined.requestFocus()
    }

    fun update(
        combinedResults: Boolean,
        voiceSearchButtonVisible: Boolean,
    ) {
        combinedValue = combinedResults
        voiceValue = voiceSearchButtonVisible
        combined.setSupporting(
            context.getString(if (combinedResults) R.string.combined_search_results_on else R.string.combined_search_results_off),
        )
        combined.switch.checked = combinedResults
        voice.setSupporting(context.getString(if (voiceSearchButtonVisible) R.string.visible_ui else R.string.hidden_ui))
        voice.switch.checked = voiceSearchButtonVisible
    }
}

/**
 * `SearchTypeOptionsDialog`: the platform-width panel (16dp padding, elevation 3) with "Include types" (WeaselTV:
 * dialog type and a rule sized like the first Home row's divider) and a scrolling list (WeaselTV: 32dp side insets;
 * 20dp clear at the ends) of the searchable types, then Requests when Seerr is set up, each with its switch.
 * Call [update] when the choices change.
 */
internal class SearchTypeOptionsDialog(
    context: Context,
    opener: View?,
    pageStyle: PageStyle,
    private val searchableTypes: List<BaseItemKind>,
    excludedSearchableTypes: List<BaseItemKind>,
    private val discoverAvailable: Boolean,
    discoverEnabled: Boolean,
    private val onClick: (BaseItemKind) -> Unit,
    private val onClickDiscover: () -> Unit,
) : AppDialog(context, DialogOptions(elevationDp = 3f), opener = opener) {
    private val typeRows = mutableListOf<Pair<BaseItemKind, SwitchListItem>>()
    private var discoverRow: SwitchListItem? = null

    init {
        val neon = AppTheme.isNeon
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                val pad = context.dp(16)
                setPadding(pad, pad, pad, pad)
            }
        if (neon) {
            column.addView(
                TextView(context).apply {
                    setText(R.string.include_types)
                    applyTextStyle(NeonType.dialogTitle())
                    setTextColor(AppTheme.palette.onSurface)
                    setPaddingRelative(context.dp(16), 0, context.dp(16), 0)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            val rule =
                pageStyle.homeMediaSettings.rows
                    .firstOrNull()
                    ?.config
                    ?.viewOptions
                    ?: ApprovedHomeLayout.apply(HomeRowViewOptions())
            column.addView(
                NeonRuleView(context).apply {
                    thicknessDp = rule.dividerThicknessDp.toFloat()
                    glowHeightDp = rule.dividerGlowDp.toFloat()
                    glowStrength = rule.dividerGlowStrength / 100f
                    ruleAccent = accent
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8)
                },
            )
        } else {
            // TitleText: titleLarge, 8dp above and 4dp below
            column.addView(
                TextView(context).apply {
                    setText(R.string.include_types)
                    applyTextRole(TextRole.TITLE_LARGE)
                    setTextColor(AppTheme.palette.onSurface)
                    setPadding(0, context.dp(8), 0, context.dp(4))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val items =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val side = if (neon) context.dp(32) else 0

        fun addRow(row: SwitchListItem) {
            items.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = side
                    marginEnd = side
                },
            )
        }
        searchableTypes.forEach { type ->
            val row =
                SwitchListItem(context).apply {
                    setHeadline(context.getString(type.titleStringRes))
                    setOnClickListener { onClick(type) }
                }
            typeRows.add(type to row)
            addRow(row)
        }
        if (discoverAvailable) {
            discoverRow =
                SwitchListItem(context)
                    .apply {
                        setHeadline(context.getString(R.string.discover))
                        setOnClickListener { onClickDiscover() }
                    }.also { addRow(it) }
        }
        val list =
            MenuListView(context).apply {
                val edge = context.dp(MenuDialog.DIALOG_LIST_EDGE_DP)
                setPadding(0, edge, 0, edge)
                addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        column.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        update(excludedSearchableTypes, discoverEnabled)
        setPanelContent(column)
        (typeRows.firstOrNull()?.second ?: discoverRow)?.requestFocus()
    }

    fun update(
        excludedSearchableTypes: List<BaseItemKind>,
        discoverEnabled: Boolean,
    ) {
        typeRows.forEach { (type, row) -> row.switch.checked = type !in excludedSearchableTypes }
        discoverRow?.switch?.checked = discoverEnabled
    }
}

/**
 * `SearchTypeToggleRow` (WeaselTV): one button per primary search type (Movies, Shows, Episodes, People) that the
 * user's libraries offer, sharing the width 16dp apart, each with the type name (labelLarge) and its switch centred
 * 8dp apart; OK includes or excludes the type. 16dp side padding, 4dp above and 20dp below, plus 16dp more at the end.
 */
internal class SearchTypeToggleRow(
    context: Context,
    private val onClick: (BaseItemKind) -> Unit,
) : LinearLayout(context) {
    private val buttons = mutableListOf<Triple<BaseItemKind, AppButton, SearchSwitchView>>()
    private var shownTypes: List<BaseItemKind> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPaddingRelative(context.dp(16), context.dp(4), context.dp(16 + 16), context.dp(20))
    }

    fun bind(
        searchableTypes: List<BaseItemKind>,
        excludedSearchableTypes: List<BaseItemKind>,
    ) {
        val types = PrimarySearchTypes.filter { it in searchableTypes }
        if (types != shownTypes) {
            shownTypes = types
            removeAllViews()
            buttons.clear()
            types.forEachIndexed { index, type ->
                val switch = SearchSwitchView(context)
                val button =
                    AppButton(context).apply {
                        setContentPaddingDp(12f, 0f, 12f, 0f)
                        labelView.applyTextRole(TextRole.LABEL_LARGE)
                        setLabel(type.titleStringRes)
                        addView(switch, LayoutParams(context.dp(40), context.dp(24)).apply { marginStart = context.dp(8) })
                        setOnClickListener { onClick(type) }
                    }
                buttons.add(Triple(type, button, switch))
                addView(
                    button,
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = context.dp(16)
                    },
                )
            }
        }
        buttons.forEach { (type, _, switch) -> switch.checked = type !in excludedSearchableTypes }
    }
}
