package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.ServerRepository
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles toggling media as favorited or watched
 */
@Singleton
class FavoriteWatchManager
    @Inject
    constructor(
        private val api: ApiClient,
        private val datePlayedService: DatePlayedService,
        private val latestNextUpService: LatestNextUpService,
        private val serverRepository: ServerRepository,
    ) {
        suspend fun setWatched(
            itemId: UUID,
            played: Boolean,
        ): UserItemDataDto {
            val owner = serverRepository.current.value
            datePlayedService.invalidate(itemId)
            val result = if (played) {
                api.playStateApi.markPlayedItem(itemId).content
            } else {
                api.playStateApi.markUnplayedItem(itemId).content
            }
            if (played && owner != null && serverRepository.current.value == owner) {
                try {
                    val item = api.userLibraryApi.getItem(itemId = itemId).content
                    val seriesId = if (item.type == BaseItemKind.SERIES) item.id else item.seriesId
                    seriesId?.let {
                        latestNextUpService.reengageSeries(owner.user.id, it) {
                            serverRepository.current.value == owner
                        }
                    }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Could not restore shared Next Up dismissal after marking played")
                }
            }
            return result
        }

        suspend fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ): UserItemDataDto =
            if (favorite) {
                api.userLibraryApi.markFavoriteItem(itemId).content
            } else {
                api.userLibraryApi.unmarkFavoriteItem(itemId).content
            }

        suspend fun removeContinueWatching(item: BaseItem) {
        }
    }
