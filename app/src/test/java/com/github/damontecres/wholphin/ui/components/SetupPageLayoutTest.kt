package com.github.damontecres.wholphin.ui.components

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.setup.JellyfinUserAndImage
import com.github.damontecres.wholphin.ui.setup.SwitchUserContent
import com.github.damontecres.wholphin.ui.setup.SwitchUserState
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.util.LoadingState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.model.api.QuickConnectResult
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
class SetupPageLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val server = JellyfinServer(UUID.randomUUID(), "WeaselPlex", "https://example.test", "10.11.0")

    @Test fun `fresh account connection retains its complete QR instructions and code at enlarged text`() {
        val code = mockk<QuickConnectResult>(relaxed = true)
        every { code.code } returns "123456"
        show(SwitchUserState(loading = LoadingState.Success, quickConnectEnabled = true, quickConnectStatus = code))
        for (label in listOf("123456", "To start watching connect your WeaselPlex account to the app.")) {
            val b = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("Full connection copy fits: $label $b", b.top.value >= 0 && b.bottom.value <= 540)
        }
    }

    @Test fun `full profile selection page retains its title and every focused profile caption`() {
        val users =
            List(12) {
                JellyfinUserAndImage(
                    JellyfinUser(
                        id = UUID.randomUUID(),
                        serverId = server.id,
                        name = "Profile $it with a long name",
                        accessToken = null,
                    ),
                    null,
                    true,
                )
            }
        show(SwitchUserState(loading = LoadingState.Success, users = users))
        val title = compose.onNodeWithText("SELECT USER").getUnclippedBoundsInRoot()
        assertTrue("Page title fits", title.top.value >= 0 && title.bottom.value <= 540)
        compose
            .onNode(
                hasContentDescription(
                    users
                        .first()
                        .user.name
                        .orEmpty(),
                ) and isFocusable(),
            ).requestFocus()
        compose.waitForIdle()
        repeat(13) {
            val b = compose.onNode(isFocused()).getUnclippedBoundsInRoot()
            assertTrue(
                "Profile $it fits: $b",
                b.top.value >= 0 && b.bottom.value <= 540 && b.left.value >= 0 && b.right.value <= 800,
            )
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionRight)
                keyUp(Key.DirectionRight)
            }
            compose.waitForIdle()
        }
    }

    @Test fun `fresh connection error remains readable without hiding the new code action`() {
        show(
            SwitchUserState(
                loading = LoadingState.Success,
                switchUserState =
                    LoadingState.Error(
                        "Unable to approve this connection. Please check the account and try again. ".repeat(20),
                    ),
            ),
        )
        val retry = compose.onNodeWithText("Get a new code")
        repeat(100) {
            compose.onRoot().performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            compose.waitForIdle()
        }
        retry.assertIsFocused()
        val b = retry.getUnclippedBoundsInRoot()
        assertTrue("Full retry action fits: $b", b.top.value >= 0 && b.bottom.value <= 540)
    }

    private fun show(state: SwitchUserState) {
        val repository = mockk<ServerRepository>(relaxed = true)
        every { repository.currentUserFlow } returns MutableStateFlow(null)
        val vm = mockk<SwitchUserViewModel>(relaxed = true)
        every { vm.serverRepository } returns repository
        every { vm.state } returns MutableStateFlow(state)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
                    SwitchUserContent(server, Modifier.fillMaxSize(), vm)
                }
            }
        }
        compose.waitForIdle()
    }
}
