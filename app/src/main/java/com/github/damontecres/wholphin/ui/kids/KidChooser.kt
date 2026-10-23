package com.github.damontecres.wholphin.ui.kids

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.AccountPinWrite
import com.github.damontecres.wholphin.kids.AccountPins
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.profilePicturesEnabled
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureDialog
import com.github.damontecres.wholphin.ui.preferences.SetPinFlow
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.outcome
import com.github.damontecres.wholphin.ui.setup.pinPromptFor
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

/** Kid state for the chooser and Settings, read only through [KidSessionGate]. */
@HiltViewModel
class KidProfilesViewModel
    @Inject
    constructor(
        val gate: KidSessionGate,
        pictures: ProfilePictureService,
        private val accountPins: AccountPins,
    ) : ViewModel() {
        val isKidSession: StateFlow<Boolean> = gate.isKidSession

        /** Profile image cache versions, applied to the chooser's image URLs (plan 4.6). */
        val pictureVersions: StateFlow<Map<UUID, Int>> = pictures.versions

        fun isKid(user: JellyfinUser): Boolean = BuildConfig.KID_PROFILES_ENABLED && gate.profileKind(user) == ProfileKind.KID

        /** Plan 4.3 step 1: weaselfin, no kid session, and at least one saved adult. */
        fun canAddKid(
            savedUsers: List<JellyfinUser>,
            kidSession: Boolean,
        ): Boolean =
            BuildConfig.KID_PROFILES_ENABLED &&
                !kidSession &&
                AddKidViewModel.parentsOf(savedUsers, gate).isNotEmpty()

        /**
         * Chooser "Change picture" (contract 11.1): every saved profile with its own token, except a pinless adult on a
         * kid-protected TV where no other adult has a PIN to unlock it (review round 1). [savedUsers] are the TV's saved
         * profiles.
         */
        fun canChangePicture(
            user: JellyfinUser,
            savedUsers: List<JellyfinUser>,
            deviceProtected: Boolean,
        ): Boolean =
            profilePicturesEnabled &&
                pictureLock(user, gate.profileKind(user), deviceProtected, possibleParents(user, savedUsers, gate)) != PictureLock.Hidden

        /** Chooser "Change PIN" (contract 11.2): a saved profile with a PIN and its own token to write the account with. */
        fun canChangePin(user: JellyfinUser): Boolean = BuildConfig.KID_PROFILES_ENABLED && user.hasPin && user.accessToken != null

        /** [user] with its account's PIN pulled into the cache (best effort), before a PIN prompt shows (contract 11.2) */
        suspend fun refreshed(user: JellyfinUser): JellyfinUser = accountPins.refresh(user)

        companion object {
            /** The saved adults other than [target] who could unlock its picture with their PIN */
            fun possibleParents(
                target: JellyfinUser,
                savedUsers: List<JellyfinUser>,
                gate: KidSessionGate,
            ): List<JellyfinUser> = savedUsers.filter { it.rowId != target.rowId && gate.profileKind(it) == ProfileKind.ADULT }

            /**
             * What changing [target]'s picture from the chooser takes (contract 11.1, review round 1): a kid's is open; a
             * profile with a PIN asks for it; a pinless adult's is open on an unprotected TV, and on a protected one needs
             * the PIN of one of [parents] that has a PIN, or isn't offered at all when none has.
             */
            fun pictureLock(
                target: JellyfinUser,
                kind: ProfileKind,
                deviceProtected: Boolean,
                parents: List<JellyfinUser>,
            ): PictureLock {
                val unlockers = parents.filter { it.rowId != target.rowId && it.hasPin }
                return when {
                    target.accessToken == null -> PictureLock.Hidden
                    kind == ProfileKind.KID -> PictureLock.Open
                    target.hasPin -> PictureLock.OwnPin
                    !deviceProtected -> PictureLock.Open
                    unlockers.isEmpty() -> PictureLock.Hidden
                    else -> PictureLock.ParentPin(unlockers)
                }
            }
        }

        /** Saves [user]'s new PIN, the account first; returns whether it was saved (nothing changed if not) */
        suspend fun changePin(
            user: JellyfinUser,
            pin: String,
        ): Boolean = accountPins.set(user, pin, user.requireLogin) == AccountPinWrite.Saved
    }

/** What changing a profile's picture from the chooser takes (see [KidProfilesViewModel.pictureLock]) */
sealed interface PictureLock {
    data object Open : PictureLock

    /** The profile's own PIN */
    data object OwnPin : PictureLock

    /** A pinless adult on a protected TV: one of [parents]' PIN */
    data class ParentPin(
        val parents: List<JellyfinUser>,
    ) : PictureLock

    /** Not offered */
    data object Hidden : PictureLock
}

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
 * adult's picture needs a parent's PIN (review round 1, [PictureLock.ParentPin]). These checks grant no unlock (review
 * round 5), so nothing outlives a dismissed dialog. [savedUsers] are the TV's saved profiles, the possible parents.
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
    if (kind == ProfileKind.KID) {
        ProfilePictureDialog(
            target = ProfilePictureTarget(server.url, user.id, token, user.shownName, isKid = true),
            onDismissRequest = onDone,
        )
        return
    }
    // The account PINs (contract 11.2) and the TV's protection, read fresh before deciding what it takes
    var decided by remember(user) { mutableStateOf<Pair<JellyfinUser, PictureLock>?>(null) }
    LaunchedEffect(user) {
        val row = refresh(user)
        val protected = gate.isDeviceProtected.first()
        val parents =
            if (protected && !row.hasPin) {
                KidProfilesViewModel.possibleParents(row, savedUsers, gate).map { refresh(it) }
            } else {
                emptyList()
            }
        decided = row to KidProfilesViewModel.pictureLock(row, kind, protected, parents)
    }
    val (row, lock) = decided ?: return
    var unlocked by remember(row, lock) { mutableStateOf(lock == PictureLock.Open) }
    var parent by remember(row, lock) { mutableStateOf((lock as? PictureLock.ParentPin)?.parents?.singleOrNull()) }
    when {
        lock == PictureLock.Hidden -> {
            LaunchedEffect(Unit) { onDone() }
        }

        unlocked -> {
            ProfilePictureDialog(
                target = ProfilePictureTarget(server.url, row.id, token, row.shownName, isKid = kind != ProfileKind.ADULT),
                onDismissRequest = onDone,
            )
        }

        lock == PictureLock.OwnPin -> {
            PicturePin(owner = row, onDismissRequest = onDone, check = { gate.checkOwnPin(row, it) }, onCorrect = { unlocked = true })
        }

        lock is PictureLock.ParentPin && parent == null -> {
            KidPanelDialog(onDismissRequest = onDone, width = 620.dp) {
                val name = row.shownName.orEmpty()
                KidTitle(stringResource(R.string.change_picture))
                KidGap(10.dp)
                KidSubtitle(stringResource(R.string.kid_ask_parent, name))
                KidGap(24.dp)
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    lock.parents.forEachIndexed { index, candidate ->
                        KidListRow(
                            text = stringResource(R.string.kid_parent_pin_title, candidate.shownName.orEmpty()),
                            glyph = stringResource(R.string.fa_user),
                            onClick = { parent = candidate },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                }
                KidGap(24.dp)
                OkBackHints()
            }
        }

        else -> {
            val owner = parent ?: return
            // The parent's PIN through the gate, adults only, with its backoff; the unlock it would grant is never
            // taken, so nothing outlives this dialog
            PicturePin(
                owner = owner,
                onDismissRequest = onDone,
                check = { gate.verifyParentPin(owner, it, null) {} },
                onCorrect = { unlocked = true },
            )
        }
    }
}

/** [owner]'s PIN before a picture changes; the entry stays latched while [check] runs */
@Composable
private fun PicturePin(
    owner: JellyfinUser,
    onDismissRequest: () -> Unit,
    check: suspend (String) -> PinCheckResult,
    onCorrect: () -> Unit,
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
                when (result) {
                    PinCheckResult.Correct -> onCorrect()
                    else -> error = result
                }
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
