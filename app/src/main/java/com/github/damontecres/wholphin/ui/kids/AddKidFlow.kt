package com.github.damontecres.wholphin.ui.kids

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidLevel
import com.github.damontecres.wholphin.ui.avatars.ProfilePicturePicker
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.EditTextBox
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.preferences.SetPinFlow
import com.github.damontecres.wholphin.ui.setup.PinEntryCreate
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/** The chooser's "Add kid profile" flow, in one dialog (plan 4.3). */
@Composable
fun AddKidFlow(
    server: JellyfinServer,
    users: List<JellyfinUser>,
    onClose: () -> Unit,
    viewModel: AddKidViewModel = hiltViewModel(),
) {
    LaunchedEffect(server) { viewModel.start(server, users) }
    val step by viewModel.step.collectAsState()
    val close = {
        viewModel.cancel()
        onClose()
    }
    if (step == AddKidStep.Done) return

    val tofu = step as? AddKidStep.CreateFirstPin
    var settingPin by remember(tofu) { mutableStateOf(false) }
    if (tofu != null && settingPin) {
        SetPinFlow(
            currentPin = null,
            onAddPin = { viewModel.createFirstPin(it) },
            onRemovePin = {},
            onDismissRequest = { settingPin = false },
        )
        return
    }

    BasicDialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(24.dp)
                    .fillMaxWidth(.5f),
        ) {
            Title(stringResource(R.string.add_kid_profile))
            when (val s = step) {
                AddKidStep.Loading,
                AddKidStep.Done,
                -> {
                    CircularProgress(Modifier.size(32.dp))
                }

                is AddKidStep.PickParent -> {
                    Body(stringResource(R.string.who_is_adding_kid))
                    s.parents.forEachIndexed { index, parent ->
                        FocusedButton(
                            text = parent.name ?: parent.id.toString(),
                            focus = index == 0,
                            onClick = { viewModel.selectParent(parent) },
                        )
                    }
                }

                is AddKidStep.EnterPin -> {
                    PinStep(s, onSubmit = viewModel::submitPin)
                }

                is AddKidStep.CreateFirstPin -> {
                    Body(stringResource(R.string.set_pin_first, s.parent.name.orEmpty()))
                    FocusedButton(stringResource(R.string.set_pin_button), focus = true) { settingPin = true }
                }

                is AddKidStep.ParentLocked -> {
                    Body(stringResource(R.string.parent_locked, s.parent.name.orEmpty()))
                    FocusedButton(stringResource(R.string.cancel), focus = true, onClick = close)
                }

                is AddKidStep.ChooseKid -> {
                    Body(stringResource(R.string.kids_on_account))
                    s.kids.forEachIndexed { index, kid ->
                        val active = kid.state == "active"
                        FocusedButton(
                            text =
                                if (active) {
                                    stringResource(R.string.sign_in_kid_on_tv, kid.displayName)
                                } else {
                                    stringResource(R.string.kid_not_available_short, kid.displayName)
                                },
                            focus = index == 0,
                            enabled = active,
                            onClick = { viewModel.signInExisting(kid) },
                        )
                    }
                    if (s.kids.size < s.limit) {
                        FocusedButton(stringResource(R.string.create_new_kid), focus = false, onClick = viewModel::createNew)
                    }
                }

                is AddKidStep.Form -> {
                    KidForm(s, viewModel)
                }

                is AddKidStep.PickPicture -> {
                    ProfilePicturePicker(
                        name = s.name,
                        avatars = viewModel.avatars,
                        current = s.selected,
                        onPick = { viewModel.pickPicture(it) },
                        onSkip = { viewModel.pickPicture(null) },
                    )
                }

                is AddKidStep.Working -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgress(Modifier.size(24.dp))
                        Body(
                            when (s.work) {
                                Work.Creating -> stringResource(R.string.creating_kid, s.kidName)
                                Work.Connecting -> stringResource(R.string.signing_in_kid, s.kidName)
                            },
                        )
                    }
                }

                is AddKidStep.Failed -> {
                    Text(
                        text = errorText(s.error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (s.retry != Retry.None) {
                            FocusedButton(stringResource(R.string.retry), focus = true, onClick = viewModel::retry)
                        }
                        FocusedButton(stringResource(R.string.cancel), focus = s.retry == Retry.None, onClick = close)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinStep(
    step: AddKidStep.EnterPin,
    onSubmit: (String) -> Unit,
) {
    Body(stringResource(R.string.enter_your_pin_name, step.parent.name.orEmpty()))
    Body(stringResource(R.string.enter_pin_then_select))
    val focus = remember { FocusRequester() }
    // A wrong PIN clears the dots for the next try.
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) {
        attempt++
        focus.tryRequestFocus()
    }
    key(attempt) {
        PinEntryCreate(
            title = R.string.enter_pin,
            onTextChange = {},
            onConfirm = { if (it.isNotEmpty()) onSubmit(it) },
            modifier = Modifier.focusRequester(focus),
        )
    }
    when (val error = step.error) {
        is PinError.Wrong -> {
            ErrorText(
                if (error.attemptsBeforeLockout in 1..9) {
                    stringResource(R.string.pin_wrong_attempts, error.attemptsBeforeLockout)
                } else {
                    stringResource(R.string.pin_wrong)
                },
            )
        }

        is PinError.LockedOut -> {
            var seconds by remember(error) { mutableIntStateOf(secondsUntil(error.retryAtMillis)) }
            LaunchedEffect(error) {
                while (seconds > 0) {
                    delay(1_000)
                    seconds = secondsUntil(error.retryAtMillis)
                }
            }
            ErrorText(stringResource(R.string.pin_locked_out, seconds))
        }

        null -> {}
    }
}

private fun secondsUntil(atMillis: Long) = ((atMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

@Composable
private fun KidForm(
    form: AddKidStep.Form,
    viewModel: AddKidViewModel,
) {
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.tryRequestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.kid_name),
            modifier = Modifier.padding(end = 8.dp),
        )
        EditTextBox(
            value = form.name,
            onValueChange = { viewModel.updateForm(name = it.take(40)) },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            isInputValid = { form.nameError == null },
            modifier = Modifier.focusRequester(nameFocus),
        )
    }
    when (form.nameError) {
        NameError.Invalid -> {
            ErrorText(stringResource(R.string.kid_name_invalid))
        }

        NameError.Taken -> {
            ErrorText(stringResource(R.string.kid_name_taken))
        }

        null -> {}
    }
    Body(stringResource(R.string.kid_level))
    KidLevel.entries.forEach { level ->
        ListItem(
            selected = form.level == level,
            onClick = { viewModel.updateForm(level = level) },
            headlineContent = { Text(stringResource(level.label)) },
            supportingContent = { Text(stringResource(level.description)) },
            trailingContent = { RadioButton(selected = form.level == level, onClick = null) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    form.error?.let { ErrorText(errorText(it)) }
    TextButton(stringRes = R.string.next, onClick = viewModel::submitForm)
}

@Composable
private fun FocusedButton(
    text: String,
    focus: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { requester.tryRequestFocus() }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusRequester(requester),
    ) {
        Text(text)
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

val KidLevel.label: Int
    get() =
        when (this) {
            KidLevel.KIDS -> R.string.kid_level_kids
            KidLevel.OLDER_KIDS -> R.string.kid_level_older_kids
            KidLevel.TEENS -> R.string.kid_level_teens
        }

val KidLevel.description: Int
    get() =
        when (this) {
            KidLevel.KIDS -> R.string.kid_level_kids_description
            KidLevel.OLDER_KIDS -> R.string.kid_level_older_kids_description
            KidLevel.TEENS -> R.string.kid_level_teens_description
        }

@Composable
private fun errorText(error: AddKidError): String =
    when (error) {
        AddKidError.NoParent -> {
            stringResource(R.string.kids_error_no_parent)
        }

        AddKidError.NotAvailable -> {
            stringResource(R.string.kids_error_unavailable)
        }

        AddKidError.LimitReached -> {
            stringResource(R.string.kids_error_limit)
        }

        AddKidError.NameTaken -> {
            stringResource(R.string.kid_name_taken)
        }

        AddKidError.PickLevel -> {
            stringResource(R.string.kid_pick_level)
        }

        AddKidError.AccessInactive -> {
            stringResource(R.string.kids_error_access)
        }

        AddKidError.SignInAgain -> {
            stringResource(R.string.kids_error_sign_in_again)
        }

        AddKidError.KidNotActive -> {
            stringResource(R.string.kids_error_kid_not_active)
        }

        is AddKidError.RateLimited -> {
            error.minutes?.let { stringResource(R.string.kids_error_rate_limited, it) }
                ?: stringResource(R.string.kids_error_rate_limited_unknown)
        }

        AddKidError.Network -> {
            stringResource(R.string.kids_error_network)
        }

        AddKidError.Generic -> {
            stringResource(R.string.kids_error_generic)
        }
    }
