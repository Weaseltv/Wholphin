package com.github.damontecres.wholphin.ui.theme

import android.content.Context

/** Keep the next loading illustration across app restarts without loading artwork over the network. */
internal object LoadingArtworkRotation {
    private const val PREFERENCES = "weaselplex_loading_artwork"
    private const val NEXT_INDEX = "next_index"
    private const val NEXT_KIDS_INDEX = "next_kids_index"
    private const val LAST_PROFILE_KIDS = "last_profile_kids"

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

    /** Kids profiles' own set (owner, 2026-10-23); every other profile keeps [resourceNames]. */
    val kidsResourceNames =
        listOf(
            "weaselplex_loading_kids_funny_banana_peel_cake",
            "weaselplex_loading_kids_crayon_mural",
            "weaselplex_loading_kids_funny_puppy_pile",
            "weaselplex_loading_kids_birthday_party",
            "weaselplex_loading_kids_movie_coral_reef",
            "weaselplex_loading_kids_funny_trampoline_flip",
            "weaselplex_loading_kids_tea_party",
            "weaselplex_loading_kids_funny_tiny_sweater",
            "weaselplex_loading_kids_campfire_marshmallows",
            "weaselplex_loading_kids_movie_balloon_house",
            "weaselplex_loading_kids_funny_bubblegum_bubble",
            "weaselplex_loading_kids_blanket_fort",
            "weaselplex_loading_kids_funny_spaghetti_explosion",
            "weaselplex_loading_kids_pirate_treasure",
            "weaselplex_loading_kids_movie_toy_bedroom",
            "weaselplex_loading_kids_funny_goat_hoodie",
            "weaselplex_loading_kids_block_tower",
            "weaselplex_loading_kids_funny_duck_chase",
            "weaselplex_loading_kids_bubble_bath",
            "weaselplex_loading_kids_movie_pride_rock",
            "weaselplex_loading_kids_funny_pancake_hat",
            "weaselplex_loading_kids_puddle_splash",
            "weaselplex_loading_kids_funny_kite_liftoff",
            "weaselplex_loading_kids_bedtime_story",
            "weaselplex_loading_kids_movie_rescue_pups",
            "weaselplex_loading_kids_funny_brain_freeze",
            "weaselplex_loading_kids_ice_cream_truck",
            "weaselplex_loading_kids_funny_cat_hat",
            "weaselplex_loading_kids_dino_ride",
            "weaselplex_loading_kids_movie_ice_palace",
        )

    fun resourceNames(kids: Boolean): List<String> = if (kids) kidsResourceNames else resourceNames

    /**
     * Called once per visible loading mark, rather than once per recomposition. The kids set keeps its own
     * place, so switching profiles never skips pictures in either set.
     */
    @Synchronized
    fun next(
        context: Context,
        artworks: List<Int>,
        kids: Boolean = false,
    ): Int? {
        if (artworks.isEmpty()) return null
        val key = if (kids) NEXT_KIDS_INDEX else NEXT_INDEX
        val preferences = preferences(context)
        val index = preferences.getInt(key, 0).mod(artworks.size)
        preferences.edit().putInt(key, (index + 1) % artworks.size).apply()
        return artworks[index]
    }

    /** Whether the last signed-in profile was a kid, for loading shown at app start before a profile is restored. */
    fun lastProfileWasKids(context: Context): Boolean = preferences(context).getBoolean(LAST_PROFILE_KIDS, false)

    fun rememberProfile(
        context: Context,
        kids: Boolean,
    ) {
        preferences(context).edit().putBoolean(LAST_PROFILE_KIDS, kids).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
