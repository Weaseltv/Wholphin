package com.github.damontecres.wholphin.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.damontecres.wholphin.kids.ProfileStatus
import com.github.damontecres.wholphin.kids.ProfileStatusRemote
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.imageApi
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
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
    /** Null when [bytes] isn't an image this TV can decode */
    fun thumbnail(bytes: ByteArray): IntArray?
}

/** A profile's server picture */
fun interface ProfileImageFetcher {
    /** The picture's bytes, or null when it has none (404); throws when the server can't be reached */
    suspend fun fetch(
        serverUrl: String,
        userId: UUID,
        imageTag: String,
    ): ByteArray?
}

object AvatarMatching {
    const val SIZE = 32

    /**
     * Mean absolute difference per channel (0-255) below which a picture is that avatar. Copies of the same avatar
     * decoded and encoded by Android, PIL or as the original PNG differ by about 1; the two most alike bundled avatars
     * differ by about 17 (measured 2026-10-23).
     */
    const val MATCH_BELOW = 6.0

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
 * token; a result is cached per user and tag, and the bundled avatars' thumbnails are made once. Bounded by
 * [FIND_TIMEOUT]; a server that can't be reached in time gives [CurrentPicture.Unknown].
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
        private val found = ConcurrentHashMap<String, CurrentPicture>()

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
            val key = "${target.userId}/$tag/$group"
            found[key]?.let { return it }
            val bytes =
                try {
                    fetcher.fetch(target.serverUrl, target.userId, tag)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Couldn't fetch the profile picture")
                    return CurrentPicture.Unknown
                }
            val picture = bytes?.let { thumbnails.thumbnail(it) }
            val result =
                picture
                    ?.let { AvatarMatching.bestMatch(it, avatarThumbnails(avatars)) }
                    ?.let { CurrentPicture.Bundled(it) }
                    ?: CurrentPicture.NotBundled
            found[key] = result
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
        }
    }

/** [ImageThumbnails] with Android's decoder: sampled down while decoding, scaled to the size, over black */
class AndroidImageThumbnails
    @Inject
    constructor() : ImageThumbnails {
        override fun thumbnail(bytes: ByteArray): IntArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AvatarMatching.SIZE * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val size = AvatarMatching.SIZE
            val scaled = Bitmap.createScaledBitmap(decoded, size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            val rgb = IntArray(size * size * 3)
            pixels.forEachIndexed { i, argb ->
                // Over black, so a transparent edge compares the same however it was encoded
                val alpha = (argb ushr 24) and 0xFF
                rgb[i * 3] = ((argb shr 16) and 0xFF) * alpha / 255
                rgb[i * 3 + 1] = ((argb shr 8) and 0xFF) * alpha / 255
                rgb[i * 3 + 2] = (argb and 0xFF) * alpha / 255
            }
            return rgb
        }
    }

/** [ProfileImageFetcher] over the server's public user image route, with its tag so caches stay right */
class HttpProfileImageFetcher
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
        @param:StandardOkHttpClient okHttpClient: OkHttpClient,
    ) : ProfileImageFetcher {
        private val client =
            okHttpClient
                .newBuilder()
                .callTimeout(CurrentAvatarFinder.FIND_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()

        override suspend fun fetch(
            serverUrl: String,
            userId: UUID,
            imageTag: String,
        ): ByteArray? =
            withContext(WholphinDispatchers.IO) {
                val url = jellyfin.createApi(serverUrl).imageApi.getUserImageUrl(userId, tag = imageTag)
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful -> throw IOException("Profile picture: HTTP ${response.code}")
                        else -> response.body.bytes()
                    }
                }
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
