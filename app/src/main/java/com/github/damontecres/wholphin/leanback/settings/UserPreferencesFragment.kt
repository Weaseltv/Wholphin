package com.github.damontecres.wholphin.leanback.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinUserPreferences
import com.github.damontecres.wholphin.preferences.SubtitleModePreference
import com.github.damontecres.wholphin.preferences.UserProfileSettings
import com.github.damontecres.wholphin.ui.preferences.user.PreferredLanguage
import com.github.damontecres.wholphin.ui.preferences.user.PreferredLanguageType
import com.github.damontecres.wholphin.ui.preferences.user.UserPreferencesViewModel
import com.github.damontecres.wholphin.ui.preferences.user.stringRes
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The settings taken from the profile on the server (`Destination.UserAppPreferences`, the old
 * `UserProfilePreferencesPage`) on Leanback Preference: preferred audio and subtitle languages (each a level with a
 * filter field over the server's languages) and the subtitle mode, saved to the signed-in profile.
 */
@AndroidEntryPoint
class UserPreferencesFragment : SettingsPageFragment() {
    private val viewModel: UserPreferencesViewModel by viewModels()

    override val rootScreenKey: String = ROOT
    override val panelStripe: Boolean = false
    override val listHorizontalPaddingDp: Int = 16

    override fun panelBackground(): Int = SettingsLook.tonalSurface(1f, accent)

    override fun screenTitle(key: String): CharSequence =
        getString(
            when (key) {
                AUDIO -> R.string.preferred_audio_language
                SUBTITLE -> R.string.preferred_subtitle_language
                else -> R.string.profile_specific_settings
            },
        )

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        when (key) {
            AUDIO -> buildLanguages(fragment, screen, isAudio = true)
            SUBTITLE -> buildLanguages(fragment, screen, isAudio = false)
            else -> buildRoot(fragment, screen)
        }
    }

    private fun buildRoot(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context
        val binder =
            AppPreferenceBinder<JellyfinUserPreferences>(
                fragment = fragment,
                accent = accent,
                current = { viewModel.userAppPreferences.value },
                update = { transform -> viewModel.updatePreferences(transform) },
                navigate = viewModel.navigationManager::navigateTo,
                largeCategories = false,
            )
        binder.special = { pref ->
            when (pref) {
                UserProfileSettings.PreferredAudioLang -> {
                    SettingsClickPreference(ctx).also { row ->
                        row.title = getString(pref.title)
                        row.onClick = { openScreen(AUDIO) }
                        fragment.onRefresh {
                            row.summary =
                                viewModel.audioLanguage.value.selected.displayString
                                    .getString(ctx)
                        }
                    }
                }

                UserProfileSettings.PreferredSubtitleLang -> {
                    SettingsClickPreference(ctx).also { row ->
                        row.title = getString(pref.title)
                        row.onClick = { openScreen(SUBTITLE) }
                        fragment.onRefresh {
                            row.summary =
                                viewModel.subtitleLanguage.value.selected.displayString
                                    .getString(ctx)
                        }
                    }
                }

                UserProfileSettings.SubtitleModePref -> {
                    subtitleModeRow(fragment)
                }

                else -> {
                    null
                }
            }
        }
        binder.addGroups(screen, UserProfileSettings.Preferences)
    }

    /** The subtitle mode; "Use user profile" names the profile's own mode */
    private fun subtitleModeRow(fragment: SettingsListFragment): SettingsListPreference {
        val ctx = fragment.preferenceManager.context
        val values = resources.getStringArray(R.array.subtitle_mode_options).toList()
        return SettingsListPreference(ctx).also { row ->
            row.title = getString(R.string.subtitle_mode)
            row.dialogTitle = row.title
            row.entryValues = Array<CharSequence>(values.size) { it.toString() }
            row.setOnPreferenceChangeListener { _, newValue ->
                val index = (newValue as String).toInt()
                viewModel.updatePreferences { it.copy(subtitleMode = SubtitleModePreference.entries[index]) }
                true
            }
            fragment.onRefresh {
                val userMode =
                    viewModel.currentUserDto.value
                        ?.configuration
                        ?.subtitleMode
                row.entries =
                    values
                        .mapIndexed<_, CharSequence> { index, text ->
                            if (index == 0 &&
                                userMode != null
                            ) {
                                AppPreferenceBinder.withSubtitle(text, getString(userMode.stringRes))
                            } else {
                                text
                            }
                        }.toTypedArray()
                val value = viewModel.userAppPreferences.value.subtitleMode
                row.value = value.ordinal.toString()
                row.summary =
                    when (value) {
                        SubtitleModePreference.USE_USER_PROFILE -> {
                            if (userMode != null) "${values[0]} - ${getString(userMode.stringRes)}" else values[0]
                        }

                        else -> {
                            values[value.ordinal]
                        }
                    }
            }
        }
    }

    /**
     * The language choice (the old `FilterableLanguagePreference`): a filter field, "Use user profile", "Any language",
     * a divider and the server's languages, the chosen one checked. Typing filters to matching languages after half a
     * second, names starting with the text first; the keyboard's search action moves to the first result.
     */
    private fun buildLanguages(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
        isAudio: Boolean,
    ) {
        val ctx = fragment.preferenceManager.context
        val flow = if (isAudio) viewModel.audioLanguage else viewModel.subtitleLanguage
        val rows = mutableMapOf<PreferredLanguageType, androidx.preference.Preference>()
        var filtered: List<PreferredLanguageType>? = null
        var filterJob: Job? = null
        val search =
            SettingsSearchPreference(ctx).apply {
                key = "search"
                accent = this@UserPreferencesFragment.accent
                order = -1
            }
        screen.addPreference(search)

        fun firstResultKey(): String? =
            rows.values
                .filter { it.isVisible && it.isSelectable }
                .minByOrNull { it.order }
                ?.key

        search.onSearch = { firstResultKey()?.let(fragment::selectPreference) }
        search.onQuery = { query ->
            filterJob?.cancel()
            filterJob =
                fragment.lifecycleScope.launch {
                    delay(FILTER_DELAY_MS)
                    val options = flow.value.options
                    filtered =
                        if (query.isNotBlank()) {
                            withContext(WholphinDispatchers.Default) {
                                val q = query.lowercase()
                                options
                                    .filter {
                                        // Only languages, not the "built-in" choices
                                        it is PreferredLanguageType.Language &&
                                            (it.name.lowercase().contains(q) || it.iso.contains(q))
                                    }.sortedByDescending {
                                        // Typing the first letters of a language: those first (English before Armenian)
                                        (it as PreferredLanguageType.Language).name.lowercase().startsWith(q)
                                    }
                            }
                        } else {
                            null
                        }
                    fragment.refresh()
                }
        }

        fun choose(option: PreferredLanguageType) {
            val value =
                when (option) {
                    PreferredLanguageType.AnyLanguage -> UserProfileSettings.PREFER_ANY_LANGUAGE
                    is PreferredLanguageType.Language -> option.iso
                    is PreferredLanguageType.ServerProfile -> UserProfileSettings.USE_USER_PROFILE
                    PreferredLanguageType.Divider -> return
                }
            Timber.v("Updating language pref to %s", option)
            viewModel.updatePreferences {
                if (isAudio) it.copy(preferredAudioLanguage = value) else it.copy(preferredSubtitleLanguage = value)
            }
            closePanel()
        }

        fragment.onRefresh {
            val language: PreferredLanguage = flow.value
            val shown = filtered ?: language.options
            val keep = language.options.toSet()
            rows.keys.filter { it !in keep }.forEach { rows.remove(it)?.let(screen::removePreference) }
            language.options.forEachIndexed { index, option ->
                val row =
                    rows.getOrPut(option) {
                        val created =
                            if (option is PreferredLanguageType.Divider) {
                                SettingsDividerPreference(ctx)
                            } else {
                                SettingsClickPreference(ctx).apply {
                                    accent = this@UserPreferencesFragment.accent
                                    onClick = { choose(option) }
                                }
                            }
                        created.also {
                            it.key = "lang$index:${option.hashCode()}"
                            screen.addPreference(it)
                        }
                    }
                val position = shown.indexOf(option)
                row.isVisible = position >= 0
                row.order = if (position >= 0) position else index
                if (row is SettingsClickPreference) {
                    val selected = option == language.selected
                    val name =
                        when (option) {
                            is PreferredLanguageType.ServerProfile -> getString(R.string.use_user_profile)
                            else -> option.displayString.getString(ctx)
                        }
                    // The old SelectedLeadingContent: a check before the chosen language
                    row.title = SettingsLook.glyphTitle(ctx, getString(R.string.fa_check), name, glyphVisible = selected)
                    row.summary = (option as? PreferredLanguageType.ServerProfile)?.name
                }
            }
        }
        fragment.lifecycleScope.launch { flow.collect { fragment.refresh() } }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.userAppPreferences.collectWhileStarted { refreshScreens() }
        viewModel.currentUserDto.collectWhileStarted { refreshScreens() }
        viewModel.audioLanguage.collectWhileStarted { refreshScreens() }
        viewModel.subtitleLanguage.collectWhileStarted { refreshScreens() }
    }

    companion object {
        private const val ROOT = "profile"
        private const val AUDIO = "language.audio"
        private const val SUBTITLE = "language.subtitle"
        private const val FILTER_DELAY_MS = 500L
    }
}
