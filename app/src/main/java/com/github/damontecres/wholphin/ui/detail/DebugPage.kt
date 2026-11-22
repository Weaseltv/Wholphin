package com.github.damontecres.wholphin.ui.detail

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ItemPlaybackDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.util.versionCodeLong
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Date
import javax.inject.Inject

@Composable
fun DebugPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    fun scroll(
        reverse: Boolean = false,
        scrollAmount: Float = 100f,
    ) {
        scope.launch(ExceptionHandler()) {
            columnState.scrollBy(if (reverse) -scrollAmount else scrollAmount)
        }
    }

    val itemPlaybacks by viewModel.itemPlaybacks.collectAsState()
    val logcat by viewModel.logcat.collectAsState()

    val padding = remember { PaddingValues(bottom = 32.dp) }

    LazyColumn(
        state = columnState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier =
            modifier
                .focusable()
                .background(
                    MaterialTheme.colorScheme.surface,
                ).onKeyEvent {
                    if (it.type == KeyEventType.KeyUp) {
                        return@onKeyEvent false
                    }
                    if (it.key == Key.DirectionDown) {
                        scroll(false)
                        return@onKeyEvent true
                    }
                    if (it.key == Key.DirectionUp) {
                        scroll(true)
                        return@onKeyEvent true
                    }
                    if (it.key == Key.MediaFastForward || it.key == Key.PageDown) {
                        scroll(false, 300f)
                        return@onKeyEvent true
                    }
                    if (it.key == Key.MediaSkipBackward || it.key == Key.PageUp) {
                        scroll(true, 300f)
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                },
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(padding),
            ) {
                SectionTitle("App Information")
                state.appInfo.forEach {
                    BodyText(it)
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(padding),
            ) {
                SectionTitle("Device Information")
                state.deviceInfo.forEach {
                    BodyText(it)
                }

                SubSectionTitle("Display Modes")
                state.displayModes.forEach {
                    BodyText(it)
                }

                SubSectionTitle("Audio Devices")
                state.audioInfo.forEach {
                    BodyText(it)
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(padding),
            ) {
                SectionTitle("AppPreferences")
                BodyText(preferences.appPreferences.toString())
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(padding),
            ) {
                SectionTitle("User Information")
                BodyText("Current server: ${viewModel.serverRepository.currentServer}")
                BodyText("Current user: ${viewModel.serverRepository.currentUser}")
                BodyText("User server settings: ${viewModel.serverRepository.currentUserDto?.configuration}")
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(padding),
            ) {
                SectionTitle("Database")

                SubSectionTitle("ItemPlayback")
                itemPlaybacks.forEach {
                    BodyText(it.toString())
                }
            }
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SectionTitle("Logcat")
            }
        }
        items(logcat) { (level, line) ->
            val color =
                when (level) {
                    Log.VERBOSE -> MaterialTheme.colorScheme.onSurface
                    Log.DEBUG -> Color(0xff2bc4cf)
                    Log.INFO -> Color(0xff2bcf8b)
                    Log.WARN -> Color(0xffdde663)
                    Log.ERROR -> Color(0xffe67063)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
