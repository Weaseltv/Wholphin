package com.github.damontecres.wholphin.data.model

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import kotlinx.serialization.Serializable

/**
 * The server-made lists shown as Home rows (owner 2026-11-03), and how many of their titles each row
 * shows. `justwatch-home.sh` on the media server saves each one daily from the server's smart
 * collection of the same name, under the entry's name in lower case (see JustWatchRowService).
 *
 * The rows before 2026-11-03 (the JustWatch charts, Popular, Trending and the two Top 10s) are no
 * longer entries: a saved layout's row for one of them doesn't decode, so it's dropped.
 */
@Serializable
enum class JustWatchHomeList(
    val size: Int,
    @param:StringRes val kicker: Int,
    @param:StringRes val title: Int,
    @param:StringRes val fullTitle: Int,
    /** Whether its titles are TV shows (else movies), for the row's color */
    val shows: Boolean = false,
) {
    /** The "Trending Movies" collection: TMDB's daily trending movies */
    TRENDING_MOVIES(25, R.string.quick_list_trending, R.string.home_movies_kicker, R.string.home_trending_movies_full),

    /** The "Trending TV" collection: TMDB's daily trending shows */
    TRENDING_SHOWS(25, R.string.quick_list_trending, R.string.home_tv_shows_kicker, R.string.home_trending_shows_full, shows = true),

    /** The "Recently Released - 90 Days" collection: movies released in the last 90 days, newest first */
    RECENTLY_RELEASED(25, R.string.home_90_days_kicker, R.string.recently_released, R.string.home_recently_released_full),

    /** The "Movie Roulette" collection: random movies, picked again each day */
    MOVIE_ROULETTE(25, R.string.home_movie_kicker, R.string.home_roulette, R.string.home_movie_roulette_full),
}
