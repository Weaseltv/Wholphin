package com.github.damontecres.wholphin.ui

import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.services.ImageUrlService

val FontAwesome = FontFamily(Font(resId = R.font.fa_solid_900))

val LocalImageUrlService =
    staticCompositionLocalOf<ImageUrlService> { throw IllegalStateException("LocalImageUrlService not set") }

/**
 * Colors not associated with the theme
 */
object AppColors {
    val TransparentBlack25 = Color(0x40000000)
    val TransparentBlack50 = Color(0x80000000)
    val TransparentBlack75 = Color(0xBF000000)

    val DarkGreen = Color(0xFF114000)
    val DarkRed = Color(0xFF400000)
    val DarkCyan = Color(0xFF21556E)
    val DarkPurple = Color(0xFF261370)

    val GoldenYellow = Color(0xFFDAB440)

    object Discover {
        val Blue = Color(37, 99, 235)
        val Purple = Color(147, 51, 234)
        val Green = Color(74, 222, 128)
        val Yellow = Color(234, 179, 8)
        val Red = Color(204, 38, 38)
    }
}

object Cards {
    const val HEIGHT_2X3_DP = 172
    val height2x3 = (if (BuildConfig.FLAVOR == "weaselfin") ApprovedHomeLayout.CARD_HEIGHT_DP else HEIGHT_2X3_DP).dp
    const val HEIGHT_EPISODE = 128
    val heightEpisode = HEIGHT_EPISODE.dp
    val playedPercentHeight = 6.dp
    val serverUserCircle = height2x3 * .75f
}

@Preview(
    device = "spec:parent=tv_1080p",
    backgroundColor = 0xFF383535,
    uiMode = UI_MODE_TYPE_TELEVISION,
)
annotation class PreviewTvSpec
