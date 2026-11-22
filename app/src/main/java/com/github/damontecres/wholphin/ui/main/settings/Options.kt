package com.github.damontecres.wholphin.ui.main.settings

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.AppChoicePreference
import com.github.damontecres.wholphin.preferences.AppClickablePreference
import com.github.damontecres.wholphin.preferences.AppSliderPreference
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.preferences.PreferenceGroup

/*
 * A Home row's display preferences (card height, spacing, image type...), Compose-free (moved unchanged from
 * HomeRowSettings.kt, 2026-11-22).
 */

/** The per-row view option preferences and the groups each row type shows */
internal object Options {
    /** The 2x3 card height constant of ui/UiConstants.kt, kept here so this file stays Compose-free */
    const val HEIGHT_2X3_DP = 172

    /** The episode card height constant of ui/UiConstants.kt */
    const val HEIGHT_EPISODE = 128

    val ViewOptionsCardHeight =
        AppSliderPreference<HomeRowViewOptions>(
            title = R.string.height,
            defaultValue = BuildConfig.DEFAULT_CARD_HEIGHT_DP.toLong(),
            min = 64L,
            max = HEIGHT_2X3_DP + 64L,
            interval = 4,
            getter = { it.heightDp.toLong() },
            setter = { prefs, value -> prefs.copy(heightDp = value.toInt()) },
        )
    val ViewOptionsSpacing =
        AppSliderPreference<HomeRowViewOptions>(
            title = R.string.spacing,
            defaultValue = HomeRowViewOptions().spacing.toLong(),
            min = 0,
            max = 64,
            interval = 2,
            getter = { it.spacing.toLong() },
            setter = { prefs, value -> prefs.copy(spacing = value.toInt()) },
        )

    val ViewOptionsContentScale =
        AppChoicePreference<HomeRowViewOptions, PrefContentScale>(
            title = R.string.global_content_scale,
            defaultValue = PrefContentScale.FIT,
            displayValues = R.array.content_scale,
            getter = { it.contentScale },
            setter = { viewOptions, value -> viewOptions.copy(contentScale = value) },
            indexToValue = { PrefContentScale.forNumber(it) },
            valueToIndex = { it.number },
        )

    val ViewOptionsAspectRatio =
        AppChoicePreference<HomeRowViewOptions, AspectRatio>(
            title = R.string.aspect_ratio,
            defaultValue = AspectRatio.TALL,
            displayValues = R.array.aspect_ratios,
            getter = { it.aspectRatio },
            setter = { viewOptions, value -> viewOptions.copy(aspectRatio = value) },
            indexToValue = { AspectRatio.entries[it] },
            valueToIndex = { it.ordinal },
        )

    val ViewOptionsShowTitles =
        AppSwitchPreference<HomeRowViewOptions>(
            title = R.string.show_titles,
            defaultValue = true,
            getter = { it.showTitles },
            setter = { vo, value -> vo.copy(showTitles = value) },
        )

    val ViewOptionsUseSeries =
        AppSwitchPreference<HomeRowViewOptions>(
            title = R.string.use_series,
            defaultValue = true,
            getter = { it.useSeries },
            setter = { vo, value -> vo.copy(useSeries = value) },
        )

    val ViewOptionsImageType =
        AppChoicePreference<HomeRowViewOptions, ViewOptionImageType>(
            title = R.string.image_type,
            defaultValue = ViewOptionImageType.PRIMARY,
            displayValues = R.array.image_types,
            getter = { it.imageType },
            setter = { viewOptions, value ->
                val aspectRatio =
                    when (value) {
                        ViewOptionImageType.PRIMARY -> AspectRatio.TALL
                        ViewOptionImageType.THUMB -> AspectRatio.WIDE
                    }
                viewOptions.copy(imageType = value, aspectRatio = aspectRatio)
            },
            indexToValue = { ViewOptionImageType.entries[it] },
            valueToIndex = { it.ordinal },
        )

    val ViewOptionsApplyAll =
        AppClickablePreference<HomeRowViewOptions>(
            title = R.string.apply_all_rows,
        )

    val ViewOptionsReset =
        AppClickablePreference<HomeRowViewOptions>(
            title = R.string.reset,
        )

    val ViewOptionsUseThumb =
        AppClickablePreference<HomeRowViewOptions>(
            title = R.string.use_thumb_images,
        )

    val ViewOptionsEpisodeContentScale =
        AppChoicePreference<HomeRowViewOptions, PrefContentScale>(
            title = R.string.global_content_scale,
            defaultValue = PrefContentScale.FIT,
            displayValues = R.array.content_scale,
            getter = { it.episodeContentScale },
            setter = { viewOptions, value -> viewOptions.copy(episodeContentScale = value) },
            indexToValue = { PrefContentScale.forNumber(it) },
            valueToIndex = { it.number },
        )

    val ViewOptionsEpisodeAspectRatio =
        AppChoicePreference<HomeRowViewOptions, AspectRatio>(
            title = R.string.aspect_ratio,
            defaultValue = AspectRatio.TALL,
            displayValues = R.array.aspect_ratios,
            getter = { it.episodeAspectRatio },
            setter = { viewOptions, value -> viewOptions.copy(episodeAspectRatio = value) },
            indexToValue = { AspectRatio.entries[it] },
            valueToIndex = { it.ordinal },
        )

    val ViewOptionsEpisodeImageType =
        AppChoicePreference<HomeRowViewOptions, ViewOptionImageType>(
            title = R.string.image_type,
            defaultValue = ViewOptionImageType.PRIMARY,
            displayValues = R.array.image_types,
            getter = { it.episodeImageType },
            setter = { viewOptions, value ->
                val aspectRatio =
                    when (value) {
                        ViewOptionImageType.PRIMARY -> AspectRatio.TALL
                        ViewOptionImageType.THUMB -> AspectRatio.WIDE
                    }
                viewOptions.copy(episodeImageType = value, episodeAspectRatio = aspectRatio)
            },
            indexToValue = { ViewOptionImageType.entries[it] },
            valueToIndex = { it.ordinal },
        )

    val OPTIONS =
        listOf(
            PreferenceGroup(
                title = R.string.general,
                preferences =
                    listOf(
                        ViewOptionsCardHeight,
                        ViewOptionsSpacing,
                        ViewOptionsShowTitles,
                        ViewOptionsImageType,
                        ViewOptionsAspectRatio,
                        ViewOptionsContentScale,
                        ViewOptionsUseSeries,
                    ),
            ),
            PreferenceGroup(
                title = R.string.more,
                preferences =
                    listOf(
//                      ViewOptionsApplyAll,
                        ViewOptionsUseThumb,
                        ViewOptionsReset,
                    ),
            ),
        )

    val OPTIONS_EPISODES =
        listOf(
            PreferenceGroup(
                title = R.string.general,
                preferences =
                    listOf(
                        ViewOptionsCardHeight,
                        ViewOptionsSpacing,
                        ViewOptionsShowTitles,
                        ViewOptionsImageType,
                        ViewOptionsAspectRatio,
                        ViewOptionsContentScale,
                    ),
            ),
            PreferenceGroup(
                title = R.string.for_episodes,
                preferences =
                    listOf(
                        ViewOptionsUseSeries,
                        ViewOptionsEpisodeImageType,
                        ViewOptionsEpisodeAspectRatio,
                        ViewOptionsEpisodeContentScale,
                    ),
            ),
            PreferenceGroup(
                title = R.string.more,
                preferences =
                    listOf(
                        ViewOptionsUseThumb,
                        ViewOptionsReset,
                    ),
            ),
        )

    val GENRE_OPTIONS =
        listOf(
            PreferenceGroup(
                title = R.string.general,
                preferences =
                    listOf(
                        ViewOptionsCardHeight,
                        ViewOptionsSpacing,
                        ViewOptionsReset,
                    ),
            ),
        )
}
