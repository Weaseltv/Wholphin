package com.github.damontecres.wholphin.ui.theme

import android.content.Context

/** Keep the next loading illustration across app restarts without loading artwork over the network. */
internal object LoadingArtworkRotation {
    private const val PREFERENCES = "weaselplex_loading_artwork"
    private const val NEXT_INDEX = "next_index"

    val resourceNames =
        listOf(
            "weaselplex_loading_cinema",
            "weaselplex_loading_director",
            "weaselplex_loading_orbit",
            "weaselplex_loading_television",
            "weaselplex_loading_rooftop",
            "weaselplex_loading_popcorn",
            "weaselplex_loading_expedition",
            "weaselplex_loading_dj",
            "weaselplex_loading_submarine",
            "weaselplex_loading_arcade",
            "weaselplex_loading_castle",
            "weaselplex_loading_tickets",
            "weaselplex_loading_chubby_midnight_snack",
            "weaselplex_loading_chubby_pool",
            "weaselplex_loading_chubby_recliner",
            "weaselplex_loading_chubby_snacks",
            "weaselplex_loading_drive",
            "weaselplex_loading_hologram",
            "weaselplex_loading_motorcycle",
            "weaselplex_loading_movie_bullet_dodge",
            "weaselplex_loading_movie_desert_rv",
            "weaselplex_loading_movie_jungle_commando",
            "weaselplex_loading_movie_mob_boss",
            "weaselplex_loading_movie_museum_steps",
            "weaselplex_loading_movie_space_knight",
            "weaselplex_loading_penthouse",
            "weaselplex_loading_popcorn_explosion",
            "weaselplex_loading_ripped_flex",
            "weaselplex_loading_ripped_gym",
            "weaselplex_loading_skatepark",
            "weaselplex_loading_stadium",
            "weaselplex_loading_tangled",
        )

    /** Called once per visible loading mark, rather than once per recomposition. */
    @Synchronized
    fun next(
        context: Context,
        artworks: List<Int>,
    ): Int? {
        if (artworks.isEmpty()) return null
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val index = preferences.getInt(NEXT_INDEX, 0).mod(artworks.size)
        preferences.edit().putInt(NEXT_INDEX, (index + 1) % artworks.size).apply()
        return artworks[index]
    }
}
