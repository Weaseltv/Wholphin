package com.github.damontecres.wholphin.ui.components

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetNextUpRequestHandler
import com.github.damontecres.wholphin.util.GetResumeItemsRequestHandler
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import java.time.LocalDateTime
import java.util.UUID

/** The Recommended tab rows of a movie library (were private in RecommendedMovie.kt) */
fun recommendedMovieRows(parentId: UUID): List<RecommendedRow<*>> =
    listOf(
        RecommendedRow(
            title = R.string.continue_watching,
            handler = GetResumeItemsRequestHandler,
            request =
                GetResumeItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    enableUserData = true,
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_released,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    sortBy =
                        listOf(
                            ItemSortBy.PREMIERE_DATE,
                            ItemSortBy.SORT_NAME,
                        ),
                    sortOrder =
                        listOf(
                            SortOrder.DESCENDING,
                            SortOrder.ASCENDING,
                        ),
                    enableTotalRecordCount = false,
                    maxPremiereDate = LocalDateTime.now(),
                ),
        ),
        RecommendedRow(
            title = R.string.recently_added,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.top_unwatched,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    isPlayed = false,
                    sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
    )

/** The Recommended tab rows of a TV show library (were private in RecommendedTvShow.kt) */
fun recommendedTvShowRows(
    parentId: UUID,
    enableRewatching: Boolean,
): List<RecommendedRow<*>> =
    listOf(
        RecommendedRow(
            title = R.string.continue_watching,
            handler = GetResumeItemsRequestHandler,
            request =
                GetResumeItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    enableUserData = true,
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.next_up,
            handler = GetNextUpRequestHandler,
            request =
                GetNextUpRequest(
                    fields = SlimItemFields,
                    imageTypeLimit = 1,
                    parentId = parentId,
                    enableResumable = false,
                    enableUserData = true,
                    enableRewatching = enableRewatching,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_released,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    sortBy =
                        listOf(
                            ItemSortBy.PREMIERE_DATE,
                            ItemSortBy.SERIES_SORT_NAME,
                            ItemSortBy.AIRED_EPISODE_ORDER,
                        ),
                    sortOrder =
                        listOf(
                            SortOrder.DESCENDING,
                            SortOrder.ASCENDING,
                            SortOrder.DESCENDING,
                        ),
                    enableTotalRecordCount = false,
                    maxPremiereDate = LocalDateTime.now(),
                    isUnaired = false,
                ),
        ),
        RecommendedRow(
            title = R.string.recently_added,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
        RecommendedRow(
            title = R.string.top_unwatched,
            handler = GetItemsRequestHandler,
            request =
                GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    enableUserData = true,
                    isPlayed = false,
                    sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    enableTotalRecordCount = false,
                ),
        ),
    )
