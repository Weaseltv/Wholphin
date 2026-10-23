package com.github.damontecres.wholphin.ui.kids

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidLevel
import com.github.damontecres.wholphin.kids.KidProfile
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.avatars.ProfilePicturePicker
import com.github.damontecres.wholphin.ui.preferences.SetPinFlow
import com.github.damontecres.wholphin.ui.setup.PinEntry
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.QrCode
import com.github.damontecres.wholphin.ui.setup.forgotPinUrl
import com.github.damontecres.wholphin.ui.setup.pairKidUrl
import com.github.damontecres.wholphin.ui.setup.pinPromptFor
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay
import java.util.UUID

/** The chooser's "Add kid profile" flow, in one panel dialog (plan 4.3; owner mockups 2026-10-22). */
@Composable
fun AddKidFlow(
    server: JellyfinServer,
    users: List<JellyfinUser>,
    onClose: () -> Unit,
    imageUrls: Map<UUID, String?> = emptyMap(),
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
            onAddPin = {
                viewModel.createFirstPin(it)
                true
            },
            onRemovePin = {},
            onDismissRequest = { settingPin = false },
        )
        return
    }

    // Contract 12.4: "Create PIN" for another adult, until the step moves on (saved) or it is dismissed
    var creatingPinFor by remember(step) { mutableStateOf<JellyfinUser?>(null) }
    creatingPinFor?.let { adult ->
        SetPinFlow(
            title = stringResource(R.string.adult_pin_title, adult.shownName.orEmpty()),
            currentPin = null,
            onAddPin = { viewModel.createAdultPin(adult, it) },
            onRemovePin = {},
            onDismissRequest = { creatingPinFor = null },
        )
        return
    }

    val title = stringResource(R.string.add_kid_profile)
    KidPanelDialog(
        onDismissRequest = close,
        width = panelWidth(step),
        // The PIN entry takes every D-pad key, so it keeps its footer on screen instead of scrolling
        scrollable = step !is AddKidStep.EnterPin,
    ) {
        when (val s = step) {
            AddKidStep.Loading,
            AddKidStep.Done,
            -> {
                KidTitle(title)
                KidGap(28.dp)
                KidSpinner(size = 56.dp, strokeWidth = 5.dp)
                KidGap(12.dp)
            }

            is AddKidStep.PickParent -> {
                KidTitle(title)
                KidGap(10.dp)
                KidSubtitle(stringResource(R.string.who_is_adding_kid))
                KidGap(28.dp)
                TileRow {
                    s.parents.forEachIndexed { index, parent ->
                        val name = parent.shownName ?: parent.id.toString()
                        KidTile(
                            label = name,
                            size = tileSize(s.parents.size),
                            onClick = { viewModel.selectParent(parent) },
                            modifier = Modifier.initialFocus(index == 0),
                        ) { KidAvatar(name, imageUrls[parent.id]) }
                    }
                }
                KidGap(26.dp)
                OkBackHints()
            }

            is AddKidStep.EnterPin -> {
                PinStep(s, onSubmit = { pin ->
                    viewModel.submitPin(pin).join()
                    if (viewModel.lastPinWasWrong()) PinOutcome.WRONG else PinOutcome.OTHER
                })
            }

            is AddKidStep.CreateFirstPin -> {
                KidTitle(title)
                KidGap(14.dp)
                KidSubtitle(stringResource(R.string.set_pin_first, s.parent.shownName.orEmpty()), color = KidColors.Text)
                if (s.saveFailed) {
                    KidGap(12.dp)
                    KidErrorText(stringResource(R.string.pin_save_failed))
                }
                KidGap(28.dp)
                KidButton(
                    text = stringResource(R.string.set_pin_button),
                    onClick = { settingPin = true },
                    arrow = true,
                    modifier = Modifier.initialFocus(true),
                )
                KidGap(24.dp)
                OkBackHints()
            }

            is AddKidStep.ParentLocked -> {
                KidTitle(title)
                KidGap(14.dp)
                KidSubtitle(stringResource(R.string.parent_locked, s.parent.shownName.orEmpty()), color = KidColors.Text)
                KidGap(28.dp)
                KidButton(stringResource(R.string.cancel), onClick = close, modifier = Modifier.initialFocus(true))
            }

            is AddKidStep.AdultPins -> {
                KidTitle(title)
                KidGap(10.dp)
                KidSubtitle(stringResource(R.string.adult_pins_subtitle))
                if (s.removeFailed) {
                    KidGap(10.dp)
                    KidErrorText(stringResource(R.string.adult_remove_failed))
                }
                KidGap(24.dp)
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                ) {
                    s.adults.forEachIndexed { index, adult ->
                        key(adult.rowId) {
                            AdultPinRow(
                                adult = adult,
                                imageUrl = imageUrls[adult.id],
                                onCreatePin = { creatingPinFor = adult },
                                onRemove = { viewModel.removeAdult(adult) },
                                focus = index == 0,
                            )
                        }
                    }
                }
                KidGap(24.dp)
                OkBackHints()
            }

            AddKidStep.ChooseHow -> {
                KidTitle(title)
                KidGap(10.dp)
                KidSubtitle(stringResource(R.string.kid_how_subtitle))
                KidGap(24.dp)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KidListRow(
                        text = stringResource(R.string.create_new_kid_profile),
                        glyph = stringResource(R.string.fa_user),
                        onClick = viewModel::createNew,
                        modifier = Modifier.initialFocus(true),
                    )
                    KidListRow(
                        text = stringResource(R.string.add_existing_kid_profile),
                        glyph = stringResource(R.string.fa_tv),
                        onClick = { viewModel.addExisting() },
                    )
                }
                KidGap(24.dp)
                OkBackHints()
            }

            is AddKidStep.Pairing -> {
                PairingStep(s, title, onSignInKid = viewModel::signInExisting)
            }

            AddKidStep.PairingRefused -> {
                KidEyebrow(title)
                KidGap(22.dp)
                Text(
                    text = stringResource(R.string.pair_refused),
                    style = KidText.body(22.sp, FontWeight.Bold),
                    color = KidColors.Text,
                    textAlign = TextAlign.Center,
                )
                KidGap(28.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KidButton(
                        stringResource(R.string.pair_new_code),
                        onClick = { viewModel.addExisting() },
                        minWidth = 200.dp,
                        modifier = Modifier.initialFocus(true),
                    )
                    KidButton(stringResource(R.string.cancel), onClick = close, minWidth = 180.dp)
                }
                KidGap(24.dp)
                OkBackHints()
            }

            is AddKidStep.Form -> {
                KidTitle(title)
                KidGap(20.dp)
                KidForm(s, viewModel)
            }

            is AddKidStep.PickPicture -> {
                ProfilePicturePicker(
                    title = title,
                    subtitle = stringResource(R.string.pick_a_picture_for, s.name),
                    avatars = viewModel.avatars,
                    current = s.selected,
                    onPick = { viewModel.pickPicture(it) },
                    onSkip = { viewModel.pickPicture(null) },
                )
            }

            is AddKidStep.Working -> {
                KidGap(12.dp)
                KidEyebrow(title)
                KidGap(30.dp)
                KidSpinner()
                KidGap(36.dp)
                val line =
                    when (s.work) {
                        Work.Creating -> stringResource(R.string.creating_kid, s.kidName)
                        Work.Connecting -> stringResource(R.string.adding_kid_to_tv, s.kidName)
                    }
                Text(
                    text = emphasise(line, s.kidName, KidColors.Lime),
                    style = KidText.body(26.sp, FontWeight.Bold),
                    color = KidColors.Text,
                    textAlign = TextAlign.Center,
                )
                KidGap(10.dp)
                KidSubtitle(stringResource(R.string.kid_takes_a_moment))
                KidGap(12.dp)
            }

            is AddKidStep.Failed -> {
                KidEyebrow(title)
                KidGap(22.dp)
                Text(
                    text = errorText(s.error),
                    style = KidText.body(22.sp, FontWeight.Bold),
                    color = KidColors.Text,
                    textAlign = TextAlign.Center,
                )
                KidGap(28.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (s.retry != Retry.None) {
                        KidButton(
                            stringResource(R.string.retry),
                            onClick = viewModel::retry,
                            minWidth = 180.dp,
                            modifier = Modifier.initialFocus(true),
                        )
                    }
                    KidButton(
                        stringResource(R.string.cancel),
                        onClick = close,
                        minWidth = 180.dp,
                        modifier = Modifier.initialFocus(s.retry == Retry.None),
                    )
                }
                KidGap(24.dp)
                OkBackHints()
            }
        }
    }
}

private fun panelWidth(step: AddKidStep): Dp =
    when (step) {
        is AddKidStep.Form, is AddKidStep.PickPicture, is AddKidStep.AdultPins, is AddKidStep.Pairing -> 750.dp
        is AddKidStep.ChooseHow, is AddKidStep.PickParent -> 680.dp
        is AddKidStep.EnterPin -> 590.dp
        else -> 540.dp
    }

/** Three tiles fit at the mockup's size; more get smaller tiles (and the row scrolls past four). */
private fun tileSize(count: Int): Dp = if (count <= 3) 150.dp else 112.dp

/** Takes focus once when [focus] is true. */
@Composable
private fun Modifier.initialFocus(focus: Boolean): Modifier {
    if (!focus) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.tryRequestFocus() }
    return focusRequester(requester)
}

@Composable
private fun TileRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .focusGroup()
                .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        content()
    }
}

/** Contract 12.4: one adult without a PIN, with "Create PIN" and "Remove from this TV" */
@Composable
private fun AdultPinRow(
    adult: JellyfinUser,
    imageUrl: String?,
    onCreatePin: () -> Unit,
    onRemove: () -> Unit,
    focus: Boolean,
) {
    val name = adult.shownName ?: adult.id.toString()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.size(56.dp)) { KidAvatar(name, imageUrl, letterSize = 26.sp) }
        Text(
            text = name,
            style = KidText.body(19.sp, FontWeight.SemiBold),
            color = KidColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        KidButton(
            stringResource(R.string.create_pin),
            onClick = onCreatePin,
            minWidth = 150.dp,
            modifier = Modifier.initialFocus(focus),
        )
        KidButton(stringResource(R.string.remove_from_this_tv), onClick = onRemove, minWidth = 150.dp)
    }
}

/**
 * Contract 12.3 "Add an existing kid profile": the pairing code, big, with where to enter it and a QR code for the
 * website's Kids profiles page, then this account's own kids that aren't on this TV yet.
 */
@Composable
private fun PairingStep(
    step: AddKidStep.Pairing,
    title: String,
    onSignInKid: (KidProfile) -> Unit,
) {
    KidEyebrow(title)
    KidGap(12.dp)
    KidTitle(stringResource(R.string.add_existing_kid_profile))
    KidGap(22.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        pairKidUrl()?.let { QrCode(content = it, size = 150.dp) }
        Column(modifier = Modifier.width(420.dp)) {
            val code = step.code
            if (code != null) {
                Text(
                    text = AddKidViewModel.formatCode(code),
                    style = KidText.display(52.sp, tracking = .08.em),
                    color = KidColors.Lime,
                )
            } else {
                KidSpinner(size = 44.dp, strokeWidth = 4.dp)
            }
            KidGap(12.dp)
            val site = forgotPinUrl()?.removePrefix("https://")?.removePrefix("http://").orEmpty()
            Text(
                text = emphasise(stringResource(R.string.pair_code_instructions, site), site, KidColors.Lime),
                style = KidText.body(17.sp),
                color = KidColors.Text,
            )
            if (step.trouble) {
                KidGap(10.dp)
                KidErrorText(stringResource(R.string.pair_trouble))
            }
        }
    }
    if (step.kids.isNotEmpty()) {
        KidGap(22.dp)
        KidDivider()
        KidGap(16.dp)
        KidSectionTitle(stringResource(R.string.pair_your_kids), Modifier.fillMaxWidth())
        KidGap(12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup()) {
            step.kids.forEachIndexed { index, kid ->
                key(kid.id) {
                    KidListRow(
                        text = stringResource(R.string.sign_in_kid_on_tv, kid.displayName),
                        glyph = stringResource(R.string.fa_user),
                        onClick = { onSignInKid(kid) },
                        modifier = Modifier.initialFocus(index == 0),
                    )
                }
            }
        }
    }
    KidGap(22.dp)
    OkBackHints()
}

@Composable
private fun PinStep(
    step: AddKidStep.EnterPin,
    onSubmit: suspend (String) -> PinOutcome,
) {
    val name = step.parent.shownName.orEmpty()
    val message =
        when (val error = step.error) {
            is PinError.Wrong -> {
                if (error.attemptsBeforeLockout in 1..9) {
                    stringResource(R.string.pin_wrong_attempts, error.attemptsBeforeLockout)
                } else {
                    stringResource(R.string.pin_wrong)
                }
            }

            is PinError.LockedOut -> {
                var seconds by remember(error) { mutableIntStateOf(secondsUntil(error.retryAtMillis)) }
                LaunchedEffect(error) {
                    while (seconds > 0) {
                        delay(1_000)
                        seconds = secondsUntil(error.retryAtMillis)
                    }
                }
                stringResource(R.string.pin_locked_out, seconds)
            }

            PinError.CheckFailed -> {
                stringResource(R.string.kids_error_generic)
            }

            null -> {
                null
            }
        }
    PinEntry(
        title = stringResource(R.string.add_kid_profile),
        prompt = pinPromptFor(name),
        length = PinRules.entryLength(step.parent.pin),
        lockedUntilMillis = (step.error as? PinError.LockedOut)?.retryAtMillis ?: 0L,
        message = message,
        // Latched until the check is done; a wrong PIN then clears the boxes
        onSubmit = { if (it.isNotEmpty()) onSubmit(it) else PinOutcome.OTHER },
    )
}

private fun secondsUntil(atMillis: Long) = ((atMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

@Composable
private fun KidForm(
    form: AddKidStep.Form,
    viewModel: AddKidViewModel,
) {
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocus.tryRequestFocus() }
    Column(modifier = Modifier.fillMaxWidth().focusGroup()) {
        KidFieldLabel(stringResource(R.string.kid_name))
        KidGap(10.dp)
        KidTextField(
            value = form.name,
            onValueChange = { viewModel.updateForm(name = it.take(40)) },
            placeholder = stringResource(R.string.kid_name_placeholder),
            isError = form.nameError != null,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.focusRequester(nameFocus),
        )
        when (form.nameError) {
            NameError.Invalid -> {
                KidGap(8.dp)
                KidErrorText(stringResource(R.string.kid_name_invalid))
            }

            NameError.Taken -> {
                KidGap(8.dp)
                KidErrorText(stringResource(R.string.kid_name_taken))
            }

            null -> {}
        }
        KidGap(28.dp)
        KidSectionTitle(stringResource(R.string.kid_level))
        KidGap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KidLevel.entries.forEach { level ->
                KidOptionCard(
                    title = stringResource(level.label),
                    description = stringResource(level.description),
                    selected = form.level == level,
                    onClick = { viewModel.updateForm(level = level) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        form.error?.let {
            KidGap(10.dp)
            KidErrorText(errorText(it), Modifier.fillMaxWidth())
        }
        KidGap(26.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OkBackHints()
            Spacer(Modifier.weight(1f))
            KidButton(
                text = stringResource(R.string.next),
                onClick = viewModel::submitForm,
                arrow = true,
                minWidth = 220.dp,
            )
        }
    }
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
