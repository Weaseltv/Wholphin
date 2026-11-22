package com.github.damontecres.wholphin.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState

/**
 * Compose adapter for the old Compose player (ControllerViewState is Compose-free since the no-Compose rewrite).
 * Compose-only: delete with the old Compose playback UI.
 */
@Composable
fun ControllerViewState.collectVisible(): Boolean = visible.collectAsState().value
