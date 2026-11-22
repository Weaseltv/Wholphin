package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import coil3.dispose
import coil3.load as coilLoad
import coil3.request.crossfade
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateMatrix()
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
