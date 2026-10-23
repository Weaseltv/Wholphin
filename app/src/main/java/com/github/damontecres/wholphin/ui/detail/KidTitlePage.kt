package com.github.damontecres.wholphin.ui.detail

import com.github.damontecres.wholphin.ui.timeRemaining
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
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
     * A movie plays from where it was left ("Keep watching"), else from the start. Any saved position resumes (review
     * round 1); the progress bar and time left show only when the runtime is known.
     */
    fun moviePlay(movie: BaseItemDto): KidPlay {
        val position = movie.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO
        val runtime = movie.runTimeTicks?.ticks
        val remaining = movie.timeRemaining
        val partlyWatched = position > Duration.ZERO
        return KidPlay(
            itemId = movie.id,
            label = if (partlyWatched) KidPlayLabel.KeepWatching else KidPlayLabel.Play,
            positionMs = if (partlyWatched) position.inWholeMilliseconds else 0L,
            progress =
                if (partlyWatched && runtime != null && runtime > Duration.ZERO) {
                    (position / runtime).toFloat().coerceIn(0f, 1f)
                } else {
                    null
                },
            remaining = if (partlyWatched) remaining else null,
        )
    }

    /** The round buttons for a movie: Start over only when it is partly watched */
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

    private val BaseItemDto.resumable: Boolean get() = (userData?.playbackPositionTicks ?: 0L) > 0L

    /** One page of a series' episodes as the kid content filter returned it */
    data class EpisodePage(
        val items: List<BaseItemDto>,
        val totalRecordCount: Int?,
    )

    /**
     * Every episode of a series this kid may watch, in order, paging until the series ends (review round 1): a page the
     * filter emptied isn't the end. The filter lowers a page's TotalRecordCount by the items it removed from it, so after
     * a page that started at startIndex and returned n items the server has `total - startIndex - n` more. At most
     * [MAX_EPISODE_PAGES] pages.
     */
    suspend fun allowedEpisodes(loadPage: suspend (startIndex: Int, limit: Int) -> EpisodePage): List<BaseItemDto> {
        val episodes = mutableListOf<BaseItemDto>()
        var startIndex = 0
        repeat(MAX_EPISODE_PAGES) {
            val page = loadPage(startIndex, EPISODE_PAGE)
            episodes += page.items
            val more =
                page.totalRecordCount?.let { it - startIndex - page.items.size > 0 }
                    // Without a count, only a full page says there may be more
                    ?: (page.items.size >= EPISODE_PAGE)
            if (!more) return episodes
            startIndex += EPISODE_PAGE
        }
        return episodes
    }

    const val EPISODE_PAGE = 100
    const val MAX_EPISODE_PAGES = 20

    /**
     * A series' Play button for [episode], resuming it from any saved position (review round 1); null when there is no
     * episode to play
     */
    fun seriesPlay(episode: BaseItemDto?): KidPlay? {
        episode ?: return null
        val season = episode.parentIndexNumber
        val number = episode.indexNumber
        return KidPlay(
            itemId = episode.id,
            label = if (season != null && number != null) KidPlayLabel.Episode(season, number) else KidPlayLabel.Play,
            positionMs =
                episode.userData
                    ?.playbackPositionTicks
                    ?.ticks
                    ?.inWholeMilliseconds ?: 0L,
            episodeTitle = episode.name?.takeIf { it.isNotBlank() },
        )
    }

    /** How many lines of the description show: the real description, capped so the buttons stay on screen */
    fun overviewLines(compact: Boolean): Int = if (compact) COMPACT_OVERVIEW_LINES else OVERVIEW_LINES

    const val OVERVIEW_LINES = 8
    const val COMPACT_OVERVIEW_LINES = 6
}
