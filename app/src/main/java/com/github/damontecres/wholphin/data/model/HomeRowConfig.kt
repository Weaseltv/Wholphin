@file:UseSerializers(UUIDSerializer::class)

package com.github.damontecres.wholphin.data.model

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

@Serializable
sealed interface HomeRowConfig {
    val viewOptions: HomeRowViewOptions

    fun updateViewOptions(viewOptions: HomeRowViewOptions): HomeRowConfig

    /**
     * Continue watching media that the user has started but not finished
     */
    @Serializable
    @SerialName("ContinueWatching")
    data class ContinueWatching(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): ContinueWatching = this.copy(viewOptions = viewOptions)
    }

    /**
     * Next up row for next episodes in a series the user has started
     */
    @Serializable
    @SerialName("NextUp")
    data class NextUp(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): NextUp = this.copy(viewOptions = viewOptions)
    }

    /**
     * Combined [ContinueWatching] and [NextUp]
     */
    @Serializable
    @SerialName("ContinueWatchingCombined")
    data class ContinueWatchingCombined(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): ContinueWatchingCombined = this.copy(viewOptions = viewOptions)
    }

    /**
     * Media recently added to a library
     */
    @Serializable
    @SerialName("RecentlyAdded")
    data class RecentlyAdded(
        val parentId: UUID,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): RecentlyAdded = this.copy(viewOptions = viewOptions)
    }

    /**
     * Media recently released (premiere date) in a library
     */
    @Serializable
    @SerialName("RecentlyReleased")
    data class RecentlyReleased(
        val parentId: UUID,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): RecentlyReleased = this.copy(viewOptions = viewOptions)
    }

    /**
     * Row of a genres in a library
     */
    @Serializable
    @SerialName("Genres")
    data class Genres(
        val parentId: UUID,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions.genreDefault,
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): Genres = this.copy(viewOptions = viewOptions)
    }

    /**
     * Row of a studios in a library
     */
    @Serializable
    @SerialName("Studios")
    data class Studios(
        val parentId: UUID,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions.genreDefault,
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): Studios = this.copy(viewOptions = viewOptions)
    }

    /**
     * Favorites for a specific type
     */
    @Serializable
    @SerialName("Favorite")
    data class Favorite(
        val kind: BaseItemKind,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): Favorite = this.copy(viewOptions = viewOptions)
    }

    /**
     * Currently recording
     */
    @Serializable
    @SerialName("Recordings")
    data class Recordings(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): Recordings = this.copy(viewOptions = viewOptions)
    }

    /**
     * Programs on now/recommended
     */
    @Serializable
    @SerialName("TvPrograms")
    data class TvPrograms(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions.liveTvDefault,
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): TvPrograms = this.copy(viewOptions = viewOptions)
    }

    /**
     * Live TV channels
     */
    @Serializable
    @SerialName("TvChannels")
    data class TvChannels(
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions.liveTvDefault,
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): TvChannels = this.copy(viewOptions = viewOptions)
    }

    /**
     * Fetch suggestions from [com.github.damontecres.wholphin.services.SuggestionService] for the given parent ID
     */
    @Serializable
    @SerialName("Suggestions")
    data class Suggestions(
        val parentId: UUID,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): Suggestions = this.copy(viewOptions = viewOptions)
    }

    /**
     * Fetch by parent ID such as a library, collection, or playlist with optional simple sorting
     */
    @Serializable
    @SerialName("ByParent")
    data class ByParent(
        val parentId: UUID,
        val recursive: Boolean = false,
        val sort: SortAndDirection? = null,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): ByParent = this.copy(viewOptions = viewOptions)
    }

    /**
     * An arbitrary [GetItemsRequest] allowing to query for anything
     */
    @Serializable
    @SerialName("GetItems")
    data class GetItems(
        val name: String,
        val getItems: GetItemsRequest,
        override val viewOptions: HomeRowViewOptions = HomeRowViewOptions(),
    ) : HomeRowConfig {
        override fun updateViewOptions(viewOptions: HomeRowViewOptions): GetItems = this.copy(viewOptions = viewOptions)
    }
}

/**
 * Root class for home page settings
 *
 * Contains the list of rows and a version
 */
@Serializable
@SerialName("HomePageSettings")
data class HomePageSettings(
    val rows: List<HomeRowConfig>,
    val version: Int,
    val layoutDefaultsRevision: Int = 0,
) {
    companion object {
        val EMPTY = HomePageSettings(listOf(), SUPPORTED_HOME_PAGE_SETTINGS_VERSION)
    }
}

/**
 * This is the max version supported by this version of the app
 */
const val SUPPORTED_HOME_PAGE_SETTINGS_VERSION = 1

/**
 * View options for displaying a row
 *
 * Allows for changing things like height or aspect ratio
 */
@Serializable
data class HomeRowViewOptions(
    val heightDp: Int = BuildConfig.DEFAULT_CARD_HEIGHT_DP,
    val spacing: Int = 14,
    val contentScale: PrefContentScale = PrefContentScale.FILL,
    val aspectRatio: AspectRatio = AspectRatio.TALL,
    val imageType: ViewOptionImageType = ViewOptionImageType.PRIMARY,
    val showTitles: Boolean = false,
    val useSeries: Boolean = true,
    val episodeContentScale: PrefContentScale = PrefContentScale.FILL,
    val episodeAspectRatio: AspectRatio = AspectRatio.TALL,
    val episodeImageType: ViewOptionImageType = ViewOptionImageType.PRIMARY,
    val verticalPaddingDp: Int = 4,
    val extraVerticalPaddingDp: Int? = if (BuildConfig.FLAVOR == "weaselfin") 12 else null,
    val dividerGapDp: Int = if (BuildConfig.FLAVOR == "weaselfin") 12 else 8,
    val rowGapDp: Int = 8,
    val edgePaddingDp: Int? = if (BuildConfig.FLAVOR == "weaselfin") 4 else null,
    val endPaddingDp: Int? = if (BuildConfig.FLAVOR == "weaselfin") 16 else null,
    val titleDividerGapDp: Int = if (BuildConfig.FLAVOR == "weaselfin") 3 else 4,
    val titleSizeSp: Int = if (BuildConfig.FLAVOR == "weaselfin") 30 else 22,
    val titleLetterSpacingTenthsSp: Int = 0,
    val countSizeSp: Int = if (BuildConfig.FLAVOR == "weaselfin") 14 else 12,
    val countOpacityPercent: Int = 100,
    val countEndPaddingDp: Int = if (BuildConfig.FLAVOR == "weaselfin") 15 else 8,
    val dividerThicknessDp: Int = if (BuildConfig.FLAVOR == "weaselfin") 5 else 1,
    val dividerGlowDp: Int = if (BuildConfig.FLAVOR == "weaselfin") 10 else 12,
    val cardAppearance: HomeCardAppearance = HomeCardAppearance(),
    val dividerGlowStrength: Int = if (BuildConfig.FLAVOR == "weaselfin") 70 else 35,
) {
    companion object {
        val genreDefault =
            HomeRowViewOptions(
                heightDp = if (BuildConfig.FLAVOR == "weaselfin") BuildConfig.DEFAULT_CARD_HEIGHT_DP else Cards.HEIGHT_EPISODE,
                aspectRatio = if (BuildConfig.FLAVOR == "weaselfin") AspectRatio.TALL else AspectRatio.WIDE,
            )

        val liveTvDefault =
            HomeRowViewOptions(
                heightDp = if (BuildConfig.FLAVOR == "weaselfin") BuildConfig.DEFAULT_CARD_HEIGHT_DP else Cards.HEIGHT_LIVE_TV,
                aspectRatio = if (BuildConfig.FLAVOR == "weaselfin") AspectRatio.TALL else AspectRatio.WIDE,
                contentScale = if (BuildConfig.FLAVOR == "weaselfin") PrefContentScale.FILL else PrefContentScale.FIT,
            )
    }
}
