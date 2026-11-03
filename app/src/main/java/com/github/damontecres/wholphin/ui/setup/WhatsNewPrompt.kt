package com.github.damontecres.wholphin.ui.setup

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.WhatsNewEntry
import com.github.damontecres.wholphin.services.WhatsNewService
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.theme.neonUpper
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/** The pop-up's neon yellow: the theme's existing Yellow. */
private val WhatsNewYellow = NeonBoard.Yellow

/**
 * WeaselPlex: What's New once after an update, over Home. It waits while the tour runs (or
 * is about to), while the update popup is up, and away from Home, then shows a moment after
 * Home settles so it doesn't flash over a page that is still opening.
 */
@Composable
fun WhatsNewPrompt(
    service: WhatsNewService,
    navigationManager: NavigationManager,
    updateChecker: UpdateChecker,
    blocked: Boolean,
) {
    val entry by service.entry.collectAsState()
    val updatePrompt by updateChecker.prompt.collectAsState()
    val onHome = navigationManager.backStack.lastOrNull() is Destination.Home
    val clear = entry != null && onHome && !blocked && updatePrompt == null
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(clear) {
        settled = false
        if (clear) {
            delay(1_000)
            settled = true
        }
    }
    val shown = entry
    if (clear && settled && shown != null) {
        WhatsNewDialog(shown, onDismiss = service::dismiss)
    }
}

/** VERSION kicker, WHAT'S NEW, the bullets and one OK button, which has focus. BACK also closes it. */
@Composable
fun WhatsNewDialog(
    entry: WhatsNewEntry,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    ProvideNeonAccent(WhatsNewYellow) {
        BasicDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            elevation = 6.dp,
            outline = WhatsNewYellow,
        ) {
            // Dim Home behind the pop-up.
            (LocalView.current.parent as? DialogWindowProvider)?.window?.let {
                it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.setDimAmount(.72f)
            }
            Column(
                modifier =
                    Modifier
                        .width(520.dp)
                        .padding(start = 26.dp, end = 26.dp, top = 27.dp, bottom = 25.dp),
            ) {
                Text(
                    text = stringResource(R.string.whats_new_version, entry.version).neonUpper(),
                    style = NeonType.eyebrow().copy(fontSize = 10.sp),
                    color = WhatsNewYellow,
                )
                Text(
                    text = stringResource(R.string.whats_new_title).neonUpper(),
                    style = NeonType.condensedTitle(30.sp),
                    color = NeonBoard.Text,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Box(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(NeonBoard.Line),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    entry.notes.forEach { WhatsNewBullet(it) }
                }
                Button(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .padding(top = 23.dp)
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    // As in the mockup: a dark fill and white OK; the yellow border and glow come
                    // from the button's own focus style under the yellow accent.
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = NeonBoard.Text,
                            focusedContainerColor = NeonBoard.Card2,
                            focusedContentColor = NeonBoard.Text,
                            pressedContainerColor = NeonBoard.Card2,
                            pressedContentColor = NeonBoard.Text,
                        ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    contentHeight = 40.dp,
                ) {
                    Text(
                        text = stringResource(R.string.whats_new_ok),
                        style = NeonType.button(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // The dialog window takes focus a frame or two after it is composed.
        repeat(3) {
            if (focusRequester.tryRequestFocus()) return@LaunchedEffect
            delay(50)
        }
    }
}

@Composable
private fun WhatsNewBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = WhatsNewYellow,
            modifier =
                Modifier
                    .padding(top = 1.5.dp)
                    .size(17.dp)
                    .drawBehind {
                        // The mockup's faint glow around each check.
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    .5f to WhatsNewYellow.copy(alpha = .28f),
                                    1f to Color.Transparent,
                                    center = center,
                                    radius = size.minDimension * .75f,
                                ),
                            radius = size.minDimension * .75f,
                        )
                    },
        )
        Text(
            text = text,
            style = NeonType.rowSubtitle().copy(lineHeight = 20.sp),
            color = NeonBoard.Text.copy(alpha = .93f),
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
