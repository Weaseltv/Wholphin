package com.github.damontecres.wholphin.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.damontecres.wholphin.kids.ProfileStatus
import com.github.damontecres.wholphin.kids.ProfileStatusRemote
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageFormat
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/*
 * Which bundled avatar a profile's picture is now (owner bug report 2026-10-23). The TV's remembered avatar is only a
 * hint written when this TV saves a picture; one set on the website (contract 11.3, 12.1), on another TV, or by the
 * one-time backfill (12.6) never reaches it. So when the picker opens, the profile's current server picture is compared,
 * pixel by pixel at a small size, with the bundled avatars of its group.
 */

/** What a profile's picture is now */
sealed interface CurrentPicture {
    /** The bundled avatar [id] */
    data class Bundled(
        val id: String,
    ) : CurrentPicture

    /** No picture, or one that isn't a bundled avatar (eg uploaded elsewhere): nothing is ticked */
    data object NotBundled : CurrentPicture

    /** The server couldn't be asked: the TV's remembered avatar is used, as before */
    data object Unknown : CurrentPicture
}

/** A picture's thumbnail: [AvatarMatching.SIZE] x [AvatarMatching.SIZE] RGB values, row by row */
fun interface ImageThumbnails {
    /** Null when [bytes] isn't an image this TV can decode (or would need too much memory) */
    fun thumbnail(bytes: ByteArray): IntArray?
}

/** A profile's server picture, small (the server resizes it) */
fun interface ProfileImageFetcher {
    /**
     * The picture's bytes, or null when it has no usable one (404, or larger than a small picture can be); throws when
     * the server can't be reached. Cancellable: a cancellation cancels the request.
     */
    suspend fun fetch(
        serverUrl: String,
        userId: UUID,
        imageTag: String,
    ): ByteArray?
}

object AvatarMatching {
    const val SIZE = 32

    /**
     * Mean absolute difference per channel (0-255) below which a picture is that avatar. Measured 2026-10-23 on the
     * bundled sets: the server's 128 px copy (PNG, WebP or JPEG) against the bundled file sampled down while decoding
     * differs by at most 3.8; the two most alike bundled avatars differ by 17.3.
     */
    const val MATCH_BELOW = 8.0

    /** The runner-up must be at least this much further away, so a near tie is never ticked */
    const val MIN_MARGIN = 6.0

    fun distance(
        a: IntArray,
        b: IntArray,
    ): Double {
        if (a.size != b.size || a.isEmpty()) return Double.MAX_VALUE
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /**
     * [argb] ([width] x [height] Android colours) as a thumbnail: each cell the average of the pixels it covers, over
     * black, so how an image was scaled before matters little and a transparent edge compares the same however it was
     * encoded
     */
    fun thumbnail(
        argb: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val rgb = IntArray(SIZE * SIZE * 3)
        for (cy in 0 until SIZE) {
            val y0 = cy * height / SIZE
            val y1 = maxOf(y0 + 1, (cy + 1) * height / SIZE).coerceAtMost(height)
            for (cx in 0 until SIZE) {
                val x0 = cx * width / SIZE
                val x1 = maxOf(x0 + 1, (cx + 1) * width / SIZE).coerceAtMost(width)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val c = argb[y * width + x]
                        val alpha = (c ushr 24) and 0xFF
                        r += ((c shr 16) and 0xFF) * alpha / 255
                        g += ((c shr 8) and 0xFF) * alpha / 255
                        b += (c and 0xFF) * alpha / 255
                        n++
                    }
                }
                val i = (cy * SIZE + cx) * 3
                if (n > 0) {
                    rgb[i] = (r / n).toInt()
                    rgb[i + 1] = (g / n).toInt()
                    rgb[i + 2] = (b / n).toInt()
                }
            }
        }
        return rgb
    }

    /** The avatar in [avatars] that [picture] clearly is, or null */
    fun bestMatch(
        picture: IntArray,
        avatars: Map<String, IntArray>,
    ): String? {
        val ranked = avatars.map { (id, thumb) -> id to distance(picture, thumb) }.sortedBy { it.second }
        val (id, best) = ranked.firstOrNull() ?: return null
        if (best >= MATCH_BELOW) return null
        val runnerUp = ranked.getOrNull(1)?.second ?: return id
        return id.takeIf { runnerUp - best >= MIN_MARGIN }
    }
}

/**
 * Finds a profile's current avatar for the picker. Its picture tag comes from `GET /Users/Me` with the profile's own
 * token; a result is cached per user, tag and the TV's own picture version for [CACHE_FOR_MS] (a picture can change
 * without its tag changing, and this TV's own save starts afresh), and the bundled avatars' thumbnails are made once. Both
 * requests together are bounded by [FIND_TIMEOUT]; a server that can't be reached in time gives [CurrentPicture.Unknown].
 */
@Singleton
class CurrentAvatarFinder
    @Inject
    constructor(
        private val catalog: AvatarCatalog,
        private val status: ProfileStatusRemote,
        private val fetcher: ProfileImageFetcher,
        private val thumbnails: ImageThumbnails,
        private val cache: ProfileImageCache,
        @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    ) {
        private val avatarThumbs = ConcurrentHashMap<String, IntArray>()
        private val found = ConcurrentHashMap<String, Found>()

        private class Found(
            val result: CurrentPicture,
            val atMillis: Long,
        )

        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        /** The last result per user, with the TV's picture version it was found at */
        private val lastFound = ConcurrentHashMap<UUID, Pair<Int, CurrentPicture>>()

        /**
         * The last result for [userId], if this TV hasn't changed that profile's picture since: shown straight away while
         * it is checked again
         */
        fun lastKnown(userId: UUID): CurrentPicture? = lastFound[userId]?.takeIf { (version, _) -> version == versionOf(userId) }?.second

        suspend fun find(
            target: ProfilePictureTarget,
            avatars: List<Avatar>,
        ): CurrentPicture {
            val version = versionOf(target.userId)
            val result =
                withContext(dispatcher) {
                    withTimeoutOrNull(FIND_TIMEOUT) { findNow(target, avatars) }
                } ?: CurrentPicture.Unknown.also { Timber.i("Current avatar not found in time") }
            if (result != CurrentPicture.Unknown) lastFound[target.userId] = version to result
            return result
        }

        private suspend fun findNow(
            target: ProfilePictureTarget,
            avatars: List<Avatar>,
        ): CurrentPicture {
            val tag =
                when (val now = status.status(target.serverUrl, target.userId, target.accessToken)) {
                    is ProfileStatus.Active -> now.primaryImageTag ?: return CurrentPicture.NotBundled
                    else -> return CurrentPicture.Unknown
                }
            val group = avatars.firstOrNull()?.group
            val key = "${target.userId}/$tag/$group/${versionOf(target.userId)}"
            found[key]?.takeIf { clock() - it.atMillis < CACHE_FOR_MS }?.let { return it.result }
            val bytes =
                try {
                    fetcher.fetch(target.serverUrl, target.userId, tag)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Couldn't fetch the profile picture")
                    return CurrentPicture.Unknown
                }
            // No picture, or one too large or not decodable: not an avatar, and not cached (it may change any moment)
            val picture = bytes?.let { thumbnails.thumbnail(it) } ?: return CurrentPicture.NotBundled
            val result =
                AvatarMatching
                    .bestMatch(picture, avatarThumbnails(avatars))
                    ?.let { CurrentPicture.Bundled(it) }
                    ?: CurrentPicture.NotBundled
            found[key] = Found(result, clock())
            return result
        }

        private suspend fun avatarThumbnails(avatars: List<Avatar>): Map<String, IntArray> =
            avatars
                .mapNotNull { avatar ->
                    val key = avatar.assetPath
                    val thumb =
                        avatarThumbs[key] ?: try {
                            thumbnails.thumbnail(catalog.assetBytes(avatar))?.also { avatarThumbs[key] = it }
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.w(ex, "Couldn't read avatar %s", avatar.id)
                            null
                        }
                    thumb?.let { avatar.id to it }
                }.toMap()

        private fun versionOf(userId: UUID): Int = cache.versions.value[userId] ?: 0

        companion object {
            val FIND_TIMEOUT = 5.seconds

            /** How long a result stands for the same user, tag and picture version before it is checked again */
            const val CACHE_FOR_MS = 60_000L
        }
    }

/**
 * [ImageThumbnails] with Android's decoder: the size is read first, then the image is sampled down while decoding to at
 * most [MAX_DECODE_SIDE] on its longer side (so no image needs much memory), and averaged into the thumbnail
 */
class AndroidImageThumbnails
    @Inject
    constructor() : ImageThumbnails {
        override fun thumbnail(bytes: ByteArray): IntArray? =
            try {
                decode(bytes)
            } catch (ex: OutOfMemoryError) {
                Timber.w("Picture too large to compare")
                null
            } catch (ex: IllegalArgumentException) {
                Timber.w(ex, "Picture not decodable")
                null
            }

        private fun decode(bytes: ByteArray): IntArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            try {
                val width = decoded.width
                val height = decoded.height
                // Sampling rounds; never more than the cap allows
                if (width <= 0 || height <= 0 || width.toLong() * height > MAX_DECODE_SIDE * MAX_DECODE_SIDE * 4L) return null
                val pixels = IntArray(width * height)
                decoded.getPixels(pixels, 0, width, 0, 0, width, height)
                return AvatarMatching.thumbnail(pixels, width, height)
            } finally {
                decoded.recycle()
            }
        }

        private companion object {
            const val MAX_DECODE_SIDE = 128
        }
    }

/**
 * [ProfileImageFetcher] over the server's public user image route, asking for a 128 px PNG with the picture's tag (so
 * caches stay right). The request is cancelled when the caller is, its bytes are capped at [MAX_BYTES], and the client has
 * its own call timeout.
 */
class HttpProfileImageFetcher internal constructor(
    okHttpClient: OkHttpClient,
    private val imageUrl: (serverUrl: String, userId: UUID, imageTag: String) -> String,
) : ProfileImageFetcher {
    @Inject
    constructor(
        jellyfin: Jellyfin,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) : this(
        okHttpClient = okHttpClient,
        imageUrl = { serverUrl, userId, imageTag ->
            jellyfin.createApi(serverUrl).imageApi.getUserImageUrl(
                userId,
                tag = imageTag,
                format = ImageFormat.PNG,
                maxWidth = REQUEST_SIDE,
                maxHeight = REQUEST_SIDE,
            )
        },
    )

    private val client =
        okHttpClient
            .newBuilder()
            .callTimeout(CurrentAvatarFinder.FIND_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()

    override suspend fun fetch(
        serverUrl: String,
        userId: UUID,
        imageTag: String,
    ): ByteArray? {
        val call = client.newCall(Request.Builder().url(imageUrl(serverUrl, userId, imageTag)).build())
        return suspendCancellableCoroutine { continuation ->
            // Cancelling the call also ends a read in progress on OkHttp's thread
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resumeWith(runCatching { response.use { read(it) } })
                    }
                },
            )
        }
    }

    private fun read(response: Response): ByteArray? {
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("Profile picture: HTTP ${response.code}")
        val body = response.body
        if (body.contentLength() > MAX_BYTES) return null
        val source = body.source()
        // More than the cap: not a small picture, so it isn't read
        if (source.request(MAX_BYTES + 1)) return null
        return source.buffer.readByteArray()
    }

    companion object {
        /** The size asked of the server */
        const val REQUEST_SIDE = 128

        /** A 128 px picture is far smaller; anything larger isn't read */
        const val MAX_BYTES = 512L * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CurrentAvatarModule {
    @Binds
    abstract fun imageThumbnails(impl: AndroidImageThumbnails): ImageThumbnails

    @Binds
    abstract fun profileImageFetcher(impl: HttpProfileImageFetcher): ProfileImageFetcher
}
