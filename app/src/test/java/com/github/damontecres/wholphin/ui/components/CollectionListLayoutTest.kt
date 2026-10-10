package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionListLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all media list layouts clear first and last focus edges at enlarged text`() {
        val type = mutableStateOf<CollectionType?>(CollectionType.MOVIES)
        val dense = mutableStateOf(false)
        val items =
            List(30) {
                BaseItem(
                    BaseItemDto(id = UUID.randomUUID(), name = "List entry ${it + 1}", type = BaseItemKind.MOVIE, productionYear = 2026),
                    false,
                )
            }
        val images = mockk<ImageUrlService>(relaxed = true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f), LocalImageUrlService provides images) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.size(700.dp, 400.dp)) {
                        CollectionFolderContent(
                            items,
                            ViewOptions(
                                type = if (dense.value) ViewOptionsType.DENSE_LIST else ViewOptionsType.LIST,
                            ),
                            type.value,
                            rememberLazyListState(),
                            0,
                            FocusRequester(),
                            { _, _ -> },
                            { _, _ -> },
                            {
                            },
                            Modifier,
                        )
                    }
                }
            }
        }
        for (kind in listOf(CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.MUSIC, null)) {
            for (compact in listOf(false, true)) {
                compose.runOnIdle {
                    type.value = kind
                    dense.value = compact
                }
                for (label in listOf("List entry 1", "List entry 30")) {
                    compose.onNode(hasScrollAction()).performScrollToNode(hasText(label, substring = true))
                    val entry = compose.onNode(hasText(label, substring = true) and isFocusable())
                    entry.requestFocus()
                    compose.waitForIdle()
                    val bounds = entry.getUnclippedBoundsInRoot()
                    val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
                    assertTrue(
                        "$kind dense=$compact $label must leave room for focus glow: $bounds / $viewport",
                        bounds.top.value - viewport.top.value >= 12 && viewport.bottom.value - bounds.bottom.value >= 12,
                    )
                }
            }
        }
    }
}
