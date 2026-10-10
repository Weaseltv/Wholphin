package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.detail.series.EpisodeList
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewContent
import com.github.damontecres.wholphin.ui.detail.series.SeriesOverviewPosition
import com.github.damontecres.wholphin.ui.logTab
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.ApiRequestPager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesOverviewLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val instanceField = WholphinApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var originalApplication: Any? = null

    @Before fun context() {
        originalApplication = instanceField.get(null)
        instanceField.set(
            null,
            mockk<WholphinApplication> {
                every { getString(any()) } answers { RuntimeEnvironment.getApplication().getString(firstArg()) }
                every { resources } returns RuntimeEnvironment.getApplication().resources
            },
        )
    }

    @After fun restoreContext() {
        instanceField.set(null, originalApplication)
    }

    @Test fun `episode page keeps enlarged header cards and every footer action stable through repeated navigation`() {
        mockkStatic(::logTab)
        every { logTab(any(), any()) } returns Unit
        try {
            val episodes = List(2) { item("Episode $it with a long descriptive title", BaseItemKind.EPISODE, it + 1) }
            val seasons = List(30) { item("Season $it", BaseItemKind.SEASON, it) }
            val pager = mockk<ApiRequestPager<GetEpisodesRequest>>(relaxed = true)
            every { pager.size } returns episodes.size
            every { pager.get(any()) } answers { episodes[firstArg()] }
            compose.setContent {
                CompositionLocalProvider(
                    LocalImageUrlService provides mockk<ImageUrlService>(relaxed = true),
                    LocalDensity provides Density(1f, 1.5f),
                ) {
                    WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                        var position by remember { mutableStateOf(SeriesOverviewPosition(29, 0)) }
                        SeriesOverviewContent(
                            preferences = UserPreferences(AppPreferences.getDefaultInstance(), null),
                            series = item("A series with a long descriptive title", BaseItemKind.SERIES, 0),
                            seasons = seasons,
                            episodes = EpisodeList.Success(seasons.last().id, pager, 0),
                            seasonExtras = emptyList(),
                            chosenStreams = null,
                            peopleInEpisode = emptyList(),
                            position = position,
                            firstItemFocusRequester = remember { FocusRequester() },
                            episodeRowFocusRequester = remember { FocusRequester() },
                            castCrewRowFocusRequester = remember { FocusRequester() },
                            guestStarRowFocusRequester = remember { FocusRequester() },
                            extrasRowFocusRequester = remember { FocusRequester() },
                            onChangeSeason = {},
                            onFocusEpisode = { position = position.copy(episodeRowIndex = it) },
                            onClick = {},
                            onLongClick = {},
                            playOnClick = {},
                            watchOnClick = {},
                            favoriteOnClick = {},
                            moreOnClick = {},
                            overviewOnClick = {},
                            personOnClick = {},
                            canDelete = { false },
                            onConfirmDelete = {},
                            onClickExtra = { _, _ -> },
                            onChooseVersion = { _, _ -> },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            compose.onNode(hasContentDescription("${episodes.first().name}, ${episodes.first().subtitle}")).requestFocus()
            compose.waitForIdle()
            repeat(8) {
                key(Key.DirectionDown)
                assertFits("Footer $it")
                key(Key.DirectionUp)
                assertFits("Episode $it")
                val settled = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
                compose.mainClock.advanceTimeBy(1200)
                compose.waitForIdle()
                assertEquals("Focused bounds remain settled", settled, compose.onNode(isFocused()).getUnclippedBoundsInRoot())
            }
            key(Key.DirectionDown)
            repeat(5) {
                assertFits("Footer action $it")
                key(Key.DirectionRight)
            }
        } finally {
            unmockkStatic(::logTab)
        }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        compose.waitForIdle()
    }

    private fun assertFits(label: String) {
        val b = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
        assertTrue("$label fits: $b", b.top.value >= 0 && b.bottom.value <= 540 && b.left.value >= 0 && b.right.value <= 960)
    }

    private fun item(
        name: String,
        kind: BaseItemKind,
        number: Int,
    ) = BaseItem(
        BaseItemDto(
            id = UUID.randomUUID(),
            name = name,
            type = kind,
            indexNumber = number,
            parentIndexNumber = 29,
            overview = "A detailed episode description with readable text. ".repeat(8),
            productionYear = 2026,
            runTimeTicks = 18000000000L,
        ),
    )
}
