package com.github.damontecres.wholphin.data.model

import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import kotlinx.serialization.Serializable

/** The US JustWatch lists shown as Home rows, and how many of their titles each row shows. */
@Serializable
enum class JustWatchHomeList(
    val size: Int,
    @param:StringRes val kicker: Int,
    @param:StringRes val title: Int,
    @param:StringRes val fullTitle: Int,
) {
    /** justwatch.com/us/streaming-charts?ct=daily */
    MOVIE_CHART(25, R.string.home_movies_kicker, R.string.home_streaming_charts, R.string.home_movie_streaming_charts),

    /** justwatch.com/us/streaming-charts?ct=daily&t=shows */
    SHOW_CHART(25, R.string.home_tv_shows_kicker, R.string.home_streaming_charts, R.string.home_show_streaming_charts),

    /** justwatch.com/us */
    POPULAR(50, R.string.quick_list_popular, R.string.home_movies_and_shows, R.string.home_popular_full),

    /** justwatch.com/us?sort_by=trending_7_day */
    TRENDING(50, R.string.quick_list_trending, R.string.home_movies_and_shows, R.string.home_trending_full),

    /** The "Top 10 movies this week" on justwatch.com: the 7-day trending movies */
    TOP_10_MOVIES(10, R.string.quick_list_top_10, R.string.home_movies_this_week, R.string.home_top_10_movies_full),

    /** The "Top 10 TV shows this week" on justwatch.com: the 7-day trending shows */
    TOP_10_SHOWS(10, R.string.quick_list_top_10, R.string.home_shows_this_week, R.string.home_top_10_shows_full),
    ;

    /** Whether the row has both movies and shows, so its cards say which each one is. */
    val mixed: Boolean get() = this == POPULAR || this == TRENDING
}
