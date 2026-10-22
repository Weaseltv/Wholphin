package com.github.damontecres.wholphin.kids

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The owner's content rules for every kids profile (contract section 10, v1.3), as a pure function of item metadata.
 * No Android or network dependencies: [KidContentFilter] gathers the facts, this decides.
 */
object KidContentRules {
    /** What the rules need to know about one item, as the server sent it */
    data class ItemFacts(
        val id: String?,
        /** Jellyfin `Type` (a `BaseItemKind` serial name, eg "Movie"); null or unknown is treated as a title */
        val type: String?,
        val name: String? = null,
        val officialRating: String? = null,
        /** Null when the server didn't send them (`fields=Genres` missing) */
        val genres: List<String>? = null,
        /** `PremiereDate` as the server sent it (ISO-8601, UTC) */
        val premiereDate: String? = null,
        val productionYear: Int? = null,
        /** Null when the server didn't send them (`fields=Settings` missing) */
        val lockedFields: List<String>? = null,
        val seriesId: String? = null,
    )

    sealed interface Decision {
        data object Show : Decision

        data class Hide(
            val reason: HideReason,
        ) : Decision
    }

    enum class HideReason {
        /** Rule 1 */
        HORROR,

        /** Rule 2 */
        NO_US_RATING,

        /** Rule 5 */
        ABOVE_MAX,

        /** Interpretation: an episode or season of a hidden series */
        SERIES_HIDDEN,

        /** The session's ceiling is unknown: no title is shown */
        UNKNOWN_CEILING,

        /** Fail closed: the metadata a rule needs couldn't be loaded */
        MISSING_FACTS,

        /** A box set with no visible child */
        EMPTY_BOX_SET,
    }

    /** The item types the rules don't apply to (interpretations); a [BOX_SET] needs a visible child instead */
    private val CONTAINER_TYPES =
        setOf(
            "AggregateFolder",
            "CollectionFolder",
            "UserView",
            "Folder",
            "UserRootFolder",
            "BasePluginFolder",
            "PlaylistsFolder",
            "ManualPlaylistsFolder",
            "Channel",
            "ChannelFolderItem",
            "Person",
            "MusicArtist",
            "Genre",
            "MusicGenre",
            "Studio",
            "Year",
            "Playlist",
        )

    const val BOX_SET = "BoxSet"
    const val SERIES = "Series"
    private const val SEASON = "Season"
    private const val EPISODE = "Episode"
    private const val MOVIE = "Movie"
    private val GENRE_TYPES = setOf("Genre", "MusicGenre")

    /** Rule 2: the usable US ratings and rule 5's scale */
    private val LEVELS =
        mapOf(
            "G" to 0,
            "TV-Y" to 0,
            "TV-Y7" to 0,
            "TV-Y7-FV" to 0,
            "TV-G" to 0,
            "PG" to 1,
            "TV-PG" to 1,
            "PG-13" to 2,
            "TV-14" to 2,
            "R" to 3,
            "TV-MA" to 3,
            "NC-17" to 4,
        )

    private const val LOCKED_RATING = "OfficialRating"

    /** Rule 4: PG premiered before this date (UTC) counts as PG-13 */
    private val PG13_INTRODUCED: LocalDate = LocalDate.of(1984, 7, 1)
    private const val PG13_YEAR = 1984

    /** Whether the rules leave [type] alone (a container); a box set still needs a visible child */
    fun isContainer(type: String?): Boolean = type != null && type in CONTAINER_TYPES

    /** Episodes and seasons are decided together with their series */
    fun needsSeries(type: String?): Boolean = type == EPISODE || type == SEASON

    /**
     * The profile's maximum level (section 10) from the session ceiling: null means unknown, and no title is shown.
     * Adults ([ProfileCeiling.Unlimited]) are never filtered, so they never get here; were they to, 4 shows everything.
     */
    fun maxLevelFor(ceiling: ProfileCeiling): Int? =
        when (ceiling) {
            is ProfileCeiling.Rating -> maxLevelFor(ceiling.maxParentalRating)
            ProfileCeiling.Unlimited -> 4
            ProfileCeiling.Unknown -> null
        }

    fun maxLevelFor(maxParentalRating: Int): Int =
        when {
            maxParentalRating >= 18 -> 4
            maxParentalRating >= 17 -> 3
            maxParentalRating >= 13 -> 2
            maxParentalRating >= 10 -> 1
            else -> 0
        }

    /** Rule 2's normalization: trimmed, uppercased, a leading `US-` stripped; null if nothing is left */
    fun normalizeRating(raw: String?): String? {
        val value = raw?.trim()?.uppercase() ?: return null
        val stripped = if (value.startsWith("US-")) value.removePrefix("US-").trim() else value
        return stripped.ifEmpty { null }
    }

    /** Rule 5's level of a normalized rating; null if it isn't a usable US rating (rule 2) */
    fun levelOf(normalized: String?): Int? = normalized?.let { LEVELS[it] }

    fun isHorror(genres: List<String>?): Boolean = genres?.any { it.contains("horror", ignoreCase = true) } == true

    /**
     * Whether [item]'s metadata is complete enough to decide it without loading it: its genres were sent, and so were its
     * locked fields when rule 3 could change the outcome (a PG movie rule 4 would raise). An episode or season also needs
     * its series ([needsSeries]), loaded separately.
     */
    fun hasFacts(item: ItemFacts): Boolean {
        if (isContainer(item.type) || item.type == BOX_SET) return true
        // Episodes and seasons go by their series' genres (rule 1), which are loaded with the series
        if (item.genres == null && !needsSeries(item.type)) return false
        if (item.lockedFields == null && eraRuleApplies(item)) return false
        return true
    }

    /**
     * Decides [item] for a profile whose maximum level is [maxLevel] (null: unknown ceiling). [series] is the parent series
     * of an episode or season; without it, those are hidden (fail closed). Box sets are decided by the caller from their
     * children ([decideBoxSet]); here one only passes as a container.
     */
    fun decide(
        item: ItemFacts,
        maxLevel: Int?,
        series: ItemFacts? = null,
    ): Decision {
        val type = item.type
        if (type in GENRE_TYPES) {
            // The "Horror" genre itself is hidden
            return if (item.name?.contains("horror", ignoreCase = true) == true) hide(HideReason.HORROR) else Decision.Show
        }
        if (isContainer(type) || type == BOX_SET) return Decision.Show
        if (maxLevel == null) return hide(HideReason.UNKNOWN_CEILING)
        if (needsSeries(type)) {
            if (series == null) return hide(HideReason.MISSING_FACTS)
            // Interpretation: hidden whenever the series is hidden, whatever the episode's own rating
            val seriesDecision = decideTitle(series, maxLevel, inheritedGenres = null, inheritedRating = null)
            if (seriesDecision is Decision.Hide) {
                return if (seriesDecision.reason == HideReason.HORROR) seriesDecision else hide(HideReason.SERIES_HIDDEN)
            }
            return decideTitle(item, maxLevel, inheritedGenres = series.genres, inheritedRating = series.officialRating)
        }
        return decideTitle(item, maxLevel, inheritedGenres = null, inheritedRating = null)
    }

    /** A box set is shown only while at least one of its children is (interpretation) */
    fun decideBoxSet(anyVisibleChild: Boolean): Decision = if (anyVisibleChild) Decision.Show else hide(HideReason.EMPTY_BOX_SET)

    private fun decideTitle(
        item: ItemFacts,
        maxLevel: Int,
        inheritedGenres: List<String>?,
        inheritedRating: String?,
    ): Decision {
        // Rule 1: beats everything, including a hand-set rating
        if (item.genres == null && inheritedGenres == null) return hide(HideReason.MISSING_FACTS)
        if (isHorror(item.genres) || isHorror(inheritedGenres)) return hide(HideReason.HORROR)
        // Rule 2: episodes and seasons without their own rating use the series'
        val rating = normalizeRating(item.officialRating) ?: normalizeRating(inheritedRating)
        var level = levelOf(rating) ?: return hide(HideReason.NO_US_RATING)
        // Rule 3: a hand-set rating is used as it is
        val locked = item.lockedFields?.any { it.equals(LOCKED_RATING, ignoreCase = true) } == true
        // Rule 4: pre-PG-13 era
        if (!locked && rating == "PG" && eraRuleApplies(item)) {
            if (item.lockedFields == null) return hide(HideReason.MISSING_FACTS)
            level = LEVELS.getValue("PG-13")
        }
        // Rule 5
        return if (level <= maxLevel) Decision.Show else hide(HideReason.ABOVE_MAX)
    }

    /** Rule 4's condition, without rule 3: a PG [MOVIE] premiered before 1984-07-01 (or, undated, made by 1984) */
    private fun eraRuleApplies(item: ItemFacts): Boolean {
        if (item.type != MOVIE || normalizeRating(item.officialRating) != "PG") return false
        val premiered = premiereDateOf(item.premiereDate)
        return when {
            premiered != null -> premiered.isBefore(PG13_INTRODUCED)

            item.productionYear != null -> item.productionYear <= PG13_YEAR

            // Neither: counts as PG-13 (interpretation, fail closed)
            else -> true
        }
    }

    /** The UTC calendar date of a Jellyfin date, or null if absent or unreadable */
    fun premiereDateOf(value: String?): LocalDate? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        } catch (_: DateTimeParseException) {
            // Jellyfin sends UTC; a value without an offset is read as UTC
            try {
                LocalDate.parse(text.take(10))
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun hide(reason: HideReason) = Decision.Hide(reason)
}
