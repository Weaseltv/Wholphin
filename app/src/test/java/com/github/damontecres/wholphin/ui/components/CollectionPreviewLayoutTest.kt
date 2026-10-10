package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.data.SortAndDirection
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
class CollectionPreviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `grid details fit a complete preview and focused poster below library controls`() {
        val title = "A Long Movie Title With Several Complete Words"
        val overview = "A complete description must remain readable beneath the title and metadata. ".repeat(12)
        val item =
            BaseItem(
                BaseItemDto(id = UUID.randomUUID(), name = title, type = BaseItemKind.MOVIE, overview = overview, productionYear = 2026),
            )
        val imageUrls = mockk<ImageUrlService>(relaxed = true)
        compose.setContent {
            CompositionLocalProvider(LocalImageUrlService provides imageUrls, LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    Box(Modifier.height(400.dp)) {
                        CollectionFolderGrid(
                            preferences = UserPreferences(AppPreferences.getDefaultInstance(), null),
                            collectionType = CollectionType.MOVIES,
                            focusedItem = item,
                            items = List(30) { item },
                            sortAndDirection = SortAndDirection.DEFAULT,
                            onClickItem = { _, _ -> },
                            onLongClickItem = { _, _ -> },
                            letterPosition = { 0 },
                            viewOptions = ViewOptions(showDetails = true),
                            onClickPlay = { _, _ -> },
                            initialPosition = 0,
                            gridFocusRequester = remember { FocusRequester() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val description = compose.onNodeWithText(overview)
        val layouts = mutableListOf<TextLayoutResult>()
        description.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("The preview must measure a complete description line", layout.size.height >= layout.getLineBottom(0))
        assertTrue(
            "The preview must not cut through its last visible line",
            layout.size.height >= layout.getLineBottom(layout.lineCount - 1),
        )
        val card = compose.onAllNodes(isFocusable())[0]
        card.requestFocus()
        compose.waitForIdle()
        val bounds = compose.onAllNodesWithText(title)[0].getUnclippedBoundsInRoot()
        assertTrue(
            "Whole focused poster and caption must fit beneath preview: $bounds",
            bounds.top.value >= 0 && bounds.bottom.value <= 400,
        )
    }
}
