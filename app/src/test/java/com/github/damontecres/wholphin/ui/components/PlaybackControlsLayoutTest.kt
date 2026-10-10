package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.playback.ControllerViewState
import com.github.damontecres.wholphin.ui.playback.overlay.OverlayViewState
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackController
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackControls
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackControlsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `down from transport opens the available queue or chapter row`() {
        val player = mockk<Player>(relaxed = true)
        every { player.duration } returns 3_600_000L
        every { player.currentPosition } returns 1_000L
        every { player.bufferedPosition } returns 30_000L
        every { player.playbackParameters } returns androidx.media3.common.PlaybackParameters.DEFAULT
        val state = ControllerViewState(60_000, true)
        state.showControls()
        val next = mutableStateOf(OverlayViewState.QUEUE)
        var changed: OverlayViewState? = null
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    PlaybackController(
                        item = null,
                        nextState = next.value,
                        player = player,
                        controllerViewState = state,
                        showPlay = true,
                        previousEnabled = true,
                        nextEnabled = true,
                        seekEnabled = true,
                        seekBack = 10.seconds,
                        skipBackOnResume = null,
                        seekForward = 30.seconds,
                        onPlaybackActionClick = {},
                        onClickPlaybackDialogType = {},
                        onSeekBarChange = {},
                        currentSegment = null,
                        onChangeState = { changed = it },
                    )
                }
            }
        }
        for (destination in listOf(OverlayViewState.QUEUE, OverlayViewState.CHAPTERS)) {
            compose.runOnIdle {
                next.value = destination
                changed = null
            }
            if (destination == OverlayViewState.CHAPTERS) {
                compose.onRoot().performKeyInput {
                    keyDown(Key.DirectionUp)
                    keyUp(Key.DirectionUp)
                }
            }
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
            assertEquals("Down must enter the available lower player row", destination, changed)
        }
    }

    @Test
    fun `transport buttons and optional skip controls do not overlap`() {
        val player = mockk<Player>(relaxed = true)
        every { player.duration } returns 3_600_000L
        every { player.currentPosition } returns 1_000L
        every { player.bufferedPosition } returns 30_000L
        val width = mutableStateOf(960.dp)
        val segment = mutableStateOf<MediaSegmentDto?>(null)
        val state = ControllerViewState(60_000, true)
        state.showControls()
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(width.value)) {
                        PlaybackControls(
                            player,
                            state,
                            {},
                            {},
                            {},
                            true,
                            true,
                            true,
                            true,
                            100,
                            10.seconds,
                            null,
                            30.seconds,
                            segment.value,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        for (viewport in listOf(960.dp, 800.dp)) {
            for (type in listOf(null, MediaSegmentType.INTRO, MediaSegmentType.COMMERCIAL)) {
                compose.runOnIdle {
                    width.value = viewport
                    segment.value =
                        type?.let {
                            MediaSegmentDto(
                                id = java.util.UUID.randomUUID(),
                                itemId = java.util.UUID.randomUUID(),
                                type = it,
                                startTicks = 0,
                                endTicks = 100_000_000,
                            )
                        }
                }
                val buttons =
                    compose
                        .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                        .fetchSemanticsNodes()
                        .map { it.boundsInRoot }
                assertTrue("transport buttons found", buttons.size >= 8)
                for (bounds in buttons) {
                    assertTrue(
                        "$viewport $type button outside screen: $bounds",
                        bounds.left >= 0 && bounds.right <= 960 && bounds.top >= 0 && bounds.bottom <= 540,
                    )
                }
                for (i in buttons.indices) {
                    for (j in i + 1 until buttons.size) {
                        assertTrue("$viewport $type buttons overlap: ${buttons[i]} and ${buttons[j]}", !buttons[i].overlaps(buttons[j]))
                    }
                }
            }
        }
    }
}
