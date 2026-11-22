package com.github.damontecres.wholphin.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** Requests search (Seerr): typing waits 400 ms, a submitted search runs at once (moved out of DiscoverSearchPage.kt) */
@HiltViewModel
class DiscoverSearchViewModel
    @Inject
    constructor(
        val navigationManager: NavigationManager,
        private val seerrService: SeerrService,
        val voiceInputManager: VoiceInputManager,
        val userPreferencesService: UserPreferencesService,
    ) : ViewModel() {
        val seerrResults = MutableStateFlow<SearchResult>(SearchResult.NoQuery)

        private var searchJob: Job? = null
        private var searchStarted = false
        var currentQuery: String = ""
            private set

        fun search(
            query: String,
            immediate: Boolean = false,
        ) {
            val title = query.trim()
            if (title == currentQuery) {
                // An explicit submission may bypass a pending typing delay or retry an error.
                if (!immediate || (searchJob?.isActive == true && searchStarted)) return
            }
            searchJob?.cancel()
            currentQuery = title
            searchStarted = false
            if (title.isBlank() || (!immediate && title.length < 2)) {
                seerrResults.value = SearchResult.NoQuery
                return
            }
            seerrResults.value = SearchResult.Searching
            searchJob =
                viewModelScope.launch {
                    if (!immediate) delay(400L)
                    searchStarted = true
                    Timber.v("Starting seerr search")
                    try {
                        val results =
                            withContext(WholphinDispatchers.IO) {
                                seerrService
                                    .search(title)
                                    .filter { it.mediaType == "movie" || it.mediaType == "tv" }
                                    .distinctBy { it.mediaType to it.id }
                                    .map { seerrService.createDiscoverItem(it) }
                            }
                        ensureActive()
                        Timber.v("Seerr search complete: %s results", results.size)
                        seerrResults.value = SearchResult.SuccessSeerr(results)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        ensureActive()
                        Timber.e(ex, "Error during seerr search")
                        seerrResults.value = SearchResult.Error(ex)
                    }
                }
        }
    }
