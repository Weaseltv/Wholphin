package com.github.damontecres.wholphin.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.isPlayback
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonUpper
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.Version

/**
 * WeaselFin: shows the "update available" popup when [UpdateChecker.maybePromptForUpdate]
 * finds a newer release. It waits while something is playing, and never covers the update
 * page itself.
 */
@Composable
fun UpdatePrompt(
    updateChecker: UpdateChecker,
    navigationManager: NavigationManager,
) {
    val release by updateChecker.prompt.collectAsState()
    val current = navigationManager.backStack.lastOrNull()
    release?.let { release ->
        if (!current.isPlayback && current !is Destination.UpdateApp) {
            UpdatePromptDialog(
                release = release,
                onUpdate = {
                    updateChecker.dismissPrompt()
                    navigationManager.navigateTo(Destination.UpdateApp(installNow = true))
                },
                onNotNow = updateChecker::dismissPrompt,
            )
        }
    }
}

/**
 * "WeaselPlex 1.2.6 is available." with Update and Not now. Back counts as Not now.
 */
@Composable
fun UpdatePromptDialog(
    release: Release,
    onUpdate: () -> Unit,
    onNotNow: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    BasicDialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        elevation = 6.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .width(520.dp)
                    .padding(24.dp),
        ) {
            val version = release.version.run { "$major.$minor.$patch" }
            Text(
                text =
                    stringResource(
                        R.string.update_prompt_title,
                        stringResource(R.string.app_name),
                        version,
                    ).neonUpper(),
                style = if (isWeaselTv()) NeonType.dialogTitle() else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            TextButton(
                stringRes = R.string.update,
                onClick = onUpdate,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
            )
            TextButton(
                stringRes = R.string.not_now,
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
}

@PreviewTvSpec
@Composable
private fun UpdatePromptDialogPreview() {
    WholphinTheme {
        UpdatePromptDialog(
            release =
                Release(
                    version = Version.fromString("v1.2.6"),
                    downloadUrl = "https://url",
                    publishedAt = null,
                    body = null,
                    notes = listOf(),
                ),
            onUpdate = {},
            onNotNow = {},
        )
    }
}
