package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.widget.ImageView
import coil3.load
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.Cards
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Profile pictures shared by the side menu's avatar and the Select User tiles (owner, 2026-11-17: the avatar blinked out
 * and back in on Select User).
 *
 * The chooser builds a profile's address more than once as it loads (first without the picture's tag, then with it),
 * and a new address started from blank. The picture last shown for that profile stays up while a new address loads,
 * and both places decode it at the chooser's tile size, so the tile can reuse the side menu's copy at once.
 */
object AvatarImages {
    private val lastShown = ConcurrentHashMap<UUID, String>()

    /** The largest tile, focused (1.2x on the stock theme, 1.06x on Neon) */
    private fun sizePx(context: Context): Int {
        val cardHeight = if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.CARD_HEIGHT_DP else Cards.HEIGHT_2X3_DP
        return context.dp(max(USER_TILE_DP, cardHeight * .75f) * 1.2f)
    }

    fun load(
        image: ImageView,
        userId: UUID,
        imageUrl: String,
        onError: () -> Unit = {},
    ) {
        image.load(imageUrl) {
            size(sizePx(image.context))
            memoryCacheKey(imageUrl)
            placeholderMemoryCacheKey(lastShown[userId] ?: imageUrl)
            listener(
                onSuccess = { _, _ -> lastShown[userId] = imageUrl },
                onError = { _, result ->
                    // Why a profile shows its letter: the server's answer (eg HTTP 404: no picture on that user)
                    Timber.w(result.throwable, "Profile picture failed for %s", userId)
                    onError()
                },
            )
        }
    }

    private const val USER_TILE_DP = 120f
}
