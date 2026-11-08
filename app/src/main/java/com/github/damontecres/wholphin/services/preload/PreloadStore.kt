@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Index for the pre-load cache, kept in memory. The store is deleted on every app start, so its
 * index never needs to survive a restart. An on-disk SQLite index commits (and fsyncs) after
 * every 256 KB fragment; on the Shield that stalled single writes for up to 3.3 s while the
 * player's reads and the download waited (2026-11-07).
 */
internal class PreloadIndex(
    context: android.content.Context,
) : android.database.sqlite.SQLiteOpenHelper(context, null, null, 1),
    androidx.media3.database.DatabaseProvider {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) = Unit

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = Unit
}

/** One writer (the session worker); readers take a cache hole lock only for a moment. */
internal class PreloadStore(
    val directory: File,
    val limit: Long,
    private val database: androidx.media3.database.DatabaseProvider? = null,
) {
    val cache = if (database == null) SimpleCache(directory, NoOpCacheEvictor()) else SimpleCache(directory, NoOpCacheEvictor(), database)

    init {
        try {
            cache.checkInitialization()
        } catch (ex: Exception) {
            cache.release()
            closeDatabase(database)
            throw ex
        }
    }

    val bytes: Long get() = cache.cacheSpace

    fun available(
        key: String,
        position: Long,
        length: Long = Long.MAX_VALUE,
    ): Long = cache.getCachedLength(key, position, length).coerceAtLeast(0)

    fun read(
        key: String,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val span = cache.getCachedSpans(key).firstOrNull { position >= it.position && position < it.position + it.length } ?: return 0
        return try {
            RandomAccessFile(requireNotNull(span.file), "r").use {
                it.seek(position - span.position)
                it.read(buffer, offset, minOf(length.toLong(), span.position + span.length - position).toInt())
            }
        } catch (_: java.io.FileNotFoundException) {
            // A seek or low-storage cleanup won the race. The reader rechecks its session.
            0
        }
    }

    fun write(
        key: String,
        position: Long,
        data: ByteArray,
        count: Int,
    ) {
        var cursor = position
        val end = position + count
        while (cursor < end) {
            val cached = available(key, cursor, end - cursor)
            if (cached > 0) {
                cursor += cached
                continue
            }
            val hole = lockHole(key, cursor, end - cursor)
            try {
                val size = minOf(end - cursor, if (hole.isOpenEnded) end - cursor else hole.length).toInt()
                val file = cache.startFile(key, cursor, size.toLong())
                try {
                    file.outputStream().use { it.write(data, (cursor - position).toInt(), size) }
                    cache.commitFile(file, size.toLong())
                } finally {
                    if (file.exists() && file.length() == 0L) file.delete()
                }
                cursor += size
            } finally {
                cache.releaseHoleSpan(hole)
            }
        }
    }

    /**
     * The worker is the only writer, but the player's read-only CacheDataSource still takes
     * the hole lock for a moment when it opens a read at the write frontier (it releases it
     * at once because it has no write sink). Wait out that brief lock instead of failing:
     * failing here used to kill the whole pre-load worker (2026-11-07, "Garage TV" report).
     */
    private fun lockHole(
        key: String,
        position: Long,
        length: Long,
    ): CacheSpan {
        val deadline = System.nanoTime() + HOLE_LOCK_WAIT_NS
        while (true) {
            cache.startReadWriteNonBlocking(key, position, length)?.let { return it }
            if (System.nanoTime() >= deadline) throw java.io.IOException("Pre-load cache range stayed locked")
            Thread.sleep(1)
        }
    }

    fun length(
        key: String,
        value: Long,
    ) {
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, value) })
    }

    fun length(key: String): Long = ContentMetadata.getContentLength(cache.getContentMetadata(key))

    fun spans(): List<CacheSpan> = cache.keys.flatMap { cache.getCachedSpans(it) }

    fun remove(
        span: CacheSpan,
        reason: String,
        log: Boolean = true,
    ) {
        cache.removeSpan(span)
        if (log) Timber.tag("Preload").i("delete reason=%s bytes=%d storedBytes=%d", reason, span.length, bytes)
    }

    /** Removes [spans] and logs one summary line instead of one line per fragment. */
    fun removeAll(
        spans: List<CacheSpan>,
        reason: String,
    ) {
        if (spans.isEmpty()) return
        var removed = 0L
        for (span in spans) {
            remove(span, reason, log = false)
            removed += span.length
        }
        Timber.tag("Preload").i("delete reason=%s spans=%d bytes=%d storedBytes=%d", reason, spans.size, removed, bytes)
    }

    fun clear(reason: String) {
        val before = bytes
        cache.keys.toList().forEach { cache.removeResource(it) }
        Timber.tag("Preload").i("delete reason=%s bytes=%d storedBytes=%d", reason, before, bytes)
    }

    fun close(reason: String) {
        val before = bytes
        cache.release()
        try {
            // Release first, then delete the cache/index in bulk. Avoid a SQLite transaction
            // per HLS resource during the five-second whole-item cleanup window.
            SimpleCache.delete(directory, database)
            if (directory.exists()) deleteDirectory(directory, reason)
            Timber.tag("Preload").i("delete reason=%s bytes=%d storedBytes=0", reason, before)
        } finally {
            closeDatabase(database)
        }
    }

    companion object {
        private const val HOLE_LOCK_WAIT_NS = 2_000_000_000L

        private fun closeDatabase(database: androidx.media3.database.DatabaseProvider?) {
            (database as? android.database.sqlite.SQLiteOpenHelper)?.close()
        }

        fun deleteDirectory(
            directory: File,
            reason: String,
            database: androidx.media3.database.DatabaseProvider? = null,
        ) {
            if (!directory.exists()) {
                closeDatabase(database)
                Timber.tag("Preload").i("delete reason=%s storedBytes=0", reason)
                return
            }
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            // Read the old UID and remove its SQLite index before deleting the UID file,
            // including after a crash. Otherwise abandoned index tables would accumulate.
            if (database != null) {
                try {
                    SimpleCache.delete(directory, database)
                } finally {
                    closeDatabase(database)
                }
            }
            val deleted = !directory.exists() || directory.deleteRecursively()
            Timber.tag("Preload").i("delete reason=%s bytes=%d success=%s storedBytes=%d", reason, size, deleted, if (deleted) 0 else size)
            check(deleted) { "Cannot delete pre-load leftovers" }
        }
    }
}
