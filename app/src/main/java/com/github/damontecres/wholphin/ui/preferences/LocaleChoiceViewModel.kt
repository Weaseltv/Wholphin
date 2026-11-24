package com.github.damontecres.wholphin.ui.preferences

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.isSameIdentity
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/*
 * The interface language choice's state, Compose-free (no-Compose rewrite, 2026-11-22). Moved unchanged from
 * LocaleChoiceDialog.kt.
 */

/** Loads the app's locales and changes the signed-in profile's interface language */
@HiltViewModel
class LocaleChoiceViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        /** The profile whose settings these are; a language picked here only ever applies to that profile */
        private val owner = serverRepository.current.value

        fun changeLocale(locale: Locale) {
            viewModelScope.launchDefault {
                serverRepository.current.value?.takeIf { owner != null && it.isSameIdentity(owner) }?.let {
                    Timber.i("Updating user's locale to %s", locale.toLanguageTag())
                    // An update of this session, never a switch: refused if another session replaced it meanwhile
                    // (review round 9)
                    if (serverRepository.updateCurrentUserLanguage(it, locale.toLanguageTag()) == null) {
                        Timber.w("Locale not updated, the session was replaced")
                    }
                }
            }
        }

        private val _state = MutableStateFlow(LocaleChoiceState())
        val state: StateFlow<LocaleChoiceState> = _state

        init {
            viewModelScope.launchDefault {
                serverRepository.currentUser?.let {
                    val availableLocales = extractAvailableLocales()
                    Timber.v("availableLocales=%s", availableLocales)
                    _state.update {
                        it.copy(
                            loading = LoadingState.Success,
                            availableLocales = availableLocales,
                        )
                    }
                }
            }
            viewModelScope.launchDefault {
                serverRepository.currentUserFlow.collectLatest { user ->
                    val userLocale = user?.uiLanguage?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                    _state.update { it.copy(userLocale = userLocale) }
                }
            }
        }

        /**
         * Get the available locales that can be used.
         *
         * Defaults to `context.assets.locales` (all locales) if can't find app specific ones
         */
        private fun extractAvailableLocales(): List<Locale> {
            val id =
                context.resources.getIdentifier(
                    "_generated_res_locale_config",
                    "xml",
                    context.packageName,
                )
            return if (id != 0) {
                try {
                    val locales = mutableListOf<Locale>()
                    // This is kind of a hack, the generated locale config is not available,
                    // programmatically, so this code parses the raw XML
                    context.resources.getXml(id).use { parser ->
                        var eventType: Int = parser.eventType
                        while (eventType != XmlResourceParser.END_DOCUMENT) {
                            if (eventType == XmlResourceParser.START_TAG) {
                                val tagName: String? = parser.name

                                // Check for a specific tag name
                                if ("locale" == tagName) {
                                    val attr =
                                        parser.getAttributeValue(
                                            "http://schemas.android.com/apk/res/android",
                                            "name",
                                        )
                                    attr?.let { locales.add(Locale.forLanguageTag(it)) }
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                    locales
                } catch (ex: Exception) {
                    Timber.w(ex, "Exception while parsing generated available locales")
                    context.assets.locales.map { Locale.forLanguageTag(it) }
                }
            } else {
                context.assets.locales.map { Locale.forLanguageTag(it) }
            }
        }
    }

data class LocaleChoiceState(
    val loading: LoadingState = LoadingState.Pending,
    val availableLocales: List<Locale> = emptyList(),
    val userLocale: Locale = Locale.getDefault(),
)
