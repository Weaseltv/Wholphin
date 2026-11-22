package com.github.damontecres.wholphin.ui.nav

import android.content.Context
import org.jellyfin.sdk.model.api.CollectionType

/**
 * WeaselFin nav rail icon set (Set A).
 *
 * Drawables live ONLY in the `weaselfin` flavor's res/drawable. Main code therefore cannot
 * reference `R.drawable.ic_nav_*` directly - that would fail to compile for every upstream
 * flavor - so they are resolved BY NAME at runtime. On any flavor without them the lookup
 * returns 0, [WeaselNavIcons.navIconFor] returns null, and the original Font Awesome glyph
 * path runs unchanged. That is what keeps this flavor-only with no upstream behaviour diff.
 *
 * Builtins match on their stable [NavDrawerItem.id]; server libraries match on NAME, because
 * a library's id differs per server and cannot be baked into a build.
 *
 * Colors are NOT here any more: the Neon Board tints every glyph the same way (`low` at
 * rest, the section accent when selected), see [sectionAccent].
 */
internal object WeaselNavIcons {
    /** Key is a NavDrawerItem id for builtins, or a lowercased library name for server items. */
    private val map: Map<String, String> =
        mapOf(
            "a_favorites" to "ic_nav_favorites",
            "a_discover" to "ic_nav_requests",
            "movies" to "ic_nav_movies",
            "sports" to "ic_nav_sports",
            "tv shows" to "ic_nav_tvshows",
            "stand up comedy" to "ic_nav_standup",
            "ufc" to "ic_nav_ufc",
            "boxing" to "ic_nav_boxing",
            "4k movies (lan)" to "ic_nav_4k_movies",
            "4k tv shows (lan)" to "ic_nav_4k_tv",
        )

    /** Fixed items, rendered outside the dynamic list. */
    const val SEARCH = "ic_nav_search"
    const val HOME = "ic_nav_home"
    const val SETTINGS = "ic_nav_settings"
    const val MASCOT = "weaselplex_mascot"
    const val LOCKUP = "weaselplex_lockup"

    private fun resId(
        context: Context,
        name: String,
    ): Int = context.resources.getIdentifier(name, "drawable", context.packageName)

    /** Drawable for a nav item, or null to fall back to upstream's glyph. */
    fun navIconFor(
        context: Context,
        item: NavDrawerItem,
    ): Int? {
        val key = if (item is ServerNavDrawerItem) item.name.trim().lowercase() else item.id
        val entry =
            map[key] ?: (item as? ServerNavDrawerItem)?.type?.let {
                when (it) {
                    CollectionType.MOVIES -> "ic_nav_movies"
                    CollectionType.TVSHOWS -> "ic_nav_tvshows"
                    CollectionType.BOXSETS -> "ic_nav_collections"
                    else -> null
                }
            } ?: return null
        return fixed(context, entry)
    }

    /** Drawable for a fixed entry, or null when this flavor does not ship it. */
    fun fixed(
        context: Context,
        name: String,
    ): Int? = resId(context, name).takeIf { it != 0 }
}
