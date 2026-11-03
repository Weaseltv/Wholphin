package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.BannerCard
import com.github.damontecres.wholphin.ui.cards.ItemCardImage
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.mockk
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpisodeRangeCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `paired episode badges fit the portrait and season cards at TV and enlarged font sizes`() {
        val imageUrls = mockk<ImageUrlService>(relaxed = true)
        val pair4 = BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(), type = BaseItemKind.EPISODE,
                parentIndexNumber = 4, indexNumber = 1, indexNumberEnd = 2,
            ),
        )
        val pair3 = BaseItem(
            BaseItemDto(
                id = UUID.randomUUID(), type = BaseItemKind.EPISODE,
                parentIndexNumber = 3, indexNumber = 6, indexNumberEnd = 7,
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides imageUrls) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Column {
                        // Approved portrait height is 170dp, at 2:3. Include the watched icon.
                        Box(Modifier.size(113.dp, 170.dp).testTag("portrait4")) {
                            ItemCardImage(pair4, null, true, false, true, 0, null, 0)
                        }
                        CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                            Box(Modifier.size(113.dp, 170.dp).testTag("portrait3")) {
                                ItemCardImage(pair3, null, true, false, true, 0, null, 0)
                            }
                            BannerCard(
                                name = "Exit 9B / Starter Pack", item = null, onClick = {}, onLongClick = {},
                                cornerText = pair4.ui.episodeCornerText, played = true, cardHeight = 120.dp,
                            )
                        }
                    }
                }
            }
        }
        for (label in listOf("S4 · E1-2", "S3 · E6-7", "S4E1-2")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(label, 1, layout.lineCount)
            assertFalse("$label must fit without truncation", layout.hasVisualOverflow)
            assertFalse("$label must not ellipsize", layout.isLineEllipsized(0))
        }
        for ((label, tag) in listOf("S4 · E1-2" to "portrait4", "S3 · E6-7" to "portrait3")) {
            val text = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val card = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$label stays within its card: $text / $card", text.left >= card.left && text.right <= card.right)
        }
    }
}
