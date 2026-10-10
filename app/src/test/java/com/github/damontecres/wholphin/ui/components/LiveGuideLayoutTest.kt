package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.LiveTvPreferences
import com.github.damontecres.wholphin.ui.detail.livetv.DvrScheduleContent
import com.github.damontecres.wholphin.ui.detail.livetv.FetchedPrograms
import com.github.damontecres.wholphin.ui.detail.livetv.Program
import com.github.damontecres.wholphin.ui.detail.livetv.TvChannel
import com.github.damontecres.wholphin.ui.detail.livetv.TvGuideGridContent
import com.github.damontecres.wholphin.ui.detail.livetv.TvProgram
import com.github.damontecres.wholphin.ui.preferences.user.FilterableLanguagePreference
import com.github.damontecres.wholphin.ui.preferences.user.PreferredLanguageType
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveGuideLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val instanceField = WholphinApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var originalApplication: Any? = null

    @Before fun context() {
        originalApplication = instanceField.get(null)
        val application =
            mockk<WholphinApplication> {
                every { getString(any()) } answers { RuntimeEnvironment.getApplication().getString(firstArg()) }
            }
        instanceField.set(null, application)
    }

    @After fun restoreContext() {
        instanceField.set(null, originalApplication)
    }

    @Test fun `guide program preserves title and episode subtitle at larger text`() {
        val start = LocalDateTime.now()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.size(400.dp, 64.dp)) {
                        Program(
                            start,
                            TvProgram(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                start,
                                start.plusHours(1),
                                0f,
                                1f,
                                1.hours,
                                "Evening Program",
                                "Episode subtitle",
                                seasonEpisode = null,
                                isRecording = false,
                                isSeriesRecording = false,
                                isRepeat = false,
                                category = null,
                            ),
                            false,
                            {},
                            null,
                        )
                    }
                }
            }
        }
        for (label in listOf("Evening Program", "Episode subtitle")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Complete line fits: $label", layouts.single().didOverflowHeight)
        }
    }

    @Test fun `final scheduled recording fits its complete focused bounds`() {
        val start = LocalDateTime.now()
        val recordings =
            List(30) { i ->
                BaseItem(
                    BaseItemDto(
                        id = UUID.randomUUID(),
                        type = BaseItemKind.PROGRAM,
                        name = "Scheduled broadcast $i",
                        episodeTitle = "A full episode subtitle",
                        isSeries = true,
                        startDate = start,
                        endDate = start.plusHours(1),
                    ),
                )
            }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    DvrScheduleContent(emptyList(), mapOf(start.toLocalDate() to recordings), {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Scheduled broadcast 29"))
        val last = compose.onNode(hasText("Scheduled broadcast 29") and isFocusable())
        last.requestFocus()
        last.assertIsDisplayed()
        val b = last.getUnclippedBoundsInRoot()
        assertTrue("Recording fits: $b", b.top.value >= 0 && b.bottom.value <= 540)
    }

    @Test fun `guide reaches its final channel and program without clipping enlarged text`() {
        val start =
            LocalDateTime
                .now()
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
        val channels = List(20) { i -> TvChannel(UUID.randomUUID(), "$i", "Broadcast channel $i", null, false) }
        val programs =
            channels.flatMapIndexed { channelIndex, channel ->
                List(4) { slot ->
                    TvProgram(
                        UUID.randomUUID(),
                        channel.id,
                        start.plusHours(slot.toLong()),
                        start.plusHours(slot + 1L),
                        slot.toFloat(),
                        slot + 1f,
                        1.hours,
                        "Program $channelIndex-$slot",
                        "Episode subtitle",
                        seasonEpisode = null,
                        isRecording = false,
                        isSeriesRecording = false,
                        isRepeat = false,
                        category = null,
                    )
                }
            }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    val focus = remember { FocusRequester() }
                    TvGuideGridContent(
                        LiveTvPreferences.getDefaultInstance(),
                        false,
                        channels,
                        FetchedPrograms(programs = programs, programsByChannel = programs.groupBy { it.channelId }),
                        channels.associate { it.id to 4 },
                        List(5) { start.plusHours(it.toLong()) },
                        { _, _ -> },
                        { _, _ -> },
                        {},
                        focus,
                        Modifier.fillMaxSize(),
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        repeat(19) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        repeat(3) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Program 19-3").assertIsDisplayed()
        val bounds = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue(
            "Final guide program fits: $bounds",
            bounds.top.value >= 0 && bounds.bottom.value <= 540 && bounds.left.value >= 0 && bounds.right.value <= 800,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Program 19-3").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Program title is vertically complete", layouts.single().didOverflowHeight)
    }

    @Test fun `language options arriving after the dialog opens remain visible and focusable`() {
        val options = mutableStateOf<List<PreferredLanguageType>>(emptyList())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    FilterableLanguagePreference(
                        R.string.preferred_audio_language,
                        PreferredLanguageType.AnyLanguage,
                        options.value,
                        {},
                        Modifier.size(360.dp, 480.dp).padding(16.dp),
                    )
                }
            }
        }
        compose.runOnIdle { options.value = List(30) { PreferredLanguageType.Language("$it", "Language $it") } }
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Language 29"))
        val last = compose.onNode(hasText("Language 29") and isFocusable())
        last.requestFocus()
        last.assertIsDisplayed()
        val b = last.getUnclippedBoundsInRoot()
        assertTrue("Language fits: $b", b.top.value >= 0 && b.bottom.value <= 480)
    }
}
