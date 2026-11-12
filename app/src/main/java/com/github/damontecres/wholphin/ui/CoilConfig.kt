package com.github.damontecres.wholphin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.EventListener
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.NavTiming
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.time.ExperimentalTime

@Composable
fun CoilConfig(
    prefs: AppPreferences,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) = CoilConfig(
    diskCacheSizeBytes =
        prefs.advancedPreferences.imageDiskCacheSizeBytes.let {
            if (it < AppPreference.ImageDiskCacheSize.min * AppPreference.MEGA_BIT) {
                AppPreference.ImageDiskCacheSize.defaultValue * AppPreference.MEGA_BIT
            } else {
                it
            }
        },
    okHttpClient = okHttpClient,
    debugLogging = debugLogging,
    enableCache = enableCache,
)

/**
 * Configure Coil image loading
 */
@OptIn(ExperimentalTime::class, ExperimentalCoilApi::class)
@Composable
fun CoilConfig(
    diskCacheSizeBytes: Long,
    okHttpClient: OkHttpClient,
    debugLogging: Boolean,
    enableCache: Boolean = true,
) {
    val client =
        remember(okHttpClient, debugLogging) {
            if (debugLogging) {
                okHttpClient
                    .newBuilder()
                    .addInterceptor {
                        val start = System.currentTimeMillis()
                        val req = it.request()
                        val res = it.proceed(req)
                        val time = System.currentTimeMillis() - start
                        Timber.v("${time}ms - ${req.url}")
                        res
                    }.build()
            } else {
                okHttpClient
            }
        }
    setSingletonImageLoaderFactory { ctx ->
        Timber.i("Image diskCacheSizeBytes=$diskCacheSizeBytes")
        ImageLoader
            .Builder(ctx)
            .apply {
                if (enableCache) {
                    memoryCache(MemoryCache.Builder().maxSizePercent(ctx).build())
                    diskCache(
                        DiskCache
                            .Builder()
                            .directory(ctx.cacheDir.resolve("coil3_image_cache"))
                            .maxSizeBytes(diskCacheSizeBytes)
                            .build(),
                    )
                } else {
                    memoryCache(null)
                    diskCache(null)
                }
            }.crossfade(false)
            // Speed plan step 0: counts where each image came from, for the log only
            .eventListenerFactory { NavTimingImageListener() }
            .logger(if (debugLogging) DebugLogger() else null)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        cacheStrategy = { WholphinCacheStrategy(CacheControlCacheStrategy()) },
                        callFactory = { client },
                        // Disable connectivity checker which may be unreliable for VPNs and/or some versions of Android
                        connectivityChecker = { ConnectivityChecker { true } },
                    ),
                )
            }.build()
    }
}

/**
 * This [CacheStrategy] always prefers the cached response for Trickplay images and for images whose address carries
 * the image's tag, otherwise the decision is delegated to the provided [CacheStrategy]
 *
 * The expectation is that Trickplay images will be prefetched so the cache will always be warm
 *
 * Tagged images (speed plan, owner's go-ahead 2026-11-11): the server sends item images without an expiry time, so the
 * delegate asked the server "has this changed?" before showing every saved copy. On the owner's Shield each of those
 * checks took 240 ms to 1 s as they queued up, which is why cards filled in one at a time on every return to a page.
 * The server gives an image a new tag when its recorded picture changes, so the saved copy of a tagged address is not
 * out of date ([com.github.damontecres.wholphin.services.ImageUrlService] adds the tags and says what that relies on)
 */
@OptIn(ExperimentalCoilApi::class)
private class WholphinCacheStrategy(
    private val delegate: CacheStrategy,
) : CacheStrategy {
    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        if (networkRequest.url.contains("/Trickplay/") || TAGGED_IMAGE.containsMatchIn(networkRequest.url)) {
            CacheStrategy.ReadResult(cacheResponse)
        } else {
            delegate.read(cacheResponse, networkRequest, options)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult = delegate.write(cacheResponse, networkRequest, networkResponse, options)
}

/** A server image address that names the picture's tag, eg /Items/<id>/Images/Primary?fillHeight=340&tag=abc */
private val TAGGED_IMAGE = Regex("/Images/[^?]*\\?(?:.*&)?tag=[^&]")

/** The image type in a server image address, eg "Primary" in /Items/<id>/Images/Primary */
private val IMAGE_KIND = Regex("/Images/([A-Za-z]+)")

/** Tells [NavTiming] where an image came from and how long it took; one per image request */
private class NavTimingImageListener : EventListener() {
    private var startNanos = 0L

    override fun onStart(request: ImageRequest) {
        startNanos = System.nanoTime()
    }

    override fun onSuccess(
        request: ImageRequest,
        result: SuccessResult,
    ) {
        NavTiming.image(result.dataSource, (System.nanoTime() - startNanos) / 1_000_000) {
            // The kind of image and how it was decoded, never the item: "Primary 240x360 BitmapImage kept=true"
            val kind = IMAGE_KIND.find(request.data.toString())?.groupValues?.get(1) ?: "other"
            val image = result.image
            "$kind ${image.width}x${image.height} ${image::class.simpleName} kept=${result.memoryCacheKey != null}"
        }
    }

    override fun onError(
        request: ImageRequest,
        result: ErrorResult,
    ) {
        NavTiming.imageFailed()
    }
}
