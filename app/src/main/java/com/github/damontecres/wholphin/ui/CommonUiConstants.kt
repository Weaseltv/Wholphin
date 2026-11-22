package com.github.damontecres.wholphin.ui

import org.jellyfin.sdk.model.api.ItemFields

// This file is for constants used for the UI

const val DEFAULT_PAGE_SIZE = 100

/**
 * The [ItemFields] to fetch for detailed paged and playback
 */
val DetailItemFields =
    listOf(
        ItemFields.OVERVIEW,
        ItemFields.TRICKPLAY,
        ItemFields.SORT_NAME,
        ItemFields.CHAPTERS,
        ItemFields.MEDIA_SOURCES,
        ItemFields.MEDIA_SOURCE_COUNT,
        ItemFields.CAN_DELETE,
        ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
    )

/**
 * [ItemFields] for higher level displays such as grids or rows
 */
val SlimItemFields =
    listOf(
        ItemFields.OVERVIEW,
        ItemFields.SORT_NAME,
        ItemFields.MEDIA_SOURCE_COUNT,
        ItemFields.CAN_DELETE,
    )

/**
 * ItemFields for displaying items in rows such as in a [com.github.damontecres.wholphin.ui.cards.ItemRow] with [com.github.damontecres.wholphin.ui.cards.SeasonCard]
 */
val ItemRowFields = SlimItemFields + ItemFields.PRIMARY_IMAGE_ASPECT_RATIO

val HomeItemFields =
    listOf(
        ItemFields.OVERVIEW,
        ItemFields.CAN_DELETE,
    )

val PhotoItemFields =
    DetailItemFields +
        listOf(
            ItemFields.WIDTH,
            ItemFields.HEIGHT,
        )

object AspectRatios {
    const val WIDE = 16f / 9f
    const val FOUR_THREE = 4f / 3f
    const val TALL = 2f / 3f
    const val SQUARE = 1f

    const val MIN = TALL
}

enum class AspectRatio(
    val ratio: Float,
) {
    TALL(AspectRatios.TALL),
    WIDE(AspectRatios.WIDE),
    FOUR_THREE(AspectRatios.FOUR_THREE),
    SQUARE(AspectRatios.SQUARE),
}

/** Card heights in dp shared by the home row settings (the old Compose `Cards` object's constants) */
object Cards {
    const val HEIGHT_2X3_DP = 172
    const val HEIGHT_EPISODE = 128
}
