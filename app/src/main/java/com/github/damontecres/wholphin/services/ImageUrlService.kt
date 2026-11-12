package com.github.damontecres.wholphin.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.damontecres.wholphin.data.model.BaseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides image URLs for items with several convenience methods
 *
 * This is available in compose UI code via [com.github.damontecres.wholphin.ui.LocalImageUrlService]
 *
 * Addresses carry the image's tag when the item's data has it (speed plan, owner's go-ahead 2026-11-11). The server
 * makes the tag from the picture's file path and the date it recorded for the picture, so a tagged address always
 * means the same picture and the copy saved on the device is shown without asking the server first
 * (`WholphinCacheStrategy`). That date is also what the server compared when the app asked "has this changed?", so a
 * replaced picture is noticed in the same cases as before: when fresh item data arrives with its new tag. An address
 * without a tag is still checked with the server every time.
 */
@Singleton
class ImageUrlService
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        fun getItemImageUrl(
            itemId: UUID,
            itemType: BaseItemKind,
            seriesId: UUID?,
            useSeriesForPrimary: Boolean,
            imageType: ImageType,
            imageTags: Map<ImageType, String?>,
            backdropTags: List<String>,
            parentThumbId: UUID? = null,
            parentBackdropId: UUID? = null,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
            seriesPrimaryTag: String? = null,
            seriesLogoTag: String? = null,
            parentThumbTag: String? = null,
            parentBackdropTag: String? = null,
        ): String? {
            // The series' backdrop tag is only known when the series is also the item the backdrop is inherited from
            val seriesBackdropTag = parentBackdropTag.takeIf { seriesId != null && seriesId == parentBackdropId }
            val seriesTag = seriesPrimaryTag.takeIf { imageType == ImageType.PRIMARY }
            return when (imageType) {
                ImageType.LOGO -> {
                    if (seriesId != null && (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)) {
                        url(seriesId, imageType, fillWidth, fillHeight, seriesLogoTag)
                    } else {
                        url(itemId, imageType, fillWidth, fillHeight, imageTags[imageType])
                    }
                }

                ImageType.BACKDROP,
                -> {
                    if (seriesId != null && (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)) {
                        url(seriesId, imageType, fillWidth, fillHeight, seriesBackdropTag)
                    } else if (backdropTags.isNotEmpty()) {
                        url(itemId, imageType, fillWidth, fillHeight, backdropTags.first())
                    } else {
                        null
                    }
                }

                ImageType.THUMB -> {
                    if (useSeriesForPrimary && parentThumbId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        // Use parent's thumb
                        url(parentThumbId, imageType, fillWidth, fillHeight, parentThumbTag)
                    } else if (useSeriesForPrimary && parentBackdropId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        // No parent thumb, so use backdrop instead
                        url(parentBackdropId, ImageType.BACKDROP, fillWidth, fillHeight, parentBackdropTag)
                    } else if (parentThumbId != null && itemType == BaseItemKind.SEASON && imageType !in imageTags) {
                        url(parentThumbId, imageType, fillWidth, fillHeight, parentThumbTag)
                    } else if (useSeriesForPrimary &&
                        parentThumbId == null &&
                        itemType == BaseItemKind.EPISODE &&
                        imageType !in imageTags
                    ) {
                        // Workaround to fall back to episode image if no parent thumb
                        url(itemId, ImageType.PRIMARY, fillWidth, fillHeight, imageTags[ImageType.PRIMARY])
                    } else if (imageType !in imageTags && backdropTags.isNotEmpty()) {
                        // If no thumb, use backdrop if available
                        url(itemId, ImageType.BACKDROP, fillWidth, fillHeight, backdropTags.first())
                    } else {
                        url(itemId, imageType, fillWidth, fillHeight, imageTags[imageType])
                    }
                }

                ImageType.PRIMARY,
                ImageType.BANNER,
                -> {
                    if (useSeriesForPrimary && seriesId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        url(seriesId, imageType, fillWidth, fillHeight, seriesTag)
                    } else if (seriesId != null && itemType == BaseItemKind.SEASON && imageType !in imageTags) {
                        url(seriesId, imageType, fillWidth, fillHeight, seriesTag)
                    } else {
                        url(itemId, imageType, fillWidth, fillHeight, imageTags[imageType])
                    }
                }

                else -> {
                    url(itemId, imageType, fillWidth, fillHeight, imageTags[imageType])
                }
            }
        }

        private fun url(
            itemId: UUID,
            imageType: ImageType,
            fillWidth: Int?,
            fillHeight: Int?,
            tag: String?,
        ): String? = getItemImageUrl(itemId = itemId, imageType = imageType, fillWidth = fillWidth, fillHeight = fillHeight, tag = tag)

        fun getItemImageUrl(
            item: BaseItem?,
            imageType: ImageType,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
            useSeriesForPrimary: Boolean? = null,
        ): String? =
            if (item != null) {
                getItemImageUrl(
                    itemId = item.id,
                    itemType = item.type,
                    seriesId = item.data.seriesId,
                    useSeriesForPrimary = useSeriesForPrimary ?: item.useSeriesForPrimary,
                    imageTags = item.data.imageTags.orEmpty(),
                    imageType = imageType,
                    parentThumbId = item.data.parentThumbItemId,
                    parentBackdropId = item.data.parentBackdropItemId,
                    backdropTags = item.data.backdropImageTags.orEmpty(),
                    fillWidth = fillWidth,
                    fillHeight = fillHeight,
                    seriesPrimaryTag = item.data.seriesPrimaryImageTag,
                    seriesLogoTag = item.data.parentLogoImageTag.takeIf { item.data.parentLogoItemId == item.data.seriesId },
                    parentThumbTag = item.data.parentThumbImageTag,
                    parentBackdropTag = item.data.parentBackdropImageTags?.firstOrNull(),
                )
            } else {
                null
            }

        fun getItemImageUrl(
            itemId: UUID,
            imageType: ImageType,
            maxWidth: Int? = null,
            maxHeight: Int? = null,
            width: Int? = null,
            height: Int? = null,
            quality: Int? = QUALITY,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
            tag: String? = null,
            format: ImageFormat? = null,
            percentPlayed: Double? = null,
            unplayedCount: Int? = null,
            blur: Int? = null,
            backgroundColor: String? = null,
            foregroundLayer: String? = null,
            imageIndex: Int? = null,
        ): String? {
            if (api.baseUrl.isNullOrBlank()) return null
            return api.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = imageType,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                width = width?.takeIf { it > 0 },
                height = height?.takeIf { it > 0 },
                quality = quality,
                fillWidth = fillWidth?.takeIf { it > 0 },
                fillHeight = fillHeight?.takeIf { it > 0 },
                tag = tag,
                format = format,
                percentPlayed = percentPlayed,
                unplayedCount = unplayedCount,
                blur = blur,
                backgroundColor = backgroundColor,
                foregroundLayer = foregroundLayer,
                imageIndex = imageIndex,
            )
        }

        fun getUserImageUrl(userId: UUID) = api.imageApi.getUserImageUrl(userId)

        /**
         * Just a convenient way to get the image URL and remember it
         */
        @Composable
        fun rememberImageUrl(
            item: BaseItem?,
            imageType: ImageType = ImageType.PRIMARY,
        ) = remember(item, imageType) {
            if (item != null) {
                getItemImageUrl(item, imageType)
            } else {
                null
            }
        }

        companion object {
            private const val QUALITY = 96
        }
    }
