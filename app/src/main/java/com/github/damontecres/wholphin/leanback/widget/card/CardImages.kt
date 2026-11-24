package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import coil3.dispose
import coil3.request.Disposable
import coil3.request.crossfade
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.model.api.ImageType
import coil3.load as coilLoad

/** Gives Views the app's [ImageUrlService] (the Compose UI read it from `LocalImageUrlService`) */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CardImagesEntryPoint {
    fun imageUrlService(): ImageUrlService
}

/** Image helpers for the card widgets (no-Compose UI): URLs from [ImageUrlService], loading with Coil 3 for Views */
object CardImages {
    @Volatile
    private var service: ImageUrlService? = null

    /** The app's [ImageUrlService] singleton */
    fun imageUrlService(context: Context): ImageUrlService =
        service ?: EntryPointAccessors
            .fromApplication(context.applicationContext, CardImagesEntryPoint::class.java)
            .imageUrlService()
            .also { service = it }

    /**
     * An item image URL sized like the old cards' (`fillWidth` / `fillHeight` in px; pass the image box's size, as the
     * composables passed `Dp.roundToPx()`), with the item's own series-primary choice unless [useSeriesForPrimary].
     */
    fun itemImageUrl(
        context: Context,
        item: BaseItem?,
        imageType: ImageType = ImageType.PRIMARY,
        fillWidthPx: Int? = null,
        fillHeightPx: Int? = null,
        useSeriesForPrimary: Boolean? = null,
    ): String? =
        imageUrlService(context).getItemImageUrl(
            item,
            imageType,
            fillWidth = fillWidthPx,
            fillHeight = fillHeightPx,
            useSeriesForPrimary = useSeriesForPrimary,
        )
}

/**
 * Load [url] into any ImageView with the app's Coil `ImageLoader`, as `AsyncImage` did: sized from the view, no
 * placeholder, crossfade only when asked (the loader's default is off), failures logged with [logCoilError] and
 * reported to [onError]; [onSuccess] when shown. A null or blank [url] clears the view. Returns the request so callers
 * can cancel it (or call `coil3.dispose` on the view).
 */
fun ImageView.loadImageUrl(
    url: String?,
    crossfade: Boolean = false,
    onError: (() -> Unit)? = null,
    onSuccess: (() -> Unit)? = null,
): Disposable? {
    if (url.isNullOrBlank()) {
        dispose()
        setImageDrawable(null)
        return null
    }
    return coilLoad(url) {
        if (crossfade) crossfade(true)
        listener(
            onError = { _, result ->
                logCoilError(url, result)
                onError?.invoke()
            },
            onSuccess = { _, _ -> onSuccess?.invoke() },
        )
    }
}

/** How an image fills its box: the Compose `ContentScale` values the cards used, all centred (`Alignment.Center`) */
enum class CardImageScale {
    /** `ContentScale.Fit` */
    FIT,

    /** `ContentScale.FillBounds` (stretch) */
    FILL_BOUNDS,

    /** `ContentScale.Crop` */
    CROP,

    /** `ContentScale.None` */
    NONE,

    /** `ContentScale.FillWidth` */
    FILL_WIDTH,

    /** `ContentScale.FillHeight` */
    FILL_HEIGHT,

    /** `ContentScale.Inside` */
    INSIDE,
    ;

    companion object {
        /** `PrefContentScale.scale` (view options and Home rows) */
        fun from(pref: PrefContentScale?): CardImageScale =
            when (pref) {
                PrefContentScale.FIT -> FIT
                PrefContentScale.NONE -> NONE
                PrefContentScale.CROP -> CROP
                PrefContentScale.FILL -> FILL_BOUNDS
                PrefContentScale.Fill_WIDTH -> FILL_WIDTH
                PrefContentScale.FILL_HEIGHT -> FILL_HEIGHT
                PrefContentScale.UNRECOGNIZED, null -> FIT
            }
    }
}

/**
 * An [ImageView] that loads a URL with the app's Coil `ImageLoader` the way `AsyncImage` did: sized from the view,
 * no placeholder, the loader's default crossfade (off) unless asked, errors logged with [logCoilError].
 */
open class CardImageView(
    context: Context,
) : AppCompatImageView(context) {
    var imageScale: CardImageScale = CardImageScale.FIT
        set(value) {
            field = value
            scaleType =
                when (value) {
                    CardImageScale.FIT -> ScaleType.FIT_CENTER
                    CardImageScale.FILL_BOUNDS -> ScaleType.FIT_XY
                    CardImageScale.CROP -> ScaleType.CENTER_CROP
                    CardImageScale.NONE -> ScaleType.CENTER
                    CardImageScale.INSIDE -> ScaleType.CENTER_INSIDE
                    CardImageScale.FILL_WIDTH, CardImageScale.FILL_HEIGHT -> ScaleType.MATRIX
                }
            updateMatrix()
        }

    /** The URL currently shown or loading */
    var url: String? = null
        private set

    private val fillMatrix = Matrix()

    init {
        imageScale = CardImageScale.FIT
    }

    /**
     * Load [url] (cancelling any earlier load). [onError] runs on the main thread when it fails; the view is left empty
     * so the caller can show its fallback. A null or blank [url] clears the view and does not call [onError].
     */
    fun load(
        url: String?,
        crossfade: Boolean = false,
        onError: (() -> Unit)? = null,
    ) {
        this.url = url
        if (url.isNullOrBlank()) {
            clear()
            return
        }
        coilLoad(url) {
            if (crossfade) crossfade(true)
            listener(
                onError = { _, result ->
                    logCoilError(url, result)
                    onError?.invoke()
                },
            )
        }
    }

    /** Cancel any load and show nothing (call when a card is recycled) */
    fun clear() {
        dispose()
        url = null
        setImageDrawable(null)
    }

    /** True while a drawable is set into this view and its size does not depend on the drawable */
    private var swappingAtFixedSize = false

    override fun setImageDrawable(drawable: Drawable?) {
        swappingAtFixedSize = hasFixedSize()
        try {
            super.setImageDrawable(drawable)
        } finally {
            swappingAtFixedSize = false
        }
        updateMatrix()
    }

    /**
     * A poster arriving has a new size, so ImageView asks every parent up to the grid to lay out again. A grid that lays
     * out while it scrolls skips its smooth scroll and then jumps (owner's recording, 2026-11-24). When this view's own
     * size is fixed, nothing moves: ImageView fits the new drawable itself, so the request is skipped.
     */
    override fun requestLayout() {
        if (swappingAtFixedSize) return
        super.requestLayout()
    }

    private fun hasFixedSize(): Boolean {
        val lp = layoutParams ?: return false
        return isLaidOut && width > 0 && height > 0 &&
            lp.width != ViewGroup.LayoutParams.WRAP_CONTENT && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun setFrame(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ): Boolean {
        val changed = super.setFrame(l, t, r, b)
        updateMatrix()
        return changed
    }

    private fun updateMatrix() {
        if (imageScale != CardImageScale.FILL_WIDTH && imageScale != CardImageScale.FILL_HEIGHT) return
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = (width - paddingLeft - paddingRight).toFloat()
        val vh = (height - paddingTop - paddingBottom).toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return
        val scale = if (imageScale == CardImageScale.FILL_WIDTH) vw / dw else vh / dh
        fillMatrix.reset()
        fillMatrix.setScale(scale, scale)
        fillMatrix.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f)
        imageMatrix = fillMatrix
    }
}
