package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.JellyfinUser
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
import kotlin.time.Duration.Companion.days

/**
 * A kids Home as it was last time, on this TV, per kid profile (owner, 2026-10-23: paint at once, then refresh in
 * place): the collection list (ids, names, server order) and each row's first page, in the server's item order.
 *
 * Kid content rules: a saved Home is only read back for the same server, profile incarnation (its saved row, so a
 * profile removed and added again starts empty) and rules ([KidContentGuard.rulesTag], ie the same ceiling), so it
 * never reaches another kid, an adult, or this kid under another age limit. It's removed with the profile or server
 * ([purgeUser], [purgeServer]), expires after [MAX_AGE], and its items are checked again, with fresh facts, before
 * they're shown ([visibleNow]); what can't be decided is hidden. Only what a card shows is kept, within [MAX_ITEMS_PER_ROW],
 * [MAX_ROWS], [MAX_FILE_BYTES] and [MAX_FILES].
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
            /** The saved profile's row: a profile removed and added again has a new one */
            val profileRowId: Int,
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

        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        /** The rules the current session's lists are filtered by, or null for an adult or an unknown ceiling */
        suspend fun rules(): String? = kidContentGuard.currentPolicy()?.takeIf { it.maxLevel != null }?.let { KidContentGuard.rulesTag(it) }

        /** The saved Home for exactly this profile ([user]'s server, id and saved row) and [rules], if any and not expired */
        fun read(
            user: JellyfinUser,
            rules: String,
        ): Saved? {
            val file = file(context, user.serverId, user.id)
            return try {
                if (!file.exists()) return null
                if (file.length() > MAX_FILE_BYTES) {
                    file.delete()
                    return null
                }
                val saved = json.decodeFromString<Saved>(file.readText())
                if (clock() - saved.savedAt > MAX_AGE.inWholeMilliseconds || saved.savedAt > clock()) {
                    file.delete()
                    return null
                }
                saved.takeIf {
                    it.serverId == user.serverId.toString() && it.userId == user.id.toString() &&
                        it.profileRowId == user.rowId && it.rules == rules
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't read the saved kids Home; removed")
                file.delete()
                null
            }
        }

        /** Saves [saved] within the bounds, then trims old and surplus files */
        fun write(saved: Saved) {
            try {
                val bounded =
                    saved.copy(
                        collections = saved.collections.take(MAX_ROWS),
                        rows =
                            saved.rows.entries
                                .take(MAX_ROWS + 2)
                                .associate { (key, row) ->
                                    key to SavedRow(row.items.take(MAX_ITEMS_PER_ROW).map { it.copy(dto = displayOnly(it.dto)) })
                                },
                    )
                val text = json.encodeToString(bounded)
                val file = file(context, UUID.fromString(saved.serverId), UUID.fromString(saved.userId))
                val size = text.encodeToByteArray().size
                if (size > MAX_FILE_BYTES) {
                    Timber.w("The kids Home is too big to save (%d bytes)", size)
                    file.delete()
                    return
                }
                file.apply { parentFile?.mkdirs() }.writeText(text)
                trim(context, clock())
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't save the kids Home")
            }
        }

        /** Removes the saved Home for [userId] on [serverId] (eg it can't be read) */
        fun delete(
            serverId: UUID,
            userId: UUID,
        ) {
            file(context, serverId, userId).delete()
        }

        /** [items] the current kid session may see now, decided with fresh facts; empty if that can't be decided (fail closed) */
        suspend fun visibleNow(items: List<BaseItem>): List<BaseItem> {
            if (items.isEmpty()) return items
            return try {
                val visible = kidContentGuard.visibleIds(api, items.map { it.id }, fresh = true)
                items.filter { it.id in visible }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't check the saved kids Home")
                emptyList()
            }
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            const val MAX_ITEMS_PER_ROW = 40
            const val MAX_ROWS = 20
            const val MAX_FILE_BYTES = 512 * 1024
            const val MAX_FILES = 16
            val MAX_AGE = 7.days

            private fun dir(context: Context) = File(context.cacheDir, "kid-home")

            private fun file(
                context: Context,
                serverId: UUID,
                userId: UUID,
            ) = File(dir(context), "${serverId}_$userId.json")

            /** Removes the saved Home of profile [userId] on [serverId]: the profile was removed from this TV */
            fun purgeUser(
                context: Context,
                serverId: UUID,
                userId: UUID,
            ) {
                // Never in the way of the removal itself
                runCatching { file(context, serverId, userId).delete() }
                    .onFailure { Timber.w(it, "Couldn't remove the saved kids Home") }
            }

            /** Removes every saved Home for [serverId]: the server was removed from this TV */
            fun purgeServer(
                context: Context,
                serverId: UUID,
            ) {
                runCatching { dir(context).listFiles { file -> file.name.startsWith("${serverId}_") }?.forEach { it.delete() } }
                    .onFailure { Timber.w(it, "Couldn't remove the saved kids Homes") }
            }

            /** Removes saved Homes past [MAX_AGE] (eg of profiles no longer here), then the oldest beyond [MAX_FILES] */
            internal fun trim(
                context: Context,
                now: Long,
            ) {
                val files = dir(context).listFiles { file -> file.name.endsWith(".json") }?.toList() ?: return
                val (expired, kept) = files.partition { now - it.lastModified() > MAX_AGE.inWholeMilliseconds }
                expired.forEach { it.delete() }
                kept.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
            }

            /** Only what a Home card shows of [dto] (and what opening it needs) */
            fun displayOnly(dto: BaseItemDto): BaseItemDto =
                BaseItemDto(
                    id = dto.id,
                    type = dto.type,
                    name = dto.name,
                    serverId = dto.serverId,
                    seriesName = dto.seriesName,
                    seriesId = dto.seriesId,
                    seasonId = dto.seasonId,
                    parentId = dto.parentId,
                    indexNumber = dto.indexNumber,
                    parentIndexNumber = dto.parentIndexNumber,
                    productionYear = dto.productionYear,
                    premiereDate = dto.premiereDate,
                    endDate = dto.endDate,
                    status = dto.status,
                    officialRating = dto.officialRating,
                    communityRating = dto.communityRating,
                    runTimeTicks = dto.runTimeTicks,
                    isFolder = dto.isFolder,
                    childCount = dto.childCount,
                    mediaType = dto.mediaType,
                    imageTags = dto.imageTags,
                    imageBlurHashes = null,
                    backdropImageTags = dto.backdropImageTags,
                    parentBackdropItemId = dto.parentBackdropItemId,
                    parentBackdropImageTags = dto.parentBackdropImageTags,
                    parentThumbItemId = dto.parentThumbItemId,
                    parentThumbImageTag = dto.parentThumbImageTag,
                    seriesPrimaryImageTag = dto.seriesPrimaryImageTag,
                    seriesThumbImageTag = dto.seriesThumbImageTag,
                    primaryImageAspectRatio = dto.primaryImageAspectRatio,
                    userData = dto.userData,
                )

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
