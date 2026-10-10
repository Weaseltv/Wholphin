package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.playback.overlay.DpadSeekOverlay
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.TrickplayInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeekPreviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `image preview and enlarged timecodes fit at both seekbar ends`() = checkPreview(true)

    @Test
    fun `timestamp preview fits when the server has no trickplay images`() = checkPreview(false)

    private fun checkPreview(withImage: Boolean) {
        val player = mockk<Player>(relaxed = true)
        every { player.duration } returns 3_600_000L
        every { player.bufferedPosition } returns 3_600_000L
        val info = mockk<TrickplayInfo>(relaxed = true)
        every { info.width } returns 320
        every { info.height } returns 180
        every { info.interval } returns 10_000
        every { info.tileWidth } returns 10
        every { info.tileHeight } returns 10
        val position = mutableStateOf(0L)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.fillMaxSize()) {
                        DpadSeekOverlay(
                            player = player,
                            seekPositionMs = position.value,
                            trickplayInfo = info.takeIf { withImage },
                            trickplayUrlFor = { "file:///missing-layout-fixture.png" },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(24.dp)
                                    .testTag("seek-overlay"),
                        )
                    }
                }
            }
        }
        for (value in listOf(0L, 3_600_000L)) {
            compose.runOnIdle { position.value = value }
            val bounds = compose.onNodeWithTag("seek-overlay").getUnclippedBoundsInRoot()
            assertTrue(
                "Seek overlay fits the viewport: $bounds",
                bounds.left.value >= 24 && bounds.right.value <= 776,
            )
            assertTrue(
                "Preview and timecodes fit vertically: $bounds",
                bounds.top.value >= 24 && bounds.bottom.value <= 516,
            )
            if (withImage) {
                assertTrue("Image reserves its full height before loading", (bounds.bottom - bounds.top).value >= 200)
            }
        }
    }
}
