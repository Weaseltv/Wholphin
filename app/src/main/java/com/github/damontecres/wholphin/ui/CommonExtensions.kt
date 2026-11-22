package com.github.damontecres.wholphin.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.widget.Toast
import androidx.media3.common.Player
import coil3.request.ErrorResult
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// This file is a dumping ground mostly for extensions

@OptIn(ExperimentalContracts::class)
fun CharSequence?.isNotNullOrBlank(): Boolean {
    contract {
        returns(true) implies (this@isNotNullOrBlank != null)
    }
    return !this.isNullOrBlank()
}

inline fun <T> List<T>.indexOfFirstOrNull(predicate: (T) -> Boolean): Int? {
    val index = this.indexOfFirst(predicate)
    return if (index >= 0) index else null
}

fun Iterable<CharSequence?>.joinNotBlank(sep: String): String? = filter { it.isNotNullOrBlank() }.letNotEmpty { it.joinToString(sep) }

fun playOnClickSound(
    context: Context,
    effectType: Int = AudioManager.FX_KEY_CLICK,
) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.playSoundEffect(effectType)
}

/**
 * Rounds a [Duration] to nearest whole minute
 */
val Duration.roundMinutes: Duration
    get() = (this + 30.seconds).inWholeMinutes.minutes

/**
 * Rounds a [Duration] to nearest whole second
 */
val Duration.roundSeconds: Duration
    get() = (this + .5.seconds).inWholeSeconds.seconds

/**
 * Gets the user's playback position as a [Duration]
 */
val BaseItemDto.timeRemaining: Duration?
    get() =
        userData?.playbackPositionTicks?.let {
            if (it > 0) {
                runTimeTicks?.minus(it)?.ticks?.takeIf { it > Duration.ZERO }
            } else {
                null
            }
        }

/**
 * Seek back the current media item by a [Duration]
 */
fun Player.seekBack(amount: Duration) = seekTo((currentPosition - amount.inWholeMilliseconds).coerceAtLeast(0L))

/**
 * Seek forward the current media item by a [Duration]
 */
fun Player.seekForward(amount: Duration) = seekTo((currentPosition + amount.inWholeMilliseconds).coerceAtMost(duration))

/**
 * Like [let] but only if the collection is not empty, otherwise returns null
 */
@OptIn(ExperimentalContracts::class)
inline fun <T : Collection<*>, R> T.letNotEmpty(block: (T) -> R): R? {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (this.isNotEmpty()) block(this) else null
}

/**
 * Tries to find the [Activity] for the given [Context]
 */
fun Context.findActivity(): Activity? {
    if (this is Activity) {
        return this
    }
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Selectively log errors from Coil image loading.
 *
 * If an HTTP error occurs, the entire stacktrace is not logged.
 */
fun logCoilError(
    url: String?,
    errorResult: ErrorResult,
) {
    if (errorResult.throwable is coil3.network.HttpException || errorResult.throwable is coil3.request.NullRequestDataException) {
        Timber.w("Error loading image: %s for %s", errorResult.throwable.localizedMessage, url)
    } else {
        Timber.e(errorResult.throwable, "Error loading image: %s", url)
    }
}

/**
 * Show a [Toast]. Ensures it runs on the main thread.
 */
suspend fun showToast(
    context: Context,
    text: CharSequence,
    duration: Int,
) = withContext(WholphinDispatchers.Main) {
    Toast.makeText(context, text, duration).show()
}

suspend fun showToast(
    context: Context,
    text: CharSequence,
) = withContext(WholphinDispatchers.Main) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

/**
 * Launches a coroutine with [WholphinDispatchers.IO] plus the provided [CoroutineContext] defaulting to using [ExceptionHandler]
 */
fun CoroutineScope.launchIO(
    context: CoroutineContext = ExceptionHandler(),
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(context = WholphinDispatchers.IO + context, start = start, block = block)

/**
 * Launches a coroutine with [WholphinDispatchers.Default] plus the provided [CoroutineContext] defaulting to using [ExceptionHandler]
 */
fun CoroutineScope.launchDefault(
    context: CoroutineContext = ExceptionHandler(),
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(context = WholphinDispatchers.Default + context, start = start, block = block)

/**
 * Converts a UUID to the format used server-side (ie without hyphens).
 *
 * This is the inverse of [org.jellyfin.sdk.model.serializer.toUUID]
 *
 */
fun UUID.toServerString() = this.toString().replace("-", "")

fun equalsNotNull(
    a: Any?,
    b: Any?,
) = a != null && b != null && a == b

fun logTab(
    name: String,
    tabIndex: Int,
) {
    val info = "$tabIndex, $name"
    Timber.i("Current tab: $info")
    ACRA.errorReporter.putCustomData("tabInfo", info)
}

suspend fun <T> onMain(block: suspend CoroutineScope.() -> T) = withContext(WholphinDispatchers.Main, block)

fun Response<BaseItemDtoQueryResult>.toBaseItems(
    api: ApiClient,
    useSeriesForPrimary: Boolean,
) = this.content.items.map { BaseItem.from(it, api, useSeriesForPrimary) }

fun Response<BaseItemDtoQueryResult>.toBaseItems(useSeriesForPrimary: Boolean = false) =
    this.content.items.map { BaseItem(it, useSeriesForPrimary = useSeriesForPrimary) }

/**
 * Check if this, coalescing nulls to zero, is greater than that
 */
fun Int?.gt(that: Int) = (this ?: 0) > that

fun Int?.lt(that: Int) = (this ?: 0) < that

/**
 * Simplifies endlessly collecting a flow
 */
fun <T> Flow<T>.collectLatestIn(
    scope: CoroutineScope,
    action: suspend (value: T) -> Unit,
) {
    scope.launchDefault { this@collectLatestIn.collectLatest(action) }
}

/**
 * Easy way to combine two flows into a [Pair]
 */
fun <T1, T2> Flow<T1>.combinePair(flow: Flow<T2>): Flow<Pair<T1, T2>> = combine(flow) { t1, t2 -> Pair(t1, t2) }

/**
 * Easy way to combine three flows into a [Triple]
 */
fun <T1, T2, T3> Flow<T1>.combineTriple(
    flow: Flow<T2>,
    flow2: Flow<T3>,
): Flow<Triple<T1, T2, T3>> =
    combine(flow) { t1, t2 -> Pair(t1, t2) }
        .combine(flow2) { (t1, t2), t3 ->
            Triple(t1, t2, t3)
        }
