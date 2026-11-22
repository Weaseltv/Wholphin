package com.github.damontecres.wholphin.ui.playback

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.findActivity
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.flow.update
import timber.log.Timber

@Composable
fun PlayExternalPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlayExternalViewModel =
        hiltViewModel(
            viewModelStoreOwner = LocalContext.current.findActivity() as AppCompatActivity,
        ),
) {
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = viewModel::onResult,
        )

    val state by viewModel.state.collectAsState()
    val launched by viewModel.launched.collectAsState()
    LaunchedEffect(Unit) {
        if (!launched) {
            viewModel.init(destination)
        }
    }

    when (val l = state.loading) {
        LoadingState.Pending -> {
            LoadingPage(modifier, false)
        }

        LoadingState.Loading,
        -> {
            LoadingPage(modifier)
        }

        is LoadingState.Error -> {
            ErrorMessage(l, modifier)
        }

        LoadingState.Success -> {
            LoadingPage(modifier)
            if (!launched) {
                LifecycleStartEffect(Unit) {
                    Timber.i("Launching external playback")
                    viewModel.launched.update { true }
                    try {
                        if (viewModel.mayLaunch()) launcher.launch(state.intent)
                    } catch (ex: Exception) {
                        viewModel.reportException(ex)
                    }
                    onStopOrDispose { }
                }
            }
        }
    }
}
