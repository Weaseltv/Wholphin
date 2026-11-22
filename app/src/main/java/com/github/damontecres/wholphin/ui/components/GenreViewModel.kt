package com.github.damontecres.wholphin.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.alphabetSortName
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.detail.CardGridItem
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.GetGenresRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.mayakapps.kache.InMemoryKache
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

/*
 * The Genres tab's ViewModel and data, moved out of GenreCardGrid.kt for the no-Compose UI (2026-11-22). Unchanged except that Genre is no longer a Compose @Stable class.
 */

@HiltViewModel(assistedFactory = GenreViewModel.Factory::class)
class GenreViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        private val imageUrlService: ImageUrlService,
        private val serverRepository: ServerRepository,
        val navigationManager: NavigationManager,
        @Assisted private val itemId: UUID,
        @Assisted private val includeItemTypes: List<BaseItemKind>?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                itemId: UUID,
                includeItemTypes: List<BaseItemKind>?,
            ): GenreViewModel
        }

        private val _state = MutableStateFlow(GenreGridState())
        val state: StateFlow<GenreGridState> = _state

        /** The card width the tab was last loaded for; see [init] */
        private var loadedFor: Int? = null

        /**
         * Load the tab.
         *
         * Speed plan 18f (2026-11): a tab's content leaves the screen when another tab is chosen, and coming back to it
         * ran this again: the loading picture and every request once more. What is already loaded (or loading) for
         * this card width is now kept. A failed load still loads again.
         */
        fun init(cardWidthPx: Int) {
            val item = state.value.item
            if (loadedFor == cardWidthPx && (item is DataLoadingState.Success || item is DataLoadingState.Loading)) return
            loadedFor = cardWidthPx
            _state.update { it.copy(item = DataLoadingState.Loading) }
            viewModelScope.launchIO {
                try {
                    val item =
                        api.userLibraryApi.getItem(itemId = itemId).content.let {
                            BaseItem(it, false)
                        }
                    val request =
                        GetGenresRequest(
                            userId = serverRepository.currentUser?.id,
                            parentId = itemId,
                            fields = SlimItemFields,
                            includeItemTypes = includeItemTypes,
                        )
                    val genres =
                        GetGenresRequestHandler
                            .execute(api, request)
                            .content.items
                            .map {
                                Genre(
                                    id = it.id,
                                    name = it.name ?: "",
                                    imageUrl = null,
                                    sortName = it.alphabetSortName,
                                )
                            }
                    _state.update {
                        it.copy(
                            item = DataLoadingState.Success(item),
                            genres = genres,
                        )
                    }
                    val genreToUrl =
                        getGenreImageMap(
                            api = api,
                            userId = serverRepository.currentUser?.id,
                            scope = viewModelScope,
                            imageUrlService = imageUrlService,
                            genres = genres.map { it.id },
                            parentId = itemId,
                            includeItemTypes = includeItemTypes,
                            cardWidthPx = cardWidthPx,
                            kidSession = isKidOrUnknown(serverRepository.currentUser),
                        )
                    val genresWithImages =
                        genres.map {
                            it.copy(
                                imageUrl = genreToUrl[it.id],
                            )
                        }
                    _state.update {
                        it.copy(
                            genres = genresWithImages,
                        )
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching genres")
                    _state.update { it.copy(item = DataLoadingState.Error(ex)) }
                }
            }
        }

        suspend fun positionOfLetter(letter: Char): Int =
            withContext(WholphinDispatchers.IO) {
                GetGenresRequestHandler.countMatching(
                    api = api,
                    request =
                        GetGenresRequest(
                            parentId = itemId,
                            nameLessThan = letter.toString(),
                            limit = 0,
                            enableTotalRecordCount = true,
                            includeItemTypes = includeItemTypes,
                        ),
                )
            }
    }

data class GenreGridState(
    val item: DataLoadingState<BaseItem> = DataLoadingState.Pending,
    val genres: List<Genre> = emptyList(),
)

data class GenreCacheKey(
    val userId: UUID?,
    val parentId: UUID,
)

/**
 * Kid content rules (review round 6): whether the signed-in profile is a kid or unknown one, which never uses the
 * cached genre backdrops or the saved Collections lists; adults keep their caching
 */
internal fun isKidOrUnknown(user: JellyfinUser?): Boolean = BuildConfig.KID_PROFILES_ENABLED && user?.profileKind != ProfileKind.ADULT

private val genreCache by lazy {
    InMemoryKache<GenreCacheKey, Map<UUID, String?>>(8) {
        expireAfterWriteDuration = 2.hours
    }
}

/**
 * Create a mapping from genre IDs to image URLs using random items within each genre
 */
suspend fun getGenreImageMap(
    api: ApiClient,
    userId: UUID?,
    scope: CoroutineScope,
    imageUrlService: ImageUrlService,
    genres: List<UUID>,
    parentId: UUID,
    includeItemTypes: List<BaseItemKind>?,
    cardWidthPx: Int?,
    useCache: Boolean = true,
    kidSession: Boolean = false,
): Map<UUID, String?> {
    val key = GenreCacheKey(userId, parentId)
    if (useCache && !kidSession) {
        genreCache.getIfAvailable(key)?.let {
            Timber.v("Got cached entry")
            return it
        }
    }
    val genreToUrl = ConcurrentHashMap<UUID, String?>()
    val semaphore = Semaphore(4)
    genres
        .map { genreId ->
            scope.async(WholphinDispatchers.IO) {
                semaphore.withPermit {
                    val item =
                        GetItemsRequestHandler
                            .execute(
                                api,
                                GetItemsRequest(
                                    userId = userId,
                                    parentId = parentId,
                                    recursive = true,
                                    limit = 1,
                                    sortBy = listOf(ItemSortBy.RANDOM),
                                    fields = listOf(ItemFields.GENRES),
                                    imageTypes = listOf(ImageType.BACKDROP),
                                    imageTypeLimit = 1,
                                    includeItemTypes = includeItemTypes,
                                    genreIds = listOf(genreId),
                                    enableTotalRecordCount = false,
                                ),
                            ).content.items
                            .firstOrNull()
                    if (item != null) {
                        genreToUrl[genreId] =
                            imageUrlService.getItemImageUrl(
                                itemId = item.id,
                                itemType = item.type,
                                seriesId = null,
                                useSeriesForPrimary = true,
                                imageType = ImageType.BACKDROP,
                                imageTags = item.imageTags.orEmpty(),
                                fillWidth = cardWidthPx,
                                backdropTags = item.backdropImageTags.orEmpty(),
                            )
                    }
                }
            }
        }.awaitAll()
    if (!kidSession) genreCache.put(key, genreToUrl)
    return genreToUrl
}

data class Genre(
    val id: UUID,
    val name: String,
    val imageUrl: String?,
    override val sortName: String = name,
) : CardGridItem {
    override val gridId: String get() = id.toString()
    override val playable: Boolean = false
}
