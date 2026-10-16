package com.github.damontecres.wholphin.ui.search

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.updateSearchPreferences
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.KeyValueService
import com.github.damontecres.wholphin.services.LiveTvService
import com.github.damontecres.wholphin.services.MediaManagementService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SearchIndexService
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.deleteItem
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.ProgramItemFields
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.baseItemKinds
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.livetv.ProgramDialogState
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.FuzzySearch
import com.github.damontecres.wholphin.util.SearchRelevance
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.personsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val api: ApiClient,
        val navigationManager: NavigationManager,
        val appPreferences: DataStore<AppPreferences>,
        private val seerrService: SeerrService,
        val voiceInputManager: VoiceInputManager,
        val userPreferencesService: UserPreferencesService,
        private val serverRepository: ServerRepository,
        private val favoriteWatchManager: FavoriteWatchManager,
        private val mediaManagementService: MediaManagementService,
        private val serverReportService: ServerReportService,
        private val liveTvService: LiveTvService,
        private val keyValueService: KeyValueService,
        private val navDrawerService: NavDrawerService,
        private val searchIndexService: SearchIndexService,
    ) : ViewModel(),
        ContextMenuProvider {
        val seerrActive =
            seerrService.active.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

        private val _state = MutableStateFlow(SearchState())
        val state: StateFlow<SearchState> = _state
        val position = MutableStateFlow(RowColumn(0, 0))

        private var currentQuery: String? = null
        private var combinedMode = false

        private val _programDialogState = MutableStateFlow(ProgramDialogState())
        val programDialogState: StateFlow<ProgramDialogState> = _programDialogState

        private var userLibraryTypes: Set<BaseItemKind> = emptySet()

        /** A movies, shows or collections section ([type] null: combined results) shown before the title index was ready. */
        private class Unindexed(
            val type: BaseItemKind?,
            val query: String,
            val types: List<BaseItemKind>,
            val items: List<BaseItem>,
        )

        private val unindexed = MutableStateFlow<List<Unindexed>>(emptyList())

        init {
            init()
        }

        private fun init() {
            viewModelScope.launchIO {
                // Sections searched before the title index was ready gain its matches once it is
                searchIndexService.index.collect { if (it != null) addIndexMatchesWhenReady() }
            }
            viewModelScope.launchDefault {
                val defaultExcludes = if (BuildConfig.FLAVOR == "weaselfin") listOf(BaseItemKind.BOX_SET) else emptyList()
                val defaultDiscover = BuildConfig.FLAVOR != "weaselfin"
                val excludedSearchableTypes =
                    serverRepository.currentUser?.id?.let { userId ->
                        try {
                            keyValueService
                                .get<List<BaseItemKind>>(
                                    userId,
                                    EXCLUDED_SEARCHABLE_TYPES_KEY,
                                    defaultExcludes,
                                ).firstOrNull()
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error occurred fetching excluded search types")
                            defaultExcludes
                        }
                    } ?: defaultExcludes
                val discoverEnabled =
                    serverRepository.currentUser?.id?.let { userId ->
                        try {
                            keyValueService
                                .get(
                                    userId,
                                    INCLUDE_DISCOVER_KEY,
                                    defaultDiscover,
                                ).firstOrNull()
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error occurred fetching excluded search types")
                            defaultDiscover
                        }
                    } ?: defaultDiscover
                _state.update {
                    it.copy(discoverEnabled = discoverEnabled, excludedSearchableTypes = excludedSearchableTypes)
                }
                // A SEARCH intent can arrive before the navigation libraries load.
                // Observe their types so the initial empty snapshot cannot permanently
                // hide Movie, Series and Episode search. Preserve exclusions on refresh.
                navDrawerService.state
                    .map { nav -> nav.allLibraries.flatMap { it.collectionType.baseItemKinds }.toSet() }
                    .distinctUntilChanged()
                    .collectLatest { libraryTypes ->
                        userLibraryTypes = libraryTypes
                        val previousTypes = state.value.includedSearchableTypes
                        val searchableTypes = determineSearchableTypes(state.value.excludedSearchableTypes)
                        val possibleSearchableTypes = determineSearchableTypes(emptyList())
                        _state.update {
                            it.copy(
                                includedSearchableTypes = searchableTypes,
                                possibleSearchableTypes = possibleSearchableTypes,
                                results =
                                    SnapshotStateMap<BaseItemKind, SearchResult>().apply {
                                        searchableTypes.forEach { type -> put(type, it.results[type] ?: SearchResult.NoQuery) }
                                    },
                            )
                        }
                        warmIndex(searchableTypes)
                        if (previousTypes != searchableTypes && currentQuery.isNotNullOrBlank()) {
                            search(currentQuery, combinedMode, force = true)
                        }
                    }
            }
        }

        private fun determineSearchableTypes(excludedSearchableTypes: List<BaseItemKind>): List<BaseItemKind> {
            val tvAccess = serverRepository.currentUserDto?.tvAccess == true
            val searchableTypes =
                allSearchableTypes.filter {
                    val excluded = excludedSearchableTypes.contains(it)
                    // Only include if there's a relevant, accessible library
                    val hasLibrary = userLibraryTypes.contains(it)
                    when (it) {
                        // No library type for person
                        BaseItemKind.PERSON -> !excluded

                        // Remove live tv search if user doesn't have access
                        BaseItemKind.TV_CHANNEL,
                        BaseItemKind.LIVE_TV_PROGRAM,
                        BaseItemKind.TV_PROGRAM,
                        BaseItemKind.PROGRAM,
                        -> tvAccess && !excluded

                        else -> hasLibrary && !excluded
                    }
                }
            return searchableTypes
        }

        fun search(
            query: String?,
            combined: Boolean = false,
            force: Boolean = false,
        ) {
            if (currentQuery == query && combinedMode == combined && !force) {
                return
            }
            currentQuery = query
            combinedMode = combined
            unindexed.value = emptyList()
            if (query.isNotNullOrBlank()) {
                _state.update {
                    it.copy(
                        results =
                            SnapshotStateMap<BaseItemKind, SearchResult>().apply {
                                it.includedSearchableTypes.forEach {
                                    put(it, SearchResult.Searching)
                                }
                            },
                        combinedResults = SearchResult.Searching,
                    )
                }
                if (combined) {
                    searchCombined(query)
                } else {
                    state.value.includedSearchableTypes.forEach { type ->
                        searchType(query, type)
                    }
                }
                searchSeerr(query)
            } else {
                _state.update {
                    it.copy(
                        results =
                            SnapshotStateMap<BaseItemKind, SearchResult>().apply {
                                it.includedSearchableTypes.forEach {
                                    put(it, SearchResult.NoQuery)
                                }
                            },
                        combinedResults = SearchResult.NoQuery,
                    )
                }
            }
        }

        private fun searchType(
            query: String,
            type: BaseItemKind,
        ) {
            viewModelScope.launchIO {
                try {
                    val indexTypes = listOf(type).filter { it in FuzzySearch.TYPES }
                    val indexed = indexMatchesAsync(query, indexTypes)
                    val items: List<BaseItem> =
                        when (type) {
                            BaseItemKind.LIVE_TV_PROGRAM -> {
                                val request =
                                    GetItemsRequest(
                                        searchTerm = query,
                                        recursive = true,
                                        includeItemTypes = listOf(type),
                                        fields = ProgramItemFields,
                                        limit = SEARCH_LIMIT,
                                        enableTotalRecordCount = false,
                                    )
                                api.itemsApi.getItems(request).toBaseItems(api, false)
                            }

                            BaseItemKind.MUSIC_ARTIST -> {
                                api.artistsApi
                                    .getArtists(
                                        searchTerm = query,
                                        fields = SlimItemFields,
                                        limit = SEARCH_LIMIT,
                                        enableTotalRecordCount = false,
                                    ).toBaseItems(api, false)
                            }

                            BaseItemKind.PERSON -> {
                                api.personsApi
                                    .getPersons(
                                        searchTerm = query,
                                        fields = SlimItemFields,
                                        limit = SEARCH_LIMIT,
                                    ).toBaseItems(api, false)
                            }

                            else -> {
                                val request =
                                    GetItemsRequest(
                                        searchTerm = query,
                                        recursive = true,
                                        includeItemTypes = listOf(type),
                                        fields = SlimItemFields,
                                        limit = SEARCH_LIMIT,
                                        enableTotalRecordCount = false,
                                    )
                                api.itemsApi.getItems(request).toBaseItems(api, false)
                            }
                        }
                    val sorted =
                        items.sortedWith(
                            compareBy<BaseItem> { SearchRelevance.score(it, query) },
                        )
                    val results = indexed?.let { FuzzySearch.merge(it.await(), sorted) { item -> item.id } } ?: sorted
                    Timber.v("Search finished for %s, %s results", type, results.size)
                    _state.value.results[type] = SearchResult.Success(results)
                    if (indexed == null) addIndexMatchesLater(type, query, indexTypes, sorted)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception searching for $type")
                    _state.value.results[type] = SearchResult.Error(ex)
                }
            }
        }

        private fun searchCombined(query: String) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                try {
                    Timber.v("Starting searchCombined")
                    val types = state.value.includedSearchableTypes
                    val indexTypes = types.filter { it in FuzzySearch.TYPES }
                    val indexed = indexMatchesAsync(query, indexTypes)
                    val request =
                        GetItemsRequest(
                            searchTerm = query,
                            recursive = true,
                            includeItemTypes = types,
                            fields = SlimItemFields,
                            limit = SEARCH_LIMIT,
                        )

                    val result = api.itemsApi.getItems(request).content
                    val items =
                        result.items.map {
                            BaseItem(it, false)
                        }
                    val sorted =
                        items.sortedWith(
                            compareBy<BaseItem> { SearchRelevance.score(it, query) },
                        )
                    val results = indexed?.let { FuzzySearch.merge(it.await(), sorted) { item -> item.id } } ?: sorted
                    Timber.v("searchCombined complete %s results", results.size)
                    _state.update { it.copy(combinedResults = SearchResult.Success(results)) }
                    if (indexed == null) addIndexMatchesLater(null, query, indexTypes, sorted)
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception in combined search")
                    _state.update { it.copy(combinedResults = SearchResult.Error(ex)) }
                }
            }
        }

        /** Get the member's title index ready for the movie, show and collection sections Search shows. */
        private fun warmIndex(types: List<BaseItemKind>) {
            val user = serverRepository.currentUser ?: return
            searchIndexService.warm(user.serverId, user.id, types.filter { it in FuzzySearch.TYPES }.toSet())
        }

        /** The member's title index, if it's ready and holds [types]. */
        private fun readyIndex(types: List<BaseItemKind>): SearchIndexService.Index? {
            val user = serverRepository.currentUser ?: return null
            return searchIndexService.index.value?.takeIf { it.holds(user.serverId, user.id, types) }
        }

        /**
         * Start fetching the title index's matches for [query] among [types] alongside the server's
         * search (owner, 2026-10-16). Null when there are none of those types or the index isn't ready:
         * search never waits for it.
         */
        private fun CoroutineScope.indexMatchesAsync(
            query: String,
            types: List<BaseItemKind>,
        ): Deferred<FuzzySearch.Ranked<BaseItem>>? {
            if (types.isEmpty()) return null
            val index = readyIndex(types) ?: return null
            return async { indexMatches(index, query, types) }
        }

        /**
         * The title index's matches for [query] among [types], best first, fetched by ID with the fields
         * and images the sections' own search uses. Titles the member can't see don't come back.
         */
        private suspend fun indexMatches(
            index: SearchIndexService.Index,
            query: String,
            types: List<BaseItemKind>,
        ): FuzzySearch.Ranked<BaseItem> =
            try {
                val ranked = FuzzySearch.rank(index.entries.filter { it.type in types }, query)
                val byId =
                    coroutineScope {
                        // One request per type, so even combined results keep the URL short
                        ranked.all
                            .groupBy { it.type }
                            .values
                            .map { entries ->
                                async {
                                    api.itemsApi
                                        .getItems(
                                            GetItemsRequest(
                                                userId = index.userId,
                                                ids = entries.map { it.id },
                                                fields = SlimItemFields,
                                                enableTotalRecordCount = false,
                                            ),
                                        ).content.items
                                }
                            }.awaitAll()
                            .flatten()
                            .associateBy { it.id }
                    }
                // The server returns them in its own order, so put them back in ranked order
                ranked.mapNotNull { entry -> byId[entry.id]?.let { BaseItem.from(it, api, false) } }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // The server's results still show
                Timber.w(ex, "Couldn't fetch the title index's matches")
                FuzzySearch.Ranked(emptyList(), emptyList())
            }

        /** Add the title index's matches to a section shown with only the server's [items], once the index is ready. */
        private fun addIndexMatchesLater(
            type: BaseItemKind?,
            query: String,
            types: List<BaseItemKind>,
            items: List<BaseItem>,
        ) {
            if (types.isEmpty()) return
            unindexed.update { it + Unindexed(type, query, types, items) }
            // In case the index became ready during the search
            addIndexMatchesWhenReady()
        }

        private fun addIndexMatchesWhenReady() {
            val user = serverRepository.currentUser ?: return
            val index = searchIndexService.index.value ?: return
            var ready: List<Unindexed> = emptyList()
            unindexed.update { sections ->
                ready = sections.filter { index.holds(user.serverId, user.id, it.types) }
                sections - ready.toSet()
            }
            ready.forEach { section ->
                viewModelScope.launchIO {
                    val indexed = indexMatches(index, section.query, section.types)
                    if (indexed.all.isEmpty() || section.query != currentQuery) return@launchIO
                    val result = SearchResult.Success(FuzzySearch.merge(indexed, section.items) { it.id })
                    when {
                        section.type == null -> if (combinedMode) _state.update { it.copy(combinedResults = result) }
                        !combinedMode -> _state.value.results[section.type] = result
                    }
                }
            }
        }

        fun setCombinedResults(enabled: Boolean) {
            viewModelScope.launchIO {
                appPreferences.updateData {
                    it.updateSearchPreferences {
                        combinedSearchResults = enabled
                    }
                }
            }
        }

        fun setVoiceSearchButtonVisible(visible: Boolean) {
            viewModelScope.launchIO {
                appPreferences.updateData {
                    it.updateSearchPreferences {
                        showVoiceSearchButton = visible
                    }
                }
            }
        }

        private fun searchSeerr(query: String) {
            viewModelScope.launchIO {
                if (seerrActive.first() && state.value.discoverEnabled) {
                    _state.update { it.copy(seerrResults = SearchResult.Searching) }
                    val results =
                        seerrService
                            .search(query)
                            .map { seerrService.createDiscoverItem(it) }
                            .filter { it.type == SeerrItemType.MOVIE || it.type == SeerrItemType.TV }
                    _state.update { it.copy(seerrResults = SearchResult.SuccessSeerr(results)) }
                }
            }
        }

        init {
            addCloseable(voiceInputManager)
        }

        override fun navigateTo(destination: Destination) {
            navigationManager.navigateTo(destination)
        }

        override fun canDelete(
            item: BaseItem,
            appPreferences: AppPreferences,
        ): Boolean = mediaManagementService.canDelete(item, appPreferences)

        override fun deleteItem(
            index: Int,
            item: BaseItem,
        ) {
            deleteItem(context, mediaManagementService, item) {
                viewModelScope.launchDefault {
                    refreshItem(item.id)
                }
            }
        }

        private suspend fun refreshItem(itemId: UUID) {
            try {
                val position = position.value
                val searchResult =
                    if (combinedMode) {
                        state.value.combinedResults
                    } else {
                        state.value.includedSearchableTypes.getOrNull(position.row)?.let {
                            state.value.results[it]
                        }
                    } ?: return
                val items = (searchResult as? SearchResult.Success)?.items ?: return

                Timber.v("Item refresh: position=%s", position)
                val item = items.getOrNull(position.column)
                // Exact item deleted (eg a movie) or deleted item was within the series
                if (item != null && item.id == itemId) {
                    val newItem =
                        api.userLibraryApi
                            .getItem(item.id)
                            .content
                            .let { BaseItem(it) }
                    val newList =
                        SearchResult.Success(
                            items.toMutableList().apply {
                                set(position.column, newItem)
                            },
                        )
                    if (combinedMode) {
                        _state.update {
                            it.copy(
                                combinedResults = newList,
                            )
                        }
                    } else {
                        state.value.includedSearchableTypes.getOrNull(position.row)?.let { type ->
                            state.value.results[type] = newList
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error refreshing item %s", itemId)
                showToast(context, "Error refreshing")
            }
        }

        override fun setWatched(
            position: Int,
            itemId: UUID,
            played: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setWatched(itemId, played)
                refreshItem(itemId)
            }
        }

        override fun setFavorite(
            position: Int,
            itemId: UUID,
            favorite: Boolean,
        ) {
            viewModelScope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
                favoriteWatchManager.setFavorite(itemId, favorite)
                refreshItem(itemId)
            }
        }

        override fun isAdministrator(): Boolean = serverRepository.currentUserDto?.policy?.isAdministrator == true

        override fun sendReportFor(itemId: UUID) = serverReportService.sendMediaReportFor(itemId)

        fun fetchProgramForDialog(programId: UUID) {
            _programDialogState.update { it.copy(loading = DataLoadingState.Loading) }
            viewModelScope.launchDefault {
                try {
                    val result = liveTvService.fetchProgramForDialog(programId)
                    _programDialogState.update { it.copy(loading = DataLoadingState.Success(result)) }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching program $programId")
                    _programDialogState.update { it.copy(loading = DataLoadingState.Error(ex)) }
                }
            }
        }

        fun cancelRecording(
            series: Boolean,
            timerId: String?,
        ) {
            viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
                try {
                    val result = liveTvService.cancelRecording(series, timerId)
                    // TODO update program card?
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error canceling timer %s, series=%s", timerId, series)
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        fun record(
            programId: UUID,
            series: Boolean,
        ) {
            viewModelScope.launchIO {
                try {
                    liveTvService.record(programId, series)
                    // TODO update program card?
                    refreshItem(programId)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Error recording %s, series=%s", programId, series)
                    showToast(context, "Error: ${ex.localizedMessage}")
                }
            }
        }

        fun onClickExcludeSearchableType(type: BaseItemKind) {
            viewModelScope.launchIO {
                try {
                    val state = state.value
                    val updateSearch: Boolean
                    val newExcludes =
                        state.excludedSearchableTypes.toMutableList().apply {
                            if (type in this) {
                                updateSearch = true
                                remove(type)
                            } else {
                                // If combined, need to update, otherwise just hide the newly excluded results
                                updateSearch = combinedMode
                                add(type)
                            }
                        }
                    Timber.v("newExcludes=%s", newExcludes)
                    serverRepository.currentUser?.id?.let { userId ->
                        keyValueService.save(userId, EXCLUDED_SEARCHABLE_TYPES_KEY, newExcludes)
                    }
                    val searchableTypes = determineSearchableTypes(newExcludes)
                    _state.update {
                        it.copy(
                            includedSearchableTypes = searchableTypes,
                            excludedSearchableTypes = newExcludes,
                        )
                    }
                    warmIndex(searchableTypes)
                    if (updateSearch && currentQuery.isNotNullOrBlank()) {
                        Timber.d("Need to update search")
                        search(currentQuery, combinedMode, true)
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception toggling %s", type)
                    showToast(context, "An error occurred: ${ex.localizedMessage}")
                }
            }
        }

        fun onClickExcludeDiscover() {
            viewModelScope.launchIO {
                try {
                    val newIncludeDiscover = state.value.discoverEnabled.not()
                    Timber.v("newIncludeDiscover=%s", newIncludeDiscover)
                    serverRepository.currentUser?.id?.let { userId ->
                        keyValueService.save(userId, INCLUDE_DISCOVER_KEY, newIncludeDiscover)
                    }
                    _state.update {
                        it.copy(
                            discoverEnabled = newIncludeDiscover,
                        )
                    }
                    if (newIncludeDiscover) {
                        currentQuery?.takeIf { it.isNotNullOrBlank() }?.let { query ->
                            searchSeerr(query)
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception discover")
                    showToast(context, "An error occurred: ${ex.localizedMessage}")
                }
            }
        }

        companion object {
            private const val EXCLUDED_SEARCHABLE_TYPES_KEY = "excludedSearchableTypes"
            private const val INCLUDE_DISCOVER_KEY = "searchIncludeDiscover"
        }
    }

sealed interface SearchResult {
    data object NoQuery : SearchResult

    data object Searching : SearchResult

    data class Error(
        val ex: Exception,
    ) : SearchResult

    data class Success(
        val items: List<BaseItem?>,
    ) : SearchResult

    data class SuccessSeerr(
        val items: List<DiscoverItem>,
    ) : SearchResult
}

data class SearchState(
    val results: SnapshotStateMap<BaseItemKind, SearchResult> = SnapshotStateMap(),
    val seerrResults: SearchResult = SearchResult.NoQuery,
    val combinedResults: SearchResult = SearchResult.NoQuery,
    val possibleSearchableTypes: List<BaseItemKind> = emptyList(),
    val includedSearchableTypes: List<BaseItemKind> = emptyList(),
    val excludedSearchableTypes: List<BaseItemKind> = emptyList(),
    val discoverEnabled: Boolean = true,
)

private val allSearchableTypes =
    listOf(
        BaseItemKind.MOVIE,
        BaseItemKind.SERIES,
        BaseItemKind.EPISODE,
        BaseItemKind.BOX_SET,
        BaseItemKind.PERSON,
        BaseItemKind.TV_CHANNEL,
        BaseItemKind.LIVE_TV_PROGRAM,
        BaseItemKind.MUSIC_ALBUM,
        BaseItemKind.MUSIC_ARTIST,
        BaseItemKind.AUDIO,
        BaseItemKind.MUSIC_VIDEO,
        BaseItemKind.PLAYLIST,
        BaseItemKind.VIDEO,
        BaseItemKind.PHOTO,
        BaseItemKind.PHOTO_ALBUM,
    )

private const val SEARCH_LIMIT = 50
