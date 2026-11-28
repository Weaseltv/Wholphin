package com.github.damontecres.wholphin.leanback.theme

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeCardAppearance
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.ServerNavDrawerItem
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.Locale
import java.util.UUID

/*
 * Section/type accents and card appearance for the View UI: ARGB-int ports of the plain logic in
 * `ui/theme/Neon.kt`, `ui/theme/MediaCardStyle.kt` and `ui/theme/CollectionCardStyle.kt` (no-Compose UI).
 */

/** What the Compose tree provided to every page through CompositionLocals, set on each screen by the shell */
data class PageStyle(
    /** `LocalNeonSectionAccent` / `LocalNeonAccent`: the sidebar section this page belongs to */
    val sectionAccent: Int = RailAccents.HOME,
    /** `LocalHomeCardAppearance` */
    val homeCardAppearance: HomeCardAppearance = defaultHomeCardAppearance(),
    /** `LocalHomeMediaSettings` */
    val homeMediaSettings: HomePageResolvedSettings = HomePageResolvedSettings.EMPTY,
    /** `LocalPosterCountAppearance` (WeaselTV theme only) */
    val posterCountAppearance: HomeCardAppearance? = null,
    /** `LocalHomeRowAccent` */
    val rowAccent: Int? = null,
    /** `LocalHomeCardBorderAccent` */
    val cardBorderAccent: Int? = null,
    /** `LocalHidePosterTypeBadge` */
    val hidePosterTypeBadge: Boolean = false,
) {
    /** The appearance cards use: `LocalPosterCountAppearance.current ?: LocalHomeCardAppearance.current` */
    val cardAppearance: HomeCardAppearance get() = posterCountAppearance ?: homeCardAppearance

    /** `neonAccentFor(item)`: accent for an item's card under this page */
    fun accentFor(item: BaseItem?): Int = neonAccentFor(item, sectionAccent, rowAccent)

    /** `homeCardAccent(default)` */
    fun homeCardAccent(default: Int): Int =
        when (homeCardAppearance.accentIndex) {
            1 -> NeonColors.VOLT
            2 -> NeonColors.ORANGE
            3 -> NeonColors.ICE_WHITE
            4 -> NeonColors.CYAN
            5 -> NeonColors.FUCHSIA
            else -> rowAccent ?: default
        }
}

fun defaultHomeCardAppearance(): HomeCardAppearance =
    if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.CARD_APPEARANCE else HomeCardAppearance()

/** `ApprovedCollectionCardAppearance` */
val ApprovedCollectionCardAppearanceView: HomeCardAppearance =
    ApprovedHomeLayout.CARD_APPEARANCE.copy(borderOpacityPercent = 100)

object RailAccents {
    val SEARCH = NeonSections.Search.border
    val HOME = NeonSections.Home.border

    /** The phone apps' Settings yellow (`NeonBoard.electricYellow`, owner 2026-11-27; was ice white 0xFFEAF2FF) */
    val SETTINGS = 0xFFFFEA00.toInt()
}

/** Owner section palette, 2026-10-08: every sidebar section keeps its own color pair */
private val RAIL_ACCENT_BY_ID =
    mapOf(
        "a_favorites" to NeonSections.Watchlist.border,
        "a_discover" to NeonSections.Requests.border,
        "a_more" to NeonColors.VOLT,
    )
private val RAIL_ACCENT_BY_NAME =
    mapOf(
        "collections" to NeonSections.Collections.border,
        "movies" to NeonSections.Movies.border,
        "tv shows" to NeonSections.TvShows.border,
        "sports" to NeonSections.Sports.border,
        "stand up comedy" to NeonSections.StandUpComedy.border,
        "standup comedy" to NeonSections.StandUpComedy.border,
        "stand-up comedy" to NeonSections.StandUpComedy.border,
        "boxing" to NeonSections.Sports.border,
        "ufc" to NeonSections.Sports.border,
    )

/** Inherit the most recent sidebar section through nested details, dialogs and playback */
fun navigationSectionAccent(
    backStack: List<Destination>,
    items: List<NavDrawerItem>,
): Int {
    var accent = RailAccents.HOME
    backStack.forEach { destination ->
        accent =
            when (destination) {
                is Destination.Home -> {
                    RailAccents.HOME
                }

                is Destination.Search -> {
                    RailAccents.SEARCH
                }

                Destination.Favorites -> {
                    NeonSections.Watchlist.border
                }

                is Destination.Discover -> {
                    NeonSections.Requests.border
                }

                // "More user profile settings" opens from Settings and takes its yellow too (owner, 2026-11-28; was volt)
                is Destination.Settings, Destination.HomeSettings, is Destination.SubtitleSettings,
                is Destination.UpdateApp, Destination.License, Destination.Debug, Destination.UserAppPreferences,
                -> {
                    RailAccents.SETTINGS
                }

                else -> {
                    items
                        .filterIsInstance<ServerNavDrawerItem>()
                        .firstOrNull { it.destination == destination }
                        ?.let(::sectionAccent) ?: when (destination) {
                        is Destination.MediaItem -> mediaAccent(destination.type, accent)
                        is Destination.SeriesOverview -> mediaAccent(destination.type, accent)
                        is Destination.DiscoveredItem -> mediaAccent(destination.item.type.baseItemKind, accent)
                        else -> accent
                    }
                }
            }
    }
    return accent
}

/** Section accent for a nav rail item: its own neon, or its collection type's colour */
fun sectionAccent(item: NavDrawerItem): Int =
    when (item) {
        is ServerNavDrawerItem -> libraryAccent(item.name, item.type)
        else -> RAIL_ACCENT_BY_ID[item.id] ?: NeonColors.VOLT
    }

/** Library chrome shares the rail's named-library overrides and collection colours */
fun libraryAccent(
    name: String,
    type: CollectionType?,
): Int = RAIL_ACCENT_BY_NAME[name.trim().lowercase()] ?: collectionAccent(type)

/** Owner-supplied provider logo palette, used for Streaming Services poster focus */
fun streamingProviderAccent(name: String?): Int? =
    when (name?.trim()?.lowercase(Locale.ROOT)) {
        "netflix" -> 0xFFE50914.toInt()
        "disney+", "disney plus" -> 0xFF113CCF.toInt()
        "hulu" -> 0xFF1CE783.toInt()
        "max", "hbo max", "max (hbo)", "hbo" -> 0xFF002BE7.toInt()
        "prime video", "amazon prime video" -> 0xFF00A8E1.toInt()
        "paramount+", "paramount plus" -> 0xFF0064FF.toInt()
        "peacock" -> 0xFFFDB927.toInt()
        "apple tv", "apple tv+" -> 0xFFA2AAAD.toInt()
        "amc+", "amc plus" -> 0xFF00EEE6.toInt()
        "mgm+", "mgm plus" -> 0xFFEFBE73.toInt()
        "starz" -> 0xFFF5E000.toInt()
        "britbox" -> 0xFF66D3EB.toInt()
        "crunchyroll" -> 0xFFFF5E00.toInt()
        "hallmark", "hallmark+", "hallmark plus" -> 0xFF613790.toInt()
        "angel", "angel studios" -> 0xFFE8B923.toInt()
        else -> null
    }

/** Contrast overrides affect the provider's focus border only; its logo-colored glow stays */
fun streamingProviderBorderAccent(name: String?): Int? =
    when (name?.trim()?.lowercase(Locale.ROOT)) {
        "paramount+", "paramount plus", "hallmark", "hallmark+", "hallmark plus" -> streamingProviderAccent("Apple TV")
        "britbox", "crunchyroll" -> 0xFF000000.toInt()
        else -> null
    }

/** Resolve glow separately from solid ink/strokes throughout the shared Neon components */
fun neonGlowAccent(accent: Int): Int = NeonSections.glowFor(accent)

/** Mixed pages use media colors; Stand Up Comedy and Sports retain their library identity */
fun mediaAccent(
    kind: BaseItemKind?,
    section: Int?,
): Int =
    when {
        section == NeonSections.StandUpComedy.border || section == NeonSections.Sports.border -> section

        kind == BaseItemKind.MOVIE || kind == BaseItemKind.SERIES || kind == BaseItemKind.SEASON ||
            kind == BaseItemKind.EPISODE -> typeAccent(kind)

        else -> section ?: typeAccent(kind)
    }

/** Section accent for a library by its Jellyfin collection type */
fun collectionAccent(type: CollectionType?): Int =
    when (type) {
        CollectionType.MOVIES -> NeonSections.Movies.border
        CollectionType.TVSHOWS -> NeonSections.TvShows.border
        CollectionType.BOXSETS -> NeonSections.Collections.border
        else -> NeonColors.VOLT
    }

/** Type accent for an item: what its card, Play button, progress and chapters wear */
fun typeAccent(kind: BaseItemKind?): Int =
    when (kind) {
        BaseItemKind.MOVIE -> NeonSections.Movies.border
        BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE -> NeonSections.TvShows.border
        BaseItemKind.CHANNEL -> NeonColors.GREEN
        else -> NeonColors.VOLT
    }

/** Type accent for [item], or volt when there is none */
fun itemAccent(item: BaseItem?): Int = typeAccent(item?.type)

/** `neonAccentFor(item)` with the page's section and row accents passed in */
fun neonAccentFor(
    item: BaseItem?,
    sectionAccent: Int?,
    rowAccent: Int? = null,
): Int =
    rowAccent ?: if (item == null) {
        sectionAccent ?: NeonColors.VOLT
    } else if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES ||
        item.type == BaseItemKind.SEASON || item.type == BaseItemKind.EPISODE
    ) {
        mediaAccent(item.type, sectionAccent)
    } else {
        sectionAccent ?: itemAccent(item)
    }

/** Library pages use the same saved focus treatment as their Recently Added Home row */
fun homeMediaAppearanceView(
    settings: HomePageResolvedSettings,
    libraryId: UUID? = null,
): HomeCardAppearance {
    val matched =
        libraryId?.let { id ->
            settings.rows.firstOrNull {
                when (val row = it.config) {
                    is HomeRowConfig.RecentlyAdded -> row.parentId == id
                    is HomeRowConfig.RecentlyReleased -> row.parentId == id
                    else -> false
                }
            }
        }
    val standard =
        settings.rows.firstOrNull {
            it.config is HomeRowConfig.ContinueWatchingCombined || it.config is HomeRowConfig.ContinueWatching
        } ?: settings.rows.firstOrNull { it.config is HomeRowConfig.RecentlyAdded }
    return (matched ?: standard)?.config?.viewOptions?.cardAppearance
        ?: ApprovedCollectionCardAppearanceView
}

/** `WatchlistCardStyle`: the page style a Watchlist card of [item] uses (WeaselTV theme) */
fun PageStyle.forWatchlistItem(
    item: BaseItem?,
    libraries: List<Library>,
): PageStyle {
    if (!AppTheme.isNeon) return this
    val library = libraries.firstOrNull { it.itemId == item?.libraryId }
    val accent = library?.let { libraryAccent(it.name, it.collectionType) } ?: typeAccent(item?.type)
    return copy(
        homeCardAppearance = homeMediaAppearanceView(homeMediaSettings, item?.libraryId).copy(accentIndex = 0),
        rowAccent = accent,
        cardBorderAccent = null,
    )
}
