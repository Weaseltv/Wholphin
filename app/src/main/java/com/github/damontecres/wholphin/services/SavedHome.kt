package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidContentGuard
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.RequestPager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

/**
 * An adult's Home as it was last time, on this TV, per profile (speed plan step 17, 2026-11): Home is painted from it
 * at once and its rows are then refreshed in place, instead of the loading picture staying up until every row has
 * come back from the server. The kids Home has its own, stricter copy ([KidHomeCache]); this one is never a kid's.
 *
 * Rules:
 * - Adults only. It is read and written only while the session has no kid content rules
 *   ([KidContentGuard.currentPolicy] is null): never for a kid, and never for an unknown profile on a protected TV.
 *   A profile that turns out to be a kid's has its copy removed ([delete]).
 * - It is only read back for the same server, profile incarnation (its saved row, so a profile removed and added
 *   again starts empty) and layout ([layoutKey]: this app version, the Home rows and the Home settings).
 * - It is removed with the profile or server ([purgeUser], [purgeServer]) and expires after [MAX_AGE].
 * - What is saved is what the rows showed: only rows that loaded, within [MAX_ITEMS_PER_ROW], [MAX_ROWS],
 *   [MAX_FILE_BYTES] and [MAX_FILES]. A card can be out of date for the moment it takes its row to refresh.
 */
@Singleton
class SavedHome
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val kidContentGuard: KidContentGuard,
    ) {
        @Serializable
        data class Saved(
            val serverId: String,
            val userId: String,
            /** The saved profile's row: a profile removed and added again has a new one */
            val profileRowId: Int,
            val layout: String,
            val savedAt: Long,
            val libraries: List<Library>,
            val rows: List<SavedRow>,
        )

        @Serializable
        data class SavedRow(
            val title: StringProvider,
            val items: List<SavedItem>,
            val viewOptions: HomeRowViewOptions,
            val rowType: HomeRowConfig?,
            val showViewMore: Boolean,
        ) {
            fun toRow(): HomeRowLoadingState =
                HomeRowLoadingState.Success(
                    title = title,
                    items = items.map { it.item.copy(libraryId = it.libraryId?.let(UUID::fromString)) },
                    viewOptions = viewOptions,
                    rowType = rowType,
                    showViewMore = showViewMore,
                )
        }

        /** A card; its library is kept beside it because [BaseItem.libraryId] isn't part of a saved [BaseItem] */
        @Serializable
        data class SavedItem(
            val item: BaseItem,
            val libraryId: String? = null,
        )

        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        /** Whether the session is an adult's: the only kind a saved Home is read or written for */
        suspend fun adultSession(): Boolean = kidContentGuard.currentPolicy() == null

        /** The saved Home for exactly this profile ([user]'s server, id and saved row) and [layout], if any and not expired */
        fun read(
            user: JellyfinUser,
            layout: String,
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
                        it.profileRowId == user.rowId && it.layout == layout
                }
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't read the saved Home; removed")
                file.delete()
                null
            }
        }

        /**
         * [rows] as they can be saved, or null if Home isn't fully loaded (a row still loading or failed), has too many
         * rows, or holds a row whose cards aren't all in memory.
         */
        fun rowsToSave(rows: List<HomeRowLoadingState>): List<SavedRow>? {
            if (rows.isEmpty() || rows.size > MAX_ROWS) return null
            return rows.map { row ->
                if (row !is HomeRowLoadingState.Success || row.items is RequestPager<*>) return null
                SavedRow(
                    title = row.title,
                    items =
                        row.items
                            .filterNotNull()
                            .take(MAX_ITEMS_PER_ROW)
                            .map { SavedItem(it, it.libraryId?.toString()) },
                    viewOptions = row.viewOptions,
                    rowType = row.rowType,
                    showViewMore = row.showViewMore,
                )
            }
        }

        /**
         * Saves [saved], then trims old and surplus files. Serialised with [purgeUser] and [purgeServer]: [stillValid] is
         * asked inside that lock right before the file is written, so a Home is never written back for a profile removed
         * meanwhile or a session that has moved on.
         */
        fun write(
            saved: Saved,
            stillValid: () -> Boolean,
        ) = synchronized(fileLock) {
            if (!stillValid()) return@synchronized
            val file = file(context, UUID.fromString(saved.serverId), UUID.fromString(saved.userId))
            try {
                val text = json.encodeToString(saved)
                val size = text.encodeToByteArray().size
                if (size > MAX_FILE_BYTES) {
                    Timber.w("Home is too big to save (%d bytes)", size)
                    file.delete()
                    return@synchronized
                }
                val temp = File(file.apply { parentFile?.mkdirs() }.parentFile, file.name + ".tmp")
                temp.writeText(text)
                if (!temp.renameTo(file)) temp.delete()
                trim(context, clock())
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't save Home")
                file.delete()
            }
        }

        /** Removes the saved Home for [userId] on [serverId] (eg the profile is a kid's now) */
        fun delete(
            serverId: UUID,
            userId: UUID,
        ) {
            runCatching { synchronized(fileLock) { file(context, serverId, userId).delete() } }
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            /** Writes, deletes and purges of saved Homes, one at a time: a purge waits for a write in progress */
            private val fileLock = Any()

            const val MAX_ITEMS_PER_ROW = 40
            const val MAX_ROWS = 40
            const val MAX_FILE_BYTES = 4 * 1024 * 1024
            const val MAX_FILES = 16
            val MAX_AGE = 3.days

            private fun dir(context: Context) = File(context.cacheDir, "saved-home")

            private fun file(
                context: Context,
                serverId: UUID,
                userId: UUID,
            ) = File(dir(context), "${serverId}_$userId.json")

            /**
             * What a saved Home was a Home of: this app version (card layouts and text resources can change with it),
             * the rows in [settings] and the Home settings [prefs]. Another layout's copy is never shown.
             */
            fun layoutKey(
                settings: HomePageResolvedSettings,
                prefs: HomePagePreferences,
            ): String {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update("${BuildConfig.VERSION_NAME}|${BuildConfig.VERSION_CODE}|".encodeToByteArray())
                digest.update(json.encodeToString(settings.rows.map { it.config }).encodeToByteArray())
                digest.update(prefs.toByteArray())
                return digest.digest().joinToString("") { "%02x".format(it) }
            }

            /** Removes the saved Home of profile [userId] on [serverId]: the profile was removed from this TV */
            fun purgeUser(
                context: Context,
                serverId: UUID,
                userId: UUID,
            ) {
                // Never in the way of the removal itself
                runCatching { synchronized(fileLock) { file(context, serverId, userId).delete() } }
                    .onFailure { Timber.w(it, "Couldn't remove the saved Home") }
            }

            /** Removes every saved Home for [serverId]: the server was removed from this TV */
            fun purgeServer(
                context: Context,
                serverId: UUID,
            ) {
                runCatching {
                    synchronized(fileLock) {
                        dir(context)
                            .listFiles { file ->
                                file.name.startsWith("${serverId}_")
                            }?.forEach { it.delete() }
                    }
                }.onFailure { Timber.w(it, "Couldn't remove the saved Homes") }
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
        }
    }
