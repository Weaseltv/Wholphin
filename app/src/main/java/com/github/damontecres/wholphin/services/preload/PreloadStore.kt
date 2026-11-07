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

/** One writer (the session worker); readers never hold a cache hole lock. */
internal class PreloadStore(val directory: File, val limit: Long, private val database: androidx.media3.database.StandaloneDatabaseProvider? = null) {
    val cache = if (database == null) SimpleCache(directory, NoOpCacheEvictor()) else SimpleCache(directory, NoOpCacheEvictor(), database)
    init {
        try { cache.checkInitialization() } catch (ex: Exception) {
            cache.release()
            database?.close()
            throw ex
        }
    }
    val bytes: Long get() = cache.cacheSpace

    fun available(key: String, position: Long, length: Long = Long.MAX_VALUE): Long =
        cache.getCachedLength(key, position, length).coerceAtLeast(0)

    fun read(key: String, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
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

    fun write(key: String, position: Long, data: ByteArray, count: Int) {
        var cursor = position
        val end = position + count
        while (cursor < end) {
            val cached = available(key, cursor, end - cursor)
            if (cached > 0) { cursor += cached; continue }
            val hole = cache.startReadWriteNonBlocking(key, cursor, end - cursor) ?: error("Pre-load has multiple writers")
            try {
                val size = minOf(end - cursor, if (hole.isOpenEnded) end - cursor else hole.length).toInt()
                val file = cache.startFile(key, cursor, size.toLong())
                try {
                    file.outputStream().use { it.write(data, (cursor - position).toInt(), size) }
                    cache.commitFile(file, size.toLong())
                } finally { if (file.exists() && file.length() == 0L) file.delete() }
                cursor += size
            } finally { cache.releaseHoleSpan(hole) }
        }
    }

    fun length(key: String, value: Long) {
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, value) })
    }

    fun length(key: String): Long = ContentMetadata.getContentLength(cache.getContentMetadata(key))

    fun spans(): List<CacheSpan> = cache.keys.flatMap { cache.getCachedSpans(it) }

    fun remove(span: CacheSpan, reason: String) {
        cache.removeSpan(span)
        Timber.tag("Preload").i("delete reason=%s bytes=%d storedBytes=%d", reason, span.length, bytes)
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
        } finally { database?.close() }
    }

    companion object {
        fun deleteDirectory(directory: File, reason: String, database: androidx.media3.database.StandaloneDatabaseProvider? = null) {
            if (!directory.exists()) {
                database?.close()
                Timber.tag("Preload").i("delete reason=%s storedBytes=0", reason)
                return
            }
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            // Read the old UID and remove its SQLite index before deleting the UID file,
            // including after a crash. Otherwise abandoned index tables would accumulate.
            if (database != null) try { SimpleCache.delete(directory, database) } finally { database.close() }
            val deleted = !directory.exists() || directory.deleteRecursively()
            Timber.tag("Preload").i("delete reason=%s bytes=%d success=%s storedBytes=%d", reason, size, deleted, if (deleted) 0 else size)
            check(deleted) { "Cannot delete pre-load leftovers" }
        }
    }
}
