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
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.AccountPinWrite
import com.github.damontecres.wholphin.kids.AccountPins
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRemoval
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

        /** Chooser "Remove PIN" (owner 2026-10-25): an adult with a PIN and its own token */
        fun canRemovePin(user: JellyfinUser): Boolean = canChangePin(user) && gate.profileKind(user) == ProfileKind.ADULT

        suspend fun kidsOnTv(): Boolean = accountPins.kidsOnTv()

        /** Removes [user]'s PIN, the account first, only while no kid profile is saved on this TV */
        suspend fun removePin(user: JellyfinUser): PinRemoval = accountPins.removeWithoutKids(user)

        /** [user] with its account's PIN pulled into the cache (best effort), before a PIN prompt shows (contract 11.2) */
        suspend fun refreshed(user: JellyfinUser): JellyfinUser = accountPins.refresh(user)

        companion object {
            /** The saved adults other than [target] who could unlock its picture with their PIN */
            fun possibleParents(
                target: JellyfinUser,
                savedUsers: List<JellyfinUser>,
                gate: KidSessionGate,
            ): List<JellyfinUser> = savedUsers.filter { it.rowId != target.rowId && gate.profileKind(it) == ProfileKind.ADULT }

            /** Whether a picture change opened by [entered] may go on under [lock] (review round 2) */
            fun lockSatisfied(
                lock: PictureLock,
                entered: PinEntered,
            ): Boolean =
                when (lock) {
                    PictureLock.Open -> true
                    PictureLock.OwnPin -> entered == PinEntered.OWN
                    is PictureLock.ParentPin -> entered == PinEntered.PARENT
                    PictureLock.Hidden -> false
                }

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

/** Which PIN opened a chooser picture change */
enum class PinEntered { NONE, OWN, PARENT }

/** What the chooser's Change picture dialog shows (see [ChooserPictureAccess]) */
sealed interface PictureAccessState {
    data object Deciding : PictureAccessState

    /** [lock] ([PictureLock.OwnPin] or [PictureLock.ParentPin]) wants a PIN; [parent] is whose, once picked */
    data class Locked(
        val row: JellyfinUser,
        val lock: PictureLock,
        val parent: JellyfinUser? = null,
    ) : PictureAccessState

    /** The picker is open, authorized by [entered] */
    data class Unlocked(
        val row: JellyfinUser,
        val entered: PinEntered,
    ) : PictureAccessState

    /** Nobody may change it now: the dialog closes with no change */
    data object Closed : PictureAccessState
}

/**
 * The authorization behind the chooser's "Change picture" for [target] (contract 11.1, review rounds 1 and 2). It follows
 * the TV's kid protection for as long as the dialog is open ([follow]): when the lock changes and what was entered no
 * longer satisfies it (eg protection turns on while a pinless adult's picker is open), the authorization is dropped and
 * the dialog asks for a parent's PIN, or closes when nobody can unlock it. Every save or remove checks again first
 * ([mayChange]). The PIN checks go through the gate and grant no unlock.
 */
class ChooserPictureAccess(
    private val target: JellyfinUser,
    private val kind: ProfileKind,
    private val gate: KidSessionGate,
    private val savedUsers: List<JellyfinUser>,
    private val refresh: suspend (JellyfinUser) -> JellyfinUser,
) {
    private val _state = MutableStateFlow<PictureAccessState>(PictureAccessState.Deciding)
    val state: StateFlow<PictureAccessState> = _state

    /** Decides, then decides again every time the TV's protection changes, until cancelled (the dialog closed) */
    suspend fun follow() {
        gate.isDeviceProtected.distinctUntilChanged().collect { decide(it) }
    }

    /** Decides with the TV's protection right now */
    suspend fun decideNow() = decide(gate.isDeviceProtected.first())

    private suspend fun decide(deviceProtected: Boolean) {
        val (row, lock) = lockNow(deviceProtected)
        _state.update { current ->
            val entered = (current as? PictureAccessState.Unlocked)?.entered ?: PinEntered.NONE
            when {
                current == PictureAccessState.Closed -> {
                    current
                }

                KidProfilesViewModel.lockSatisfied(lock, entered) -> {
                    PictureAccessState.Unlocked(row, entered)
                }

                lock == PictureLock.Hidden -> {
                    PictureAccessState.Closed
                }

                else -> {
                    // A parent already picked keeps the entry, if they can still unlock it
                    val picked = (current as? PictureAccessState.Locked)?.parent
                    val parents = (lock as? PictureLock.ParentPin)?.parents.orEmpty()
                    val parent = parents.firstOrNull { it.rowId == picked?.rowId } ?: parents.singleOrNull()
                    PictureAccessState.Locked(row, lock, parent)
                }
            }
        }
    }

    /** [target]'s fresh row and what changing its picture takes now (account PINs pulled, contract 11.2) */
    private suspend fun lockNow(deviceProtected: Boolean): Pair<JellyfinUser, PictureLock> {
        if (kind == ProfileKind.KID) return target to PictureLock.Open
        val row = refresh(target)
        val parents =
            if (deviceProtected && !row.hasPin) {
                KidProfilesViewModel.possibleParents(row, savedUsers, gate).map { refresh(it) }
            } else {
                emptyList()
            }
        return row to KidProfilesViewModel.pictureLock(row, kind, deviceProtected, parents)
    }

    fun pickParent(parent: JellyfinUser) {
        _state.update { (it as? PictureAccessState.Locked)?.copy(parent = parent) ?: it }
    }

    /** Checks the PIN the locked dialog asks for; a correct one opens the picker, while it still asks for that PIN */
    suspend fun submitPin(pin: String): PinCheckResult {
        val locked = _state.value as? PictureAccessState.Locked ?: return PinCheckResult.Wrong(0)
        val (result, entered) =
            when (locked.lock) {
                PictureLock.OwnPin -> {
                    gate.checkOwnPin(locked.row, pin) to PinEntered.OWN
                }

                is PictureLock.ParentPin -> {
                    // Adults only, with the gate's backoff; the unlock it would grant is never taken
                    val parent = locked.parent ?: return PinCheckResult.Wrong(0)
                    gate.verifyParentPin(parent, pin, null) {} to PinEntered.PARENT
                }

                else -> {
                    return PinCheckResult.Wrong(0)
                }
            }
        if (result == PinCheckResult.Correct) {
            _state.update { if (it == locked) PictureAccessState.Unlocked(locked.row, entered) else it }
        }
        return result
    }

    /**
     * Right before a save or remove: whether what was entered still satisfies what changing the picture takes now. If
     * not, nothing is saved and the dialog closes.
     */
    suspend fun mayChange(): Boolean {
        val unlocked = _state.value as? PictureAccessState.Unlocked ?: return false
        val (_, lock) = lockNow(gate.isDeviceProtected.first())
        val allowed = KidProfilesViewModel.lockSatisfied(lock, unlocked.entered)
        if (!allowed) _state.value = PictureAccessState.Closed
        return allowed
    }
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
