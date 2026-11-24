@file:UseSerializers(UUIDSerializer::class)

package com.github.damontecres.wholphin.ui.detail.series

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/*
 * The season/episode page's positions, moved out of the Compose file SeriesOverview.kt (no-Compose UI, 2026-11-22) so
 * the navigation destinations and the View page share them.
 */

@Serializable
data class SeasonEpisode(
    val season: Int,
    val episode: Int,
    val episodeEnd: Int? = null,
)

@Serializable
data class SeasonEpisodeIds(
    val seasonId: UUID,
    val seasonNumber: Int?,
    val episodeId: UUID?,
    val episodeNumber: Int?,
)

@Serializable
data class SeriesOverviewPosition(
    val seasonTabIndex: Int,
    val episodeRowIndex: Int,
)
