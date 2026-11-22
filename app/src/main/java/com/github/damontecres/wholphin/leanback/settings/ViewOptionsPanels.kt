package com.github.damontecres.wholphin.leanback.settings

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionsType
import com.github.damontecres.wholphin.ui.detail.collection.CollectionViewOptions

/*
 * The view options side panels (no-Compose UI, 2026-11-22): the old `ViewOptionsDialog` and
 * `CollectionViewOptionsDialog` content as [PreferencePanel]s, headed "View options".
 */

/**
 * The view options of the Watchlist / folder pages and the library pages (`ViewOptionsDialog`): the grid layout shows
 * [ViewOptions.GRID_OPTIONS] (layout, image type, aspect ratio, details, backdrop, titles, columns, spacing, content
 * scale, reset), the list layouts [ViewOptions.LIST_OPTIONS] (layout, details, backdrop, spacing, reset); the rows a
 * layout does not use are hidden, so changing the layout keeps the focused row. Reset restores [defaults].
 */
internal fun viewOptionsPanel(
    value: ViewOptions,
    defaults: ViewOptions,
    accent: Int,
    onChange: (ViewOptions) -> Unit,
): PreferencePanel<ViewOptions> {
    // LIST_OPTIONS is GRID_OPTIONS without the grid-only rows, in the same order
    val always: (ViewOptions) -> Boolean = { true }
    val gridOnly: (ViewOptions) -> Boolean = { it.type == ViewOptionsType.GRID }
    return PreferencePanel(
        title = R.string.view_options,
        initial = value,
        preferences =
            ViewOptions.GRID_OPTIONS.map { pref ->
                PanelPreference(
                    preference = pref,
                    visible = if (ViewOptions.LIST_OPTIONS.any { it === pref }) always else gridOnly,
                    onClick = if (pref === ViewOptions.ViewOptionsReset) ({ defaults }) else null,
                )
            },
        accent = accent,
        onChange = onChange,
    )
}

/**
 * A collection's view options (`CollectionViewOptionsDialog`): Separate types, then the card options of one grid
 * ([CollectionViewOptions.MixedOptions]: image type, aspect ratio, titles, columns, spacing, image scaling, reset) or,
 * with separate rows, [CollectionViewOptions.SeparateOptions] (titles, reset). Reset restores [defaults].
 */
internal fun collectionViewOptionsPanel(
    value: CollectionViewOptions,
    accent: Int,
    onChange: (CollectionViewOptions) -> Unit,
    defaults: CollectionViewOptions = CollectionViewOptions(),
): PreferencePanel<CollectionViewOptions> {
    // SeparateOptions is MixedOptions without the rows a single grid needs, in the same order
    val always: (CollectionViewOptions) -> Boolean = { true }
    val mixedOnly: (CollectionViewOptions) -> Boolean = { !it.separateTypes }
    val cardRows =
        CollectionViewOptions.MixedOptions.map { pref ->
            PanelPreference(
                preference =
                    pref.forPart<ViewOptions, CollectionViewOptions>(
                        get = { it.cardViewOptions },
                        set = { options, card -> options.copy(cardViewOptions = card) },
                    ),
                visible = if (CollectionViewOptions.SeparateOptions.any { it === pref }) always else mixedOnly,
                onClick = if (pref === ViewOptions.ViewOptionsReset) ({ defaults }) else null,
            )
        }
    return PreferencePanel(
        title = R.string.view_options,
        initial = value,
        preferences = listOf(PanelPreference(CollectionViewOptions.SeparateTypes)) + cardRows,
        accent = accent,
        onChange = onChange,
    )
}
