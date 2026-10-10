package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.ui.playback.AnalyticsState
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackDebugOverlay
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.TrackSupport
import com.github.damontecres.wholphin.util.TrackSupportReason
import com.github.damontecres.wholphin.util.TrackType
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.TranscodeReason
import org.jellyfin.sdk.model.api.TranscodingInfo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackDebugLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `transcode statistics leave room for final expanded track and hide action`() {
        val playback =
            CurrentPlayback(
                item = BaseItem(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE)),
                tracks =
                    List(30) { index ->
                        TrackSupport(
                            index.toString(),
                            TrackType.TEXT,
                            TrackSupportReason.HANDLED,
                            index == 0,
                            listOf("Language $index descriptive subtitle track"),
                            "srt",
                            Format.Builder().build(),
                        )
                    },
                backend = PlayerBackend.EXO_PLAYER,
                playMethod = PlayMethod.TRANSCODE,
                playSessionId = null,
                liveStreamId = null,
                mediaSourceInfo =
                    MediaSourceInfo(
                        protocol = MediaProtocol.HTTP,
                        type = MediaSourceType.DEFAULT,
                        isRemote = false,
                        readAtNativeFramerate = true,
                        ignoreDts = true,
                        ignoreIndex = true,
                        genPtsInput = false,
                        supportsTranscoding = true,
                        supportsDirectStream = true,
                        supportsDirectPlay = true,
                        isInfiniteStream = false,
                        requiresOpening = false,
                        requiresClosing = false,
                        requiresLooping = false,
                        supportsProbing = true,
                        transcodingSubProtocol = MediaStreamProtocol.HTTP,
                        hasSegments = false,
                    ),
                videoDecoder = "OMX.Nvidia.h265.decode.hardware",
                audioDecoder = "c2.android.aac.decoder",
                transcodeInfo =
                    TranscodingInfo(
                        videoCodec = "h264",
                        audioCodec = "aac",
                        container = "HLS",
                        width = 1920,
                        height = 1080,
                        isVideoDirect = false,
                        isAudioDirect = false,
                        transcodeReasons =
                            listOf(
                                TranscodeReason.VIDEO_PROFILE_NOT_SUPPORTED,
                                TranscodeReason.AUDIO_CHANNELS_NOT_SUPPORTED,
                            ),
                    ),
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    PlaybackDebugOverlay(AnalyticsState(), playback, Modifier.fillMaxSize().padding(16.dp))
                }
            }
        }
        val expand = compose.onNode(hasText("Show 29 more") and isFocusable())
        expand.requestFocus()
        expand.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        repeat(29) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        val last = compose.onNode(hasText("Language 29 descriptive subtitle track") and isFocusable())
        last.assertIsFocused()
        val b = last.getUnclippedBoundsInRoot()
        assertTrue("Final track below statistics fits: $b", b.top.value >= 16 && b.bottom.value <= 524)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        val hide = compose.onNode(hasText("Hide") and isFocusable())
        hide.assertIsFocused()
        val h = hide.getUnclippedBoundsInRoot()
        assertTrue("Full Hide action fits: $h", h.top.value >= 16 && h.bottom.value <= 524)
    }
}
