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
    /** A movie plays from where it was left ("Keep watching"), else from the start */
    fun moviePlay(movie: BaseItemDto): KidPlay {
        val position = movie.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO
        val runtime = movie.runTimeTicks?.ticks
        val remaining = movie.timeRemaining
        val partlyWatched = position > Duration.ZERO && remaining != null
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
     * The episode a series' Play button plays: the server's next up (which includes one in progress), else the first
     * regular episode, else the first one at all (eg only specials). Both lists come through the kid content filter, so
     * an episode this kid can't watch is never offered; null when there is none.
     */
    fun nextEpisode(
        nextUp: List<BaseItemDto>,
        episodes: List<BaseItemDto>,
    ): BaseItemDto? {
        nextUp.firstOrNull { it.type == BaseItemKind.EPISODE }?.let { return it }
        val playable = episodes.filter { it.type == BaseItemKind.EPISODE }
        return playable.firstOrNull { (it.parentIndexNumber ?: 1) > 0 } ?: playable.firstOrNull()
    }

    /** A series' Play button for [episode], resuming it if it was started; null when there is no episode to play */
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
