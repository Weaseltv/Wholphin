package com.github.damontecres.wholphin.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.theme.NeonBrandRow
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState

@Composable
fun PinnedServerContent(
    modifier: Modifier = Modifier,
    viewModel: PinnedServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.connect() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            if (isWeaselTv()) {
                NeonBrandRow(mascotSize = 40.dp, wordmarkSize = 34.sp)
            }
            if (state is LoadingState.Error) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
                Text(
                    text = stringResource(R.string.pinned_server_error),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    stringRes = R.string.retry,
                    onClick = viewModel::connect,
                    modifier = Modifier.focusRequester(focusRequester),
                )
            } else {
                CircularProgress(Modifier.size(64.dp))
            }
        }
    }
}
