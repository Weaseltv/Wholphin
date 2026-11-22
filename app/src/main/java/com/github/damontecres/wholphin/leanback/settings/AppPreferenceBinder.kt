package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import androidx.preference.Preference
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppDestinationPreference
import com.github.damontecres.wholphin.preferences.AppMultiChoicePreference
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppStringPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.PreferenceValidation
import androidx.preference.PreferenceGroup as AndroidPreferenceGroup
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup as AppPreferenceGroup

/**
 * Turns the declarative preference definitions of `preferences/AppPreference.kt` into AndroidX Preference rows
 * (no-Compose UI, 2026-11-22), the job `ComposablePreference` did:
 *
 * - [AppSwitchPreference] → switch row; [AppChoicePreference] → list row (choices with their subtitles);
 *   [AppMultiChoicePreference] → multiple choice row; [AppStringPreference] → text row; [AppSliderPreference] →
 *   slider row; [AppClickablePreference] → click row ([onClickable]); [AppDestinationPreference] → navigates.
 * - Values are read with the definition's getter from [current] and written with its setter through [update], after
 *   its `validate` (an invalid value shows its message and is not saved), so every side effect a setter has
 *   (eg AC3 off clears "prefer AC3") happens exactly as before.
 * - Groups become headed categories; conditional preferences show while their condition holds; [hidden] hides
 *   definitions (kid sessions).
 *
 * Each created row registers a refresh with the level, so a new value from the store updates values, summaries and
 * visibility in place (inserted and removed rows keep the focused row).
 */
internal class AppPreferenceBinder<P : Any>(
    private val fragment: SettingsListFragment,
    private val accent: Int,
    private val current: () -> P?,
    private val update: (transform: (P) -> P) -> Unit,
    private val navigate: (Destination) -> Unit,
    private val onClickable: (AppClickablePreference<P>) -> Unit = {},
    private val hidden: (AppPreference<P, *>) -> Boolean = { false },
    private val largeCategories: Boolean = true,
    /** Headings for the groups (a single group of a Home row's options had none) */
    private val showCategories: (groupCount: Int) -> Boolean = { true },
) {
    private val context: Context = fragment.preferenceManager.context
    private var keyCounter = 0

    /** A replacement row for a definition that needs special handling, or null for the generic row */
    var special: ((AppPreference<P, *>) -> Preference?)? = null

    /** Called right after a definition's row is added, eg to add a related row below it */
    var afterAdd: ((AppPreference<P, *>, Preference, AndroidPreferenceGroup) -> Unit)? = null

    /** Adds each group as a heading with its rows to [parent] */
    fun addGroups(
        parent: AndroidPreferenceGroup,
        groups: List<AppPreferenceGroup<P>>,
        keyPrefix: String = "",
    ) {
        val headed = showCategories(groups.size)
        groups.forEachIndexed { groupIndex, group ->
            val category =
                SettingsCategory(context).apply {
                    key = "${keyPrefix}g$groupIndex"
                    title = if (headed) context.getString(group.title) else null
                    large = largeCategories
                    if (!headed) layoutResource = R.layout.lb_settings_group_spacer
                }
            parent.addPreference(category)
            group.preferences.forEachIndexed { index, pref ->
                add(category, pref, "${keyPrefix}g$groupIndex.p$index") { !hidden(pref) }
            }
            group.conditionalPreferences.forEachIndexed { conditionIndex, conditional ->
                conditional.preferences.forEachIndexed { index, pref ->
                    add(category, pref, "${keyPrefix}g$groupIndex.c$conditionIndex.p$index") { prefs ->
                        conditional.condition(prefs) && !hidden(pref)
                    }
                }
            }
        }
    }

    /** Adds the row for [pref] under [parent], shown while [visible] holds for the current value */
    fun add(
        parent: AndroidPreferenceGroup,
        pref: AppPreference<P, *>,
        key: String = "row${keyCounter++}",
        visible: (P) -> Boolean = { true },
    ): Preference {
        val specialRow = special?.invoke(pref)
        val row = specialRow ?: create(pref)
        row.key = key
        (row as? SettingsRow)?.accent = accent
        parent.addPreference(row)
        afterAdd?.invoke(pref, row, parent)
        // A special row shows its value itself; the binder only keeps its visibility
        val refreshValue: (P) -> Unit = if (specialRow != null) ({ _ -> }) else refresher(pref, row)
        fragment.onRefresh {
            val prefs = current()
            row.isVisible = prefs != null && visible(prefs)
            if (prefs != null && row.isVisible) refreshValue(prefs)
        }
        return row
    }

    /** The generic row for [pref] */
    @Suppress("UNCHECKED_CAST")
    fun create(pref: AppPreference<P, *>): Preference =
        when (pref) {
            is AppSwitchPreference -> {
                SettingsSwitchPreference(context).apply {
                    title = context.getString(pref.title)
                    setOnPreferenceChangeListener { _, newValue -> save(pref as AppPreference<P, Boolean>, newValue as Boolean) }
                }
            }

            is AppChoicePreference<*, *> -> {
                val choice = pref as AppChoicePreference<P, Any?>
                SettingsListPreference(context).apply {
                    title = context.getString(pref.title)
                    dialogTitle = title
                    entries = choiceEntries(context, choice.displayValues, choice.subtitles)
                    entryValues = Array<CharSequence>(entries.size) { it.toString() }
                    setOnPreferenceChangeListener { _, newValue ->
                        val index = (newValue as String).toInt()
                        save(choice, choice.indexToValue(index))
                    }
                }
            }

            is AppMultiChoicePreference<*, *> -> {
                val multi = pref as AppMultiChoicePreference<P, Any?>
                SettingsMultiListPreference(context).apply {
                    title = context.getString(pref.title)
                    dialogTitle = title
                    entries = choiceEntries(context, multi.displayValues, multi.displayValuesSubtitles)
                    entryValues = Array<CharSequence>(multi.allValues.size) { it.toString() }
                    setOnPreferenceChangeListener { _, newValue ->
                        val prefs = current() ?: return@setOnPreferenceChangeListener false

                        @Suppress("UNCHECKED_CAST")
                        val indices = (newValue as Set<String>).map { it.toInt() }.toSet()
                        val chosen = multi.allValues.filterIndexed { i, _ -> i in indices }
                        // Keep the stored order and add new choices at the end, as the old selection set did
                        val old = multi.getter(prefs)
                        val ordered = old.filter { it in chosen } + chosen.filterNot { it in old }
                        save(multi, ordered)
                    }
                }
            }

            is AppStringPreference -> {
                SettingsEditTextPreference(context).apply {
                    title = context.getString(pref.title)
                    dialogTitle = title
                    setOnPreferenceChangeListener { _, newValue -> save(pref as AppPreference<P, String>, newValue as String) }
                }
            }

            is AppSliderPreference -> {
                SettingsSliderPreference(context).apply {
                    title = context.getString(pref.title)
                    min = pref.min
                    max = pref.max
                    interval = pref.interval
                    summarizer = { value -> pref.summary(context, value) ?: pref.summary?.let(context::getString) }
                    onValueChange = { value -> save(pref as AppPreference<P, Long>, value) }
                }
            }

            is AppDestinationPreference -> {
                SettingsClickPreference(context).apply {
                    title = context.getString(pref.title)
                    summary = pref.summary(context, null)
                    onClick = { navigate(pref.destination) }
                }
            }

            is AppClickablePreference -> {
                SettingsClickPreference(context).apply {
                    title = context.getString(pref.title)
                    summary = pref.summary(context, null)
                    onClick = { onClickable(pref as AppClickablePreference<P>) }
                }
            }
        }

    /** How [row] shows the current value of [pref] */
    @Suppress("UNCHECKED_CAST")
    private fun refresher(
        pref: AppPreference<P, *>,
        row: Preference,
    ): (P) -> Unit =
        when {
            row is SettingsSwitchPreference && pref is AppSwitchPreference -> {
                { prefs ->
                    val value = (pref as AppSwitchPreference<P>).getter(prefs)
                    row.isChecked = value
                    row.summary = pref.summary(context, value)
                }
            }

            row is SettingsListPreference && pref is AppChoicePreference<*, *> -> {
                { prefs ->
                    val choice = pref as AppChoicePreference<P, Any?>
                    val value = choice.getter(prefs)
                    val index = choice.valueToIndex(value)
                    row.value = index.toString()
                    row.summary = choice.summary?.let(context::getString)
                        ?: choice.summary(context, value)
                        ?: plainEntry(row.entries, index)
                }
            }

            row is SettingsMultiListPreference && pref is AppMultiChoicePreference<*, *> -> {
                { prefs ->
                    val multi = pref as AppMultiChoicePreference<P, Any?>
                    val selected = multi.getter(prefs)
                    row.values =
                        multi.allValues.indices
                            .filter { multi.allValues[it] in selected }
                            .map { it.toString() }
                            .toSet()
                    row.summary = multi.summary?.let(context::getString) ?: multi.summary(context, selected)
                }
            }

            row is SettingsEditTextPreference && pref is AppStringPreference -> {
                { prefs ->
                    val value = (pref as AppStringPreference<P>).getter(prefs)
                    row.text = value
                    row.summary = pref.summary(context, value) ?: pref.summary?.let(context::getString)
                }
            }

            row is SettingsSliderPreference && pref is AppSliderPreference -> {
                { prefs ->
                    val value = (pref as AppSliderPreference<P>).getter(prefs)
                    row.value = value
                    row.summary = pref.summary(context, value) ?: pref.summary?.let(context::getString)
                }
            }

            else -> {
                { _ -> }
            }
        }

    /** Validates and saves [value] for [pref]; false keeps the row unchanged */
    fun <T> save(
        pref: AppPreference<P, T>,
        value: T,
    ): Boolean {
        val prefs = current() ?: return false
        return when (val validation = pref.validate(prefs, value)) {
            is PreferenceValidation.Invalid -> {
                Toast.makeText(context, validation.message, Toast.LENGTH_SHORT).show()
                false
            }

            PreferenceValidation.Valid -> {
                update { latest -> pref.setter(latest, value) }
                true
            }
        }
    }

    companion object {
        /** The choice texts, each with its subtitle in a smaller second line when it has one */
        fun choiceEntries(
            context: Context,
            displayValues: Int,
            subtitles: Int?,
        ): Array<CharSequence> {
            val values = context.resources.getStringArray(displayValues)
            val subs = subtitles?.let { context.resources.getStringArray(it) }
            return Array(values.size) { i ->
                val subtitle = subs?.getOrNull(i)
                if (subtitle.isNotNullOrBlank()) withSubtitle(values[i], subtitle!!) else values[i]
            }
        }

        fun withSubtitle(
            title: CharSequence,
            subtitle: CharSequence,
        ): CharSequence =
            SpannableStringBuilder(title).apply {
                append("\n")
                val start = length
                append(subtitle)
                setSpan(RelativeSizeSpan(.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(
                    ForegroundColorSpan(AppTheme.palette.onSurfaceVariant),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

        /** A choice's text without its subtitle line, for the row summary */
        fun plainEntry(
            entries: Array<CharSequence>?,
            index: Int,
        ): CharSequence? =
            entries
                ?.getOrNull(index)
                ?.toString()
                ?.substringBefore('\n')
    }
}
