package com.github.damontecres.wholphin.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.components.clockHeaderEndPadding
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.search.SearchCombinedResults
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
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

@Composable
fun DiscoverSearchPage(
    preferences: UserPreferences,
    positionCallback: (columns: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverSearchViewModel = hiltViewModel(),
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val seerrResults by viewModel.seerrResults.collectAsState()
    var query by rememberSaveable { mutableStateOf(viewModel.currentQuery) }
    var position by rememberInt(-1)
    var isSearchActive by remember { mutableStateOf(false) }
    var pendingFocusQuery by remember { mutableStateOf<String?>(null) }
    var submittedResults by remember { mutableIntStateOf(0) }
    val gridFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val voiceFocusRequester = remember { FocusRequester() }

    // Start with current preferences, but collect updates when view options change
    val prefs =
        viewModel.userPreferencesService.flow
            .collectAsState(preferences)
            .value.appPreferences.interfacePreferences.searchPreferences

    val onClickDiscover = { _: Int, item: DiscoverItem ->
        val dest =
            if (item.jellyfinItemId != null && item.type.baseItemKind != null) {
                Destination.MediaItem(
                    itemId = item.jellyfinItemId,
                    type = item.type.baseItemKind,
                )
            } else {
                Destination.DiscoveredItem(item)
            }
        viewModel.navigationManager.navigateTo(dest)
    }

    fun triggerImmediateSearch(searchQuery: String) {
        isSearchActive = false
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        pendingFocusQuery = searchQuery.trim().takeIf { it.isNotEmpty() }
        submittedResults++
        viewModel.search(searchQuery, immediate = true)
    }

    LaunchedEffect(query) {
        viewModel.search(query)
    }
    LaunchedEffect(pendingFocusQuery, seerrResults) {
        val submitted = pendingFocusQuery ?: return@LaunchedEffect
        if (submitted != viewModel.currentQuery) return@LaunchedEffect
        val results = seerrResults
        if (results is SearchResult.SuccessSeerr && results.items.isNotEmpty()) {
            repeat(3) {
                withFrameNanos { }
                if (gridFocusRequester.tryRequestFocus("submitted request results")) {
                    pendingFocusQuery = null
                    return@LaunchedEffect
                }
            }
            // A temporarily unavailable grid must not leave the search field locked out.
            pendingFocusQuery = null
        } else if (results is SearchResult.Error || results is SearchResult.SuccessSeerr) {
            pendingFocusQuery = null
            textFieldFocusRequester.tryRequestFocus("empty request results")
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (position >= 0) {
            gridFocusRequester.tryRequestFocus("grid")
        } else if (!prefs.showVoiceSearchButton || !voiceFocusRequester.tryRequestFocus("voice search")) {
            textFieldFocusRequester.tryRequestFocus("textField")
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            var isTextFieldFocused by remember { mutableStateOf(false) }

            BackHandler(isTextFieldFocused) {
                when {
                    isSearchActive -> {
                        isSearchActive = false
                        keyboardController?.hide()
                    }

                    else -> {
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(start = 16.dp, end = if (isWeaselTv()) clockHeaderEndPadding() else 16.dp)
                        .focusGroup()
                        .focusRestorer(),
            ) {
                AnimatedVisibility(
                    visible = prefs.showVoiceSearchButton,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                ) {
                    VoiceSearchButton(
                        onSpeechResult = { spokenText ->
                            query = spokenText
                            triggerImmediateSearch(spokenText)
                        },
                        voiceInputManager = viewModel.voiceInputManager,
                        modifier = Modifier.padding(end = 12.dp).focusRequester(voiceFocusRequester),
                    )
                }

                SearchEditTextBox(
                    value = query,
                    onValueChange = {
                        pendingFocusQuery = null
                        isSearchActive = true
                        query = it
                    },
                    onSearchClick = { triggerImmediateSearch(query) },
                    readOnly = !isSearchActive,
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(textFieldFocusRequester)
                            .onFocusChanged { state ->
                                isTextFieldFocused = state.isFocused
                                if (!state.isFocused) isSearchActive = false
                            }.onPreviewKeyEvent { event ->
                                val isActivationKey =
                                    event.key in listOf(Key.DirectionCenter, Key.Enter)
                                if (event.type == KeyEventType.KeyUp && isActivationKey && !isSearchActive && pendingFocusQuery == null) {
                                    isSearchActive = true
                                    keyboardController?.show()
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }
        }
        if (seerrResults is SearchResult.Error) {
            Button(
                onClick = { triggerImmediateSearch(query) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.retry))
            }
        }
        key(submittedResults) {
        SearchCombinedResults(
            result = seerrResults,
            focusRequester = gridFocusRequester,
            onClickItem = { _, _ -> },
            onLongClickItem = { _, _ -> },
            onPlayItem = { _, _ -> },
            onClickPosition = { position = it.column },
            onClickDiscover = onClickDiscover,
            positionCallback = { columns, index ->
                position = index
                positionCallback.invoke(columns, index)
            },
            modifier = Modifier.fillMaxSize(),
        )
        }
    }
}
