package com.github.damontecres.wholphin.ui.detail

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

/*
 * The kid title page (owner mockup kid-title-page.html, 2026-10-23): for kid and unknown sessions, movie and series pages
 * show a big Play button with round Start over / Watchlist / Shuffle buttons, a short metadata line and the real
 * description, and only the More like this (and Seasons) rows. Adults keep today's pages. These are its decisions,
 * without UI.
 */

/** What the big Play button says */
sealed interface KidPlayLabel {
    data object Play : KidPlayLabel

    /** A partly watched movie */
    data object KeepWatching : KidPlayLabel

    /** "PLAY S2 · E5" */
    data class Episode(
        val season: Int,
        val episode: Int,
    ) : KidPlayLabel
}

/**
 * The big Play button: it plays [itemId] from [positionMs]. A partly watched movie shows its [progress] (0-1) and the
 * [remaining] time under it; an episode shows its [episodeTitle] ("Next up: ...").
 */
data class KidPlay(
    val itemId: UUID,
    val label: KidPlayLabel,
    val positionMs: Long,
    val progress: Float? = null,
    val remaining: Duration? = null,
    val episodeTitle: String? = null,
)

/** The round buttons beside Play, in order */
enum class KidAction { START_OVER, WATCHLIST, SHUFFLE }

object KidTitlePage {
    /**
     * Where [item] resumes, or null to play it from the start (review rounds 1 and 2): a saved position resumes when the
     * runtime is unknown, or when it is before the end; a stale one at or past the end doesn't
     */
    fun resumePosition(item: BaseItemDto): Duration? {
        val position = item.userData?.playbackPositionTicks?.ticks ?: return null
        val runtime = item.runTimeTicks?.ticks?.takeIf { it > Duration.ZERO }
        return position.takeIf { it > Duration.ZERO && (runtime == null || it < runtime) }
    }

    /**
     * A movie plays from where it was left ("Keep watching"), else from the start ([resumePosition]). The progress bar
     * and time left show only when the runtime is known.
     */
    fun moviePlay(movie: BaseItemDto): KidPlay = itemPlay(movie)

    /**
     * An episode's own Play button, on the season/episode and episode pages (owner 2026-10-23): like a movie's,
     * "Keep watching" with its progress when it was started
     */
    fun episodePlay(episode: BaseItemDto): KidPlay = itemPlay(episode)

    private fun itemPlay(movie: BaseItemDto): KidPlay {
        val resume = resumePosition(movie)
        val runtime = movie.runTimeTicks?.ticks?.takeIf { it > Duration.ZERO }
        return KidPlay(
            itemId = movie.id,
            label = if (resume != null) KidPlayLabel.KeepWatching else KidPlayLabel.Play,
            positionMs = resume?.inWholeMilliseconds ?: 0L,
            progress = if (resume != null && runtime != null) (resume / runtime).toFloat().coerceIn(0f, 1f) else null,
            remaining = if (resume != null && runtime != null) runtime - resume else null,
        )
    }

    /** The round buttons for a movie or an episode: Start over only when it is partly watched */
    fun movieActions(play: KidPlay): List<KidAction> =
        buildList {
            if (play.label == KidPlayLabel.KeepWatching) add(KidAction.START_OVER)
            add(KidAction.WATCHLIST)
        }

    /** The round buttons for a series */
    val seriesActions: List<KidAction> = listOf(KidAction.WATCHLIST, KidAction.SHUFFLE)

    /**
     * The episode a series' Play button plays. Both lists come through the kid content filter (contract 10: an episode's
     * own rating, else its series'), so every episode here, specials included, is one this kid may watch.
     *
     * - The server's next up (which includes one in progress), when the filter kept it.
     * - Otherwise [episodes] (every allowed episode, in order): regular episodes, or the specials when there are no
     *   regular ones. The next up may have been hidden (review round 1), so the first one after the last watched one
     *   that is unwatched or started; nothing watched yet gives the first.
     * - If none follows it, the first unwatched or started one anywhere; if every one is watched, the first, to watch
     *   again. Null only when there is no allowed episode at all.
     */
    fun nextEpisode(
        nextUp: List<BaseItemDto>,
        episodes: List<BaseItemDto>,
    ): BaseItemDto? {
        nextUp.firstOrNull { it.type == BaseItemKind.EPISODE }?.let { return it }
        val playable = episodes.filter { it.type == BaseItemKind.EPISODE }
        val pool = playable.filter { (it.parentIndexNumber ?: 1) > 0 }.ifEmpty { playable }
        val toWatch: (BaseItemDto) -> Boolean = { it.userData?.played != true || it.resumable }
        val lastWatched = pool.indexOfLast { it.userData?.played == true }
        return pool.drop(lastWatched + 1).firstOrNull(toWatch)
            ?: pool.firstOrNull(toWatch)
            ?: pool.firstOrNull()
    }

    private val BaseItemDto.resumable: Boolean get() = resumePosition(this) != null

    /** One page of a series' episodes as the kid content filter returned it */
    data class EpisodePage(
        val items: List<BaseItemDto>,
        val totalRecordCount: Int?,
    )

    /**
     * Every episode of a series this kid may watch, in order, paging to the series' real end (review rounds 1 and 2): a
     * page the filter emptied or cut isn't the end. The filter lowers a page's TotalRecordCount by the items it removed
     * from it, so after a page that started at startIndex and returned n items the server has `total - startIndex - n`
     * more, exactly. A page without a count (the request asks for one, so a server that drops it) ends paging only when
     * it returned nothing; a partly filtered page never does. [SAFETY_PAGES] stops a server that never ends, and says so.
     */
    suspend fun allowedEpisodes(loadPage: suspend (startIndex: Int, limit: Int) -> EpisodePage): List<BaseItemDto> {
        val episodes = mutableListOf<BaseItemDto>()
        var startIndex = 0
        repeat(SAFETY_PAGES) {
            val page = loadPage(startIndex, EPISODE_PAGE)
            episodes += page.items
            val more = page.totalRecordCount?.let { it - startIndex - page.items.size > 0 } ?: page.items.isNotEmpty()
            if (!more) return episodes
            startIndex += EPISODE_PAGE
        }
        Timber.w("Stopped looking through a series' episodes after %d pages", SAFETY_PAGES)
        return episodes
    }

    const val EPISODE_PAGE = 100

    /** Only for a server that never ends: 20,000 episodes */
    const val SAFETY_PAGES = 200

    /** A series' Play button for [episode], resuming it as [resumePosition] says; null when there is no episode to play */
    fun seriesPlay(episode: BaseItemDto?): KidPlay? {
        episode ?: return null
        val season = episode.parentIndexNumber
        val number = episode.indexNumber
        return KidPlay(
            itemId = episode.id,
            label = if (season != null && number != null) KidPlayLabel.Episode(season, number) else KidPlayLabel.Play,
            positionMs = resumePosition(episode)?.inWholeMilliseconds ?: 0L,
            episodeTitle = episode.name?.takeIf { it.isNotBlank() },
        )
    }

    /** "S1 E9" for an episode's metadata line, or null without both numbers */
    fun episodeCode(episode: BaseItemDto): String? {
        val season = episode.parentIndexNumber ?: return null
        val number = episode.indexNumber ?: return null
        return "S$season E$number"
    }

    /** An episode's age rating: its own, else its series' */
    fun episodeRating(
        episode: BaseItemDto,
        seriesRating: String?,
    ): String? = episode.officialRating?.takeIf { it.isNotBlank() } ?: seriesRating?.takeIf { it.isNotBlank() }

    /** How many lines of the description show: the real description, capped so the buttons stay on screen */
    fun overviewLines(compact: Boolean): Int = if (compact) COMPACT_OVERVIEW_LINES else OVERVIEW_LINES

    const val OVERVIEW_LINES = 8
    const val COMPACT_OVERVIEW_LINES = 6
}
