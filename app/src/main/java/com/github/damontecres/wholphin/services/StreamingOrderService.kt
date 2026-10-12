package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.first
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A member's own order for the Streaming Services row, saved on this device. Services without a
 * saved position (new ones, or before any reorder) keep the default order after the saved ones.
 */
@Singleton
class StreamingOrderService
    @Inject
    constructor(
        private val keyValueService: KeyValueService,
    ) {
        suspend fun order(userId: UUID): List<String> = keyValueService.get(userId, KEY, emptyList<String>()).first()

        suspend fun save(
            userId: UUID,
            collectionIds: List<UUID>,
        ) = keyValueService.save(userId, KEY, collectionIds.map { it.toString() })

        /** Forget the member's order, so the default order is used. */
        suspend fun reset(userId: UUID) = keyValueService.save(userId, KEY, emptyList<String>())

        /** [collections] (already in the default order) in the member's saved order. */
        suspend fun sort(
            userId: UUID,
            collections: List<BaseItemDto>,
        ): List<BaseItemDto> {
            val positions = order(userId).withIndex().associate { (index, id) -> id to index }
            if (positions.isEmpty()) return collections
            return collections
                .withIndex()
                .sortedWith(compareBy({ positions[it.value.id.toString()] ?: Int.MAX_VALUE }, { it.index }))
                .map { it.value }
        }

        companion object {
            private const val KEY = "streaming_services_order"
        }
    }
