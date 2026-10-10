package com.github.damontecres.wholphin.ui.setup

import android.widget.Toast
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.components.keepFocusedItemVisible
import com.github.damontecres.wholphin.ui.dimAndBlur
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonBrandRow
import com.github.damontecres.wholphin.ui.theme.NeonEyebrow
import com.github.damontecres.wholphin.ui.theme.NeonRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.launch

@Composable
fun SwitchUserContent(
    server: JellyfinServer,
    modifier: Modifier = Modifier,
    viewModel: SwitchUserViewModel =
        hiltViewModel<SwitchUserViewModel, SwitchUserViewModel.Factory>(
            creationCallback = { it.create(server) },
        ),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.init()
    }

    val state by viewModel.state.collectAsState()

    val currentUser by viewModel.serverRepository.currentUserFlow.collectAsState(null)
    val connectOnly = weaselPlexConnectOnly()
    var showAddUser by remember { mutableStateOf(false) }
    var addUser by remember(server) { mutableStateOf<JellyfinUser?>(null) }
    var username by remember(addUser) { mutableStateOf(addUser?.name ?: "") }

    fun showAddUserDialog(user: JellyfinUser?) {
        addUser = user
        showAddUser = true
    }

    fun hideAddUserDialog() {
        addUser = null
        showAddUser = false
    }

    fun trySwitchUser(user: JellyfinUser) {
        val result = viewModel.trySwitchUser(user)
        scope.launch {
            when (val r = result.await()) {
                is SwitchUserResult.Error -> {
                    Toast.makeText(context, r.errorMessage, Toast.LENGTH_LONG).show()
                    if (r.showLogin) {
                        showAddUserDialog(user)
                    }
                }

                SwitchUserResult.Success -> {
                    // no-op, view model will navigate
                }
            }
        }
    }

    LaunchedEffect(state.switchUserState) {
        if (!showAddUser) {
            when (val s = state.switchUserState) {
                is LoadingState.Error -> {
                    val msg = s.message ?: s.exception?.localizedMessage
                    Toast.makeText(context, "Error: $msg", Toast.LENGTH_LONG).show()
                }

                else -> {}
            }
        }
    }
    var switchUserWithPin by remember { mutableStateOf<JellyfinUser?>(null) }

    when (val st = state.loading) {
        is LoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        LoadingState.Loading,
        LoadingState.Pending,
        -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            if (connectOnly && state.users.isEmpty()) {
                ConnectYourAccount(server = server, viewModel = viewModel, modifier = modifier)
                return@SwitchUserContent
            }
            Box(
                modifier = modifier.dimAndBlur(showAddUser || switchUserWithPin != null),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(16.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isWeaselTv()) {
                            // Neon Board (T9): mascot + wordmark, the server host as an eyebrow,
                            // the title on a volt rule.
                            NeonBrandRow(mascotSize = 40.dp, wordmarkSize = 34.sp)
                            NeonEyebrow(text = server.name ?: server.url, accent = NeonBoard.Mid)
                            Text(
                                text = stringResource(R.string.select_user).uppercase(),
                                style = NeonType.pageTitle(),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            NeonRule(modifier = Modifier.width(320.dp))
                        } else {
                            Text(
                                text = stringResource(R.string.select_user),
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = server.name ?: server.url,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    UserList(
                        users = state.users,
                        currentUser = currentUser,
                        onSwitchUser = { user ->
                            if (user.accessToken == null || user.requireLogin) {
                                showAddUserDialog(user)
                            } else if (user.hasPin) {
                                switchUserWithPin = user
                            } else {
                                trySwitchUser(user)
                            }
                        },
                        onAddUser = {
                            showAddUserDialog(null)
                        },
                        onRemoveUser = { user ->
                            viewModel.removeUser(user)
                        },
                        onSwitchServer =
                            if (connectOnly) {
                                null
                            } else {
                                {
                                    viewModel.setupNavigationManager.navigateTo(
                                        SetupDestination.ServerList,
                                    )
                                }
                            },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when (state.serverVersionSupported) {
                    ServerVersionSupported.SUPPORTED -> {}

                    ServerVersionSupported.NOT_SUPPORTED,
                    ServerVersionSupported.UNKNOWN,
                    -> {
                        val resources = LocalResources.current
                        val message =
                            remember(resources) {
                                if (state.serverVersion.isNotNullOrBlank()) {
                                    resources.getString(R.string.server_version_not_supported) + ": ${state.serverVersion}"
                                } else {
                                    resources.getString(R.string.server_version_not_supported) + ": " +
                                        resources.getString(R.string.unknown)
                                }
                            }
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, end = 32.dp, bottom = 32.dp)
                                    .align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    if (showAddUser) {
        var useQuickConnect by remember { mutableStateOf(state.quickConnectEnabled || connectOnly) }
        LaunchedEffect(Unit) {
            viewModel.clearSwitchUserState()
            viewModel.resetAttempts()
            if (useQuickConnect) {
                viewModel.initiateQuickConnect(server, addUser)
            }
        }
        BasicDialog(
            onDismissRequest = {
                viewModel.cancelQuickConnect()
                hideAddUserDialog()
            },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                        .focusGroup()
                        .padding(24.dp)
                        .fillMaxWidth(.4f),
            ) {
                if (useQuickConnect) {
                    if (state.quickConnectStatus == null && state.switchUserState !is LoadingState.Error) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier =
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .align(Alignment.CenterHorizontally),
                        ) {
                            CircularProgress(Modifier.size(20.dp))
                            Text(
                                text = "Waiting for Quick Connect code...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier,
                            )
                        }
                    } else if (state.quickConnectStatus != null) {
                        val approveUrl = quickConnectApproveUrl(state.quickConnectStatus?.code)
                        if (approveUrl != null) {
                            // WeaselFin: the phone scans this, signs in with the website account
                            // and approves the code. Typing the code on theweasel.tv/plex works too.
                            Text(
                                text =
                                    stringResource(
                                        R.string.quick_connect_scan_hint,
                                        BuildConfig.QUICK_CONNECT_APPROVE_LABEL,
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            QrCode(
                                content = approveUrl,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        } else {
                            Text(
                                text = "Use Quick Connect on your device to authenticate to ${server.name ?: server.url}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = state.quickConnectStatus?.code ?: "Failed to get code",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                    UserStateError(state.switchUserState)
                    if (!connectOnly) {
                        TextButton(
                            stringRes = R.string.username_or_password,
                            onClick = {
                                viewModel.cancelQuickConnect()
                                viewModel.clearSwitchUserState()
                                useQuickConnect = false
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                } else {
                    var password by remember { mutableStateOf("") }
                    val onSubmit = {
                        viewModel.login(
                            server,
                            addUser,
                            username,
                            password,
                        )
                    }
                    val focusRequester = remember { FocusRequester() }
                    val passwordFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (username.isBlank()) {
                            focusRequester.tryRequestFocus()
                        } else {
                            passwordFocusRequester.tryRequestFocus()
                        }
                    }
                    Text(
                        text = "Enter username/password to login to ${server.name ?: server.url}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    UserStateError(state.switchUserState)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            text = stringResource(R.string.username),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        EditTextBox(
                            value = username,
                            onValueChange = { username = it },
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onNext = {
                                        passwordFocusRequester.tryRequestFocus()
                                    },
                                ),
                            //                                onKeyboardAction = {
//                                    passwordFocusRequester.tryRequestFocus()
//                                },
                            isInputValid = { state.switchUserState !is LoadingState.Error },
                            modifier = Modifier.keepFocusedItemVisible().focusRequester(focusRequester),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            text = stringResource(R.string.password),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        LaunchedEffect(password) {
                            viewModel.clearSwitchUserState()
                        }
                        EditTextBox(
                            value = password,
                            onValueChange = { password = it },
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Go,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onGo = { onSubmit.invoke() },
                                ),
                            isInputValid = { state.switchUserState !is LoadingState.Error },
                            modifier = Modifier.keepFocusedItemVisible().focusRequester(passwordFocusRequester),
                        )
                    }
                    TextButton(
                        stringRes = R.string.login,
                        onClick = { onSubmit.invoke() },
                        enabled = username.isNotNullOrBlank(),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                if (state.loginAttempts > 2) {
                    Text(
                        text = "Trouble logging in?",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    TextButton(
                        stringRes = R.string.show_debug_info,
                        onClick = {
                            viewModel.navigationManager.navigateTo(Destination.Debug)
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
    switchUserWithPin?.let { user ->
        PinEntryDialog(
            onDismissRequest = { switchUserWithPin = null },
            onClickServerAuth = {
                showAddUserDialog(user)
                switchUserWithPin = null
            },
            onTextChange = {
                if (it == user.pin) {
                    trySwitchUser(user)
                }
            },
        )
    }
}

/**
 * WeaselPlex first screen: the brand, "To start watching connect your WeaselPlex account to the
 * app." and the QR code a phone scans to approve this TV on theweasel.tv. The code starts as soon
 * as the screen shows; an approval signs the TV in and the view model moves on. A code that
 * expires (or a Deny, which simply lets it expire) offers a fresh one.
 */
@Composable
private fun ConnectYourAccount(
    server: JellyfinServer,
    viewModel: SwitchUserViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val errorFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(state.switchUserState) {
        if (state.switchUserState is LoadingState.Error) {
            scrollState.scrollTo(0)
            errorFocus.tryRequestFocus()
        }
    }

    fun newCode() {
        viewModel.clearSwitchUserState()
        viewModel.resetAttempts()
        viewModel.initiateQuickConnect(server, null)
    }
    LaunchedEffect(server) { newCode() }
    DisposableEffect(Unit) { onDispose { viewModel.cancelQuickConnect() } }

    BoxWithConstraints(modifier = modifier) {
        val errorModifier =
            if (state.switchUserState is LoadingState.Error) {
                Modifier
                    .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(0.dp))
                    .verticalScroll(scrollState)
                    .focusRequester(errorFocus)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val amount =
                            when {
                                event.key == Key.DirectionDown && scrollState.canScrollForward -> {
                                    100f
                                }

                                event.key == Key.DirectionUp && scrollState.canScrollBackward -> {
                                    -100f
                                }

                                event.key == Key.DirectionDown -> {
                                    retryFocus.tryRequestFocus()
                                    return@onKeyEvent true
                                }

                                else -> {
                                    return@onKeyEvent false
                                }
                            }
                        scope.launch {
                            errorFocus.tryRequestFocus()
                            scrollState.scrollBy(amount)
                        }
                        true
                    }
            } else {
                Modifier
            }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(.6f)
                    .focusGroup()
                    .then(errorModifier),
        ) {
            if (isWeaselTv()) NeonBrandRow(mascotSize = 40.dp, wordmarkSize = 34.sp)
            Text(
                text = stringResource(R.string.connect_account_intro),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            val status = state.quickConnectStatus
            val approveUrl = quickConnectApproveUrl(status?.code)
            if (state.switchUserState is LoadingState.Error) {
                UserStateError(state.switchUserState)
                TextButton(
                    stringRes = R.string.get_new_code,
                    onClick = { newCode() },
                    modifier = Modifier.focusRequester(retryFocus),
                )
            } else if (status == null || approveUrl == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(min = 32.dp),
                ) {
                    CircularProgress(Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.getting_code),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Text(
                    text =
                        stringResource(
                            R.string.quick_connect_scan_hint,
                            BuildConfig.QUICK_CONNECT_APPROVE_LABEL,
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                QrCode(content = approveUrl)
                Text(
                    text = status.code,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun UserStateError(
    userState: LoadingState,
    modifier: Modifier = Modifier,
) {
    when (val s = userState) {
        is LoadingState.Error -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = modifier,
            ) {
                s.message?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (s.exception != null) {
                    s.exception.localizedMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    s.exception.cause?.localizedMessage?.let {
                        Text(
                            text = "Cause: $it",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        else -> {}
    }
}
