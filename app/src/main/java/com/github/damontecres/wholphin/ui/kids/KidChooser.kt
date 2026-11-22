package com.github.damontecres.wholphin.ui.kids

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRemoval
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureDialog
import com.github.damontecres.wholphin.ui.preferences.SetPinFlow
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.outcome
import com.github.damontecres.wholphin.ui.setup.pinPromptFor
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * Runs [content] with [user]'s saved row once its account PIN was pulled (contract 11.2: before a PIN prompt shows).
 * Nothing shows while it is pulled; that is short and best effort.
 */
@Composable
private fun WithAccountPin(
    user: JellyfinUser,
    refresh: suspend (JellyfinUser) -> JellyfinUser,
    content: @Composable (JellyfinUser) -> Unit,
) {
    var fresh by remember(user) { mutableStateOf<JellyfinUser?>(null) }
    LaunchedEffect(user) { fresh = refresh(user) }
    fresh?.let { content(it) }
}

/**
 * "Change picture" from the chooser's profile menu (contract 11.1): kids pick from the kid list without a PIN; adults
 * pick from the adult list, and an adult with a PIN proves they are its owner first. On a kid-protected TV a pinless
 * adult's picture needs a parent's PIN (review round 1, [PictureLock.ParentPin]), also when protection turns on while the
 * dialog is open (review round 2, [ChooserPictureAccess]). These checks grant no unlock (review round 5), so nothing
 * outlives a dismissed dialog. [savedUsers] are the TV's saved profiles, the possible parents.
 */
@Composable
fun ChooserChangePicture(
    server: JellyfinServer,
    user: JellyfinUser,
    gate: KidSessionGate,
    onDone: () -> Unit,
    savedUsers: List<JellyfinUser> = emptyList(),
    refresh: suspend (JellyfinUser) -> JellyfinUser = { it },
) {
    val token = user.accessToken ?: return onDone()
    val kind = gate.profileKind(user)
    val access = remember(user) { ChooserPictureAccess(user, kind, gate, savedUsers, refresh) }
    LaunchedEffect(access) { access.follow() }
    when (val state = access.state.collectAsState().value) {
        PictureAccessState.Deciding -> {}

        PictureAccessState.Closed -> {
            LaunchedEffect(Unit) { onDone() }
        }

        is PictureAccessState.Unlocked -> {
            val row = state.row
            ProfilePictureDialog(
                target = ProfilePictureTarget(server.url, row.id, token, row.shownName, isKid = kind != ProfileKind.ADULT),
                onDismissRequest = onDone,
                mayChange = access::mayChange,
            )
        }

        is PictureAccessState.Locked -> {
            val parents = (state.lock as? PictureLock.ParentPin)?.parents
            val owner = if (parents != null) state.parent else state.row
            if (owner == null && parents != null) {
                PickPictureParent(state.row, parents, onPick = access::pickParent, onDismissRequest = onDone)
            } else if (owner != null) {
                PicturePin(owner = owner, onDismissRequest = onDone, check = access::submitPin)
            }
        }
    }
}

/** Which parent unlocks a pinless adult's picture on a protected TV */
@Composable
private fun PickPictureParent(
    row: JellyfinUser,
    parents: List<JellyfinUser>,
    onPick: (JellyfinUser) -> Unit,
    onDismissRequest: () -> Unit,
) {
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 620.dp) {
        KidTitle(stringResource(R.string.change_picture))
        KidGap(10.dp)
        KidSubtitle(stringResource(R.string.kid_ask_parent, row.shownName.orEmpty()))
        KidGap(24.dp)
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            parents.forEachIndexed { index, candidate ->
                KidListRow(
                    text = stringResource(R.string.kid_parent_pin_title, candidate.shownName.orEmpty()),
                    glyph = stringResource(R.string.fa_user),
                    onClick = { onPick(candidate) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        KidGap(24.dp)
        OkBackHints()
    }
}

/** [owner]'s PIN before a picture changes; the entry stays latched while [check] runs (a correct one moves on) */
@Composable
private fun PicturePin(
    owner: JellyfinUser,
    onDismissRequest: () -> Unit,
    check: suspend (String) -> PinCheckResult,
) {
    var error by remember(owner) { mutableStateOf<PinCheckResult?>(null) }
    key(owner.rowId) {
        PinEntryDialog(
            onDismissRequest = onDismissRequest,
            title = stringResource(R.string.change_picture),
            prompt = pinPromptFor(owner.shownName),
            length = PinRules.entryLength(owner.pin),
            lockedUntilMillis = (error as? PinCheckResult.LockedOut)?.retryAtMillis ?: 0L,
            message =
                when (error) {
                    is PinCheckResult.Wrong -> stringResource(R.string.pin_wrong)
                    is PinCheckResult.LockedOut -> stringResource(R.string.kids_error_rate_limited_unknown)
                    else -> null
                },
            onSubmit = { pin ->
                val result = check(pin)
                if (result != PinCheckResult.Correct) error = result
                result.outcome()
            },
        )
    }
}

/**
 * "Change PIN" from the chooser's profile menu (contract 11.2): the current PIN (checked by the gate, with its backoff),
 * then the new PIN, then the new PIN again. The new PIN goes to the account first; nothing changes if that fails.
 */
@Composable
fun ChooserChangePin(
    user: JellyfinUser,
    gate: KidSessionGate,
    refresh: suspend (JellyfinUser) -> JellyfinUser,
    changePin: suspend (JellyfinUser, String) -> Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    WithAccountPin(user, refresh) { row ->
        if (!row.hasPin) {
            // The account has no PIN any more: nothing to change
            LaunchedEffect(Unit) { onDone() }
            return@WithAccountPin
        }
        SetPinFlow(
            title = stringResource(R.string.change_pin),
            currentPin = row.pin,
            checkCurrentPin = { gate.checkOwnPin(row, it) },
            allowRemove = false,
            onAddPin = { pin ->
                changePin(row, pin).also { saved ->
                    if (saved) {
                        Toast.makeText(context, context.getString(R.string.pin_changed), Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
            },
            onRemovePin = {},
            onDismissRequest = onDone,
        )
    }
}

/**
 * "Remove PIN" from the chooser's profile menu (owner 2026-10-25). With a kid profile saved on this TV it only says why
 * not: every grown-up on a TV with kids needs a PIN (contract 12.4). Otherwise the current PIN (checked by the gate,
 * with its backoff), then the PIN is removed, the account first; nothing changes if that fails.
 */
@Composable
fun ChooserRemovePin(
    user: JellyfinUser,
    gate: KidSessionGate,
    refresh: suspend (JellyfinUser) -> JellyfinUser,
    kidsOnTv: suspend () -> Boolean,
    removePin: suspend (JellyfinUser) -> PinRemoval,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var hasKids by remember(user) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(user) { hasKids = kidsOnTv() }
    when (hasKids) {
        null -> {}

        true -> {
            KidsNeedPinPanel(name = user.shownName.orEmpty(), onDismissRequest = onDone)
        }

        false -> {
            WithAccountPin(user, refresh) { row ->
                if (!row.hasPin) {
                    // The account has no PIN any more: nothing to remove
                    LaunchedEffect(Unit) { onDone() }
                    return@WithAccountPin
                }
                var message by remember { mutableStateOf<String?>(null) }
                var lockedUntil by remember { mutableLongStateOf(0L) }
                PinEntryDialog(
                    onDismissRequest = onDone,
                    title = stringResource(R.string.remove_pin),
                    prompt = AnnotatedString(stringResource(R.string.pin_prompt_current)),
                    length = PinRules.entryLength(row.pin),
                    lockedUntilMillis = lockedUntil,
                    message = message,
                    onSubmit = { typed ->
                        // Suspends for the check and the removal, so the entry stays latched until they are done
                        val result = gate.checkOwnPin(row, typed)
                        when (result) {
                            PinCheckResult.Correct -> {
                                when (removePin(row)) {
                                    PinRemoval.REMOVED -> {
                                        Toast.makeText(context, context.getString(R.string.pin_removed), Toast.LENGTH_SHORT).show()
                                        onDone()
                                    }

                                    PinRemoval.KIDS_ON_TV -> {
                                        hasKids = true
                                    }

                                    PinRemoval.FAILED -> {
                                        message = context.getString(R.string.pin_remove_failed)
                                    }
                                }
                            }

                            is PinCheckResult.Wrong -> {
                                message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                            }

                            is PinCheckResult.LockedOut -> {
                                val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                                message = context.getString(R.string.kid_pin_locked_out, seconds)
                                lockedUntil = result.retryAtMillis
                            }
                        }
                        result.outcome()
                    },
                )
            }
        }
    }
}

/** "<Name>'s PIN can't be removed": this TV has kid profiles, so every grown-up needs a PIN. OK or Back closes it. */
@Composable
private fun KidsNeedPinPanel(
    name: String,
    onDismissRequest: () -> Unit,
) {
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 620.dp) {
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
        KidTitle(stringResource(R.string.pin_cant_be_removed, name))
        KidGap(10.dp)
        KidSubtitle(stringResource(R.string.pin_cant_be_removed_subtitle))
        KidGap(28.dp)
        KidButton(
            stringResource(R.string.pin_ok),
            onClick = onDismissRequest,
            minWidth = 180.dp,
            modifier = Modifier.focusRequester(firstFocus),
        )
    }
}
