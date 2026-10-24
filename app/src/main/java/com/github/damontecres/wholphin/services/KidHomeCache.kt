package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.kids.KidContentGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A kids Home as it was last time, on this TV, per kid profile (owner, 2026-10-23: paint at once, then refresh in
 * place): the collection list (ids, names, server order) and each row's first page, in the server's item order.
 *
 * Kid content rules: a saved Home is only read back for the same server, profile and rules ([KidContentGuard.rulesTag],
 * ie the same ceiling), so it never reaches another kid, an adult, or this kid under another age limit. Its items are
 * checked again through the rules before they're shown ([visibleNow]); what can't be decided is hidden.
 */
@Singleton
class KidHomeCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val kidContentGuard: KidContentGuard,
    ) {
        @Serializable
        data class Saved(
            val serverId: String,
            val userId: String,
            val rules: String,
            val savedAt: Long,
            val collections: List<SavedCollection>,
            val rows: Map<String, SavedRow>,
        )

        @Serializable
        data class SavedCollection(
            val id: String,
            val name: String,
        )

        @Serializable
        data class SavedRow(
            val items: List<SavedItem>,
        )

        @Serializable
        data class SavedItem(
            val dto: BaseItemDto,
            val useSeriesForPrimary: Boolean = false,
        )

        /** The rules the current session's lists are filtered by, or null for an adult or an unknown ceiling */
        suspend fun rules(): String? = kidContentGuard.currentPolicy()?.takeIf { it.maxLevel != null }?.let { KidContentGuard.rulesTag(it) }

        /** The saved Home for exactly this [serverId], [userId] and [rules], if any */
        fun read(
            serverId: UUID,
            userId: UUID,
            rules: String,
        ): Saved? =
            try {
                file(userId)
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.let { json.decodeFromString<Saved>(it) }
                    ?.takeIf { it.serverId == serverId.toString() && it.userId == userId.toString() && it.rules == rules }
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't read the saved kids Home")
                null
            }

        fun write(saved: Saved) {
            try {
                file(UUID.fromString(saved.userId)).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(saved))
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't save the kids Home")
            }
        }

        /** [items] the current kid session may see now; empty if that can't be decided (fail closed) */
        suspend fun visibleNow(items: List<BaseItem>): List<BaseItem> {
            if (items.isEmpty()) return items
            return try {
                val visible = kidContentGuard.visibleIds(api, items.map { it.id })
                items.filter { it.id in visible }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't check the saved kids Home")
                emptyList()
            }
        }

        private fun file(userId: UUID) = File(context.cacheDir, "kid-home/$userId.json")

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            /** The saved row key for a row of the kids Home */
            fun rowKey(row: HomeRowConfig): String? =
                when (row) {
                    is HomeRowConfig.ContinueWatchingCombined -> "continue"
                    is HomeRowConfig.GetItems -> "watchAgain"
                    is HomeRowConfig.ByParent -> "collection:${row.parentId}"
                    else -> null
                }
        }
    }
