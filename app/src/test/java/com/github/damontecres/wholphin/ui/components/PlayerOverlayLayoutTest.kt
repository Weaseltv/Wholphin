package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.data.model.PlaylistItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.overlay.ChapterRowOverlay
import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import com.github.damontecres.wholphin.ui.playback.overlay.QueueRowOverlay
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerOverlayLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `player queue scrolls to its last item while keeping the full card caption visible`() {
        val names = List(20) { "Queue $it with a long descriptive movie title" }
        compose.setContent {
            CompositionLocalProvider(
                LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
                LocalDensity provides Density(1f, 1.5f),
            ) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        QueueRowOverlay(
                            names.map {
                                PlaylistItem.Media(
                                    BaseItem(
                                        BaseItemDto(
                                            id = UUID.randomUUID(),
                                            name = it,
                                            type = BaseItemKind.MOVIE,
                                            productionYear = 2026,
                                        ),
                                    ),
                                )
                            },
                            ControllerViewState(5000, true),
                            OverlayViewState.CONTROLLER,
                            {},
                            {},
                        )
                    }
                }
            }
        }
        compose.onNode(hasContentDescription(names.first()) and isFocusable()).requestFocus()
        repeat(19) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }
            compose.waitForIdle()
            val focused = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            val caption = compose.onNodeWithText(names[it + 1]).getUnclippedBoundsInRoot()
            assertTrue("Queue image fits: $focused", focused.left.value >= 0 && focused.right.value <= 960)
            assertTrue("Queue caption fits: $caption", caption.left.value >= 0 && caption.right.value <= 960 && caption.bottom.value <= 540)
        }
        assertTrue(
            compose
                .onNode(isFocused())
                .fetchSemanticsNode()
                .config
                .toString()
                .contains(names.last()),
        )
    }

    @Test
    fun `player chapter row reaches the final long chapter without cutting off its name or timestamp`() {
        val chapters = List(20) { Chapter(UUID.randomUUID(), "Chapter $it with a long descriptive scene title", it.seconds, null, it) }
        val player = mockk<androidx.media3.common.Player>(relaxed = true)
        every { player.currentPosition } returns 0L
        compose.setContent {
            CompositionLocalProvider(
                LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
                LocalDensity provides Density(1f, 1.5f),
            ) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ChapterRowOverlay(player, ControllerViewState(5000, true), chapters, true, {})
                    }
                }
            }
        }
        compose.onNode(hasText(chapters.first().name.orEmpty()) and isFocusable()).requestFocus()
        compose.waitForIdle()
        repeat(19) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }
            compose.waitForIdle()
            val f = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            assertTrue("Chapter image and inset caption fit: $f", f.left.value >= 0 && f.right.value <= 960 && f.bottom.value <= 540)
        }
        assertTrue(
            compose
                .onNode(isFocused())
                .fetchSemanticsNode()
                .config
                .toString()
                .contains(chapters.last().name.orEmpty()),
        )
    }
}
