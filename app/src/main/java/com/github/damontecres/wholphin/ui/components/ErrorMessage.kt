package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonUpper
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ErrorViewModel
    @Inject
    constructor(
        private val serverReportService: ServerReportService,
    ) : ViewModel() {
        fun sendLogs() {
            viewModelScope.launchIO { serverReportService.sendAppLogs() }
        }
    }

/**
 * Displays an error message and/or exception
 */
@Composable
fun ErrorMessage(
    message: String?,
    exception: Throwable?,
    modifier: Modifier = Modifier,
    viewModel: ErrorViewModel = hiltViewModel(),
) {
    val neon = isWeaselTv()
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(16.dp),
    ) {
        if (neon) {
            // Neon Board (T12): an 80dp red outline icon box, Condensed 30 title, `mid` detail.
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(80.dp)
                        .border(1.dp, NeonBoard.Red),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = NeonBoard.Red,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.compose_error_message_title).neonUpper(),
            color = if (neon) NeonBoard.Text else MaterialTheme.colorScheme.error,
            style = if (neon) NeonType.condensedTitle(30.sp) else MaterialTheme.typography.titleMedium,
        )
        TextButton(
            stringRes = R.string.send_app_logs,
            onClick = {
                viewModel.sendLogs()
            },
        )
        message?.let {
            Text(
                text = it,
                color = if (neon) NeonBoard.Mid else MaterialTheme.colorScheme.error,
                style = if (neon) NeonType.body() else MaterialTheme.typography.titleLarge,
            )
        }
        exception?.localizedMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        var cause = exception?.cause
        while (cause != null) {
            cause.localizedMessage?.let {
                Text(
                    text = "Caused by: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            cause = cause.cause
        }
    }
}

@Composable
fun ErrorMessage(
    error: LoadingState.Error,
    modifier: Modifier = Modifier,
) = ErrorMessage(
    message = error.message,
    exception = error.exception,
    modifier = modifier,
)

@Composable
fun ErrorMessage(
    error: DataLoadingState.Error,
    modifier: Modifier = Modifier,
) = ErrorMessage(
    message = error.message,
    exception = error.exception,
    modifier = modifier,
)
