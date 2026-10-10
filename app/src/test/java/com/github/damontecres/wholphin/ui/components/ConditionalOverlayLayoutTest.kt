package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.ChapterCard
import com.github.damontecres.wholphin.ui.playback.SubtitleDelay
import com.github.damontecres.wholphin.ui.slideshow.ImageControlsOverlay
import com.github.damontecres.wholphin.ui.slideshow.SlideshowControls
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConditionalOverlayLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long chapter name leaves its timestamp inside the card`() {
        val name = "A chapter with a long descriptive name ".repeat(12)
        compose.setContent {
            CompositionLocalProvider(
                LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
                LocalDensity provides Density(1f, 1.5f),
            ) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    ChapterCard(
                        Chapter(UUID.randomUUID(), name, 30.seconds, null, 0),
                        {},
                        modifier = Modifier.width(213.dp),
                        cardHeight = 120.dp,
                    )
                }
            }
        }
        val nameLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
        assertTrue(
            "Chapter name leaves room for its timestamp",
            nameLayouts.single().lineCount <= 2 &&
                nameLayouts.single().getLineBottom(nameLayouts.single().lineCount - 1) <= nameLayouts.single().size.height,
        )
        val nameBounds = compose.onNodeWithText(name).getUnclippedBoundsInRoot()
        assertTrue("Chapter label stays inside its image: $nameBounds", nameBounds.top.value >= 0 && nameBounds.bottom.value <= 120)
        val allText = compose.onAllNodes(hasText("30s", substring = true)).fetchSemanticsNodes()
        assertTrue(
            "Chapter timestamp remains visible",
            allText.isNotEmpty() && allText.all { it.boundsInRoot.height > 0 && it.boundsInRoot.bottom <= 120 },
        )
    }

    @Test
    fun `subtitle delay labels and all seven actions fit at larger text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SubtitleDelay(1.seconds, {}) }
                }
            }
        }
        val buttons = compose.onAllNodes(isFocusable()).fetchSemanticsNodes()
        assertTrue("All delay actions are visible", buttons.size == 7)
        for (b in buttons) assertTrue("Delay action fits: ${b.boundsInRoot}", b.boundsInRoot.left >= 0 && b.boundsInRoot.right <= 800)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Reset").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(
            "Reset is fully readable",
            layouts.isNotEmpty() && !layouts.first().didOverflowWidth && !layouts.first().isLineEllipsized(0),
        )
    }

    @Test
    fun `slideshow and video clip transport focus fits at both row ends`() {
        val clip = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ImageControlsOverlay(false, mockk<SlideshowControls>(relaxed = true), {
                        }, clip.value, {}, {}, {}, {}, false, {}, {}, null, Modifier.width(800.dp))
                    }
                }
            }
        }
        for (videoClip in listOf(false, true)) {
            compose.runOnIdle { clip.value = videoClip }
            compose.onNode(hasText("Play slideshow") and isFocusable()).requestFocus()
            compose.waitForIdle()
            repeat(14) {
                val f = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
                assertTrue(
                    "Whole slideshow action stays within screen: $f",
                    f.left.value >= 0 && f.right.value <= 800 && f.top.value >= 0 && f.bottom.value <= 540,
                )
                compose.onRoot().performKeyInput {
                    keyDown(Key.DirectionRight)
                    keyUp(Key.DirectionRight)
                }
                compose.waitForIdle()
            }
        }
    }
}
