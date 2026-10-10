package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.playback.DownloadSubtitlesContent
import com.github.damontecres.wholphin.ui.playback.SubtitleSearchStatus
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
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
class SubtitleDownloadLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `subtitle results preserve full first and final focus bounds with large text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    BasicDialog({}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Box(Modifier.padding(24.dp)) {
                            DownloadSubtitlesContent(
                                SubtitleSearchStatus.Success(
                                    List(30) {
                                        RemoteSubtitleInfo(
                                            name = "Subtitle ${it + 1}",
                                            providerName = "A descriptive subtitle provider",
                                            isHashMatch = true,
                                            forced = true,
                                            hearingImpaired = true,
                                            communityRating = 8.5f,
                                            downloadCount = 10500,
                                        )
                                    },
                                ),
                                "eng",
                                {},
                                {},
                                Modifier.size(600.dp, 400.dp),
                            )
                        }
                    }
                }
            }
        }
        for (label in listOf("Subtitle 1", "Subtitle 30")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val entry = compose.onNode(hasText(label) and isFocusable())
            entry.requestFocus()
            entry.assertIsDisplayed()
            val bounds = entry.getUnclippedBoundsInRoot()
            assertTrue("Full result inside the viewport: $bounds", bounds.top.value >= 8 && bounds.bottom.value <= 532)
        }
    }

    @Test
    fun `empty search loading and download states keep readable status text`() {
        val state = mutableStateOf<SubtitleSearchStatus>(SubtitleSearchStatus.Searching)
        compose.setContent {
            WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                BasicDialog({
                }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    DownloadSubtitlesContent(state.value, "eng", {}, {}, Modifier.padding(24.dp))
                }
            }
        }
        for ((value, label) in listOf(
            SubtitleSearchStatus.Searching to "Searching…",
            SubtitleSearchStatus.Downloading to "Downloading…",
            SubtitleSearchStatus.Success(emptyList()) to "No remote subtitles were found",
        )) {
            compose.runOnIdle { state.value = value }
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
