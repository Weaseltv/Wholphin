package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.detail.livetv.ProgramDialog
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.DataLoadingState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w800dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProgramDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `long program information preserves live and both recording button bounds`() {
        show(recording = false)
        verifyButtons("Watch live", "Record Series", "Record Program")
    }

    @Test
    fun `cancel recording controls remain complete at larger text size`() {
        show(recording = true)
        verifyButtons("Watch live", "Cancel Series Recording", "Cancel Recording")
    }

    @Test
    fun `missing guide dates do not crash or offer watch live`() {
        show(recording = false, dates = false)
        compose.onNodeWithText("Watch live").assertDoesNotExist()
        verifyButtons("Record Series", "Record Program")
    }

    private fun show(
        recording: Boolean,
        dates: Boolean = true,
    ) {
        val now = LocalDateTime.now()
        val item =
            BaseItem(
                BaseItemDto(
                    id = UUID.randomUUID(),
                    type = BaseItemKind.PROGRAM,
                    name = "An extended live program title with several complete words",
                    isSeries = true,
                    indexNumber = 12,
                    parentIndexNumber = 8,
                    episodeTitle = "A detailed episode title for this broadcast",
                    overview = "A long program description with additional details. ".repeat(30),
                    startDate = if (dates) now.minusHours(1) else null,
                    endDate = if (dates) now.plusHours(1) else null,
                    timerId = if (recording) "program" else null,
                    seriesTimerId = if (recording) "series" else null,
                ),
            )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.2f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    ProgramDialog(DataLoadingState.Success(item), true, {}, {}, { _, _ -> }, { _, _ -> })
                }
            }
        }
    }

    private fun verifyButtons(vararg labels: String) {
        for (label in labels) {
            val button = compose.onNode(hasText(label) and isFocusable())
            button.requestFocus()
            button.assertIsDisplayed()
            val bounds = button.getUnclippedBoundsInRoot()
            assertTrue(
                "Complete focus stays within the viewport: $label $bounds",
                bounds.left.value >= 8 && bounds.right.value <= 792 && bounds.top.value >= 8 && bounds.bottom.value <= 532,
            )
        }
    }
}
