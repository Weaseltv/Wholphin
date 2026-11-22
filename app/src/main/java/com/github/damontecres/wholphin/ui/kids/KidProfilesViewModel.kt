package com.github.damontecres.wholphin.ui.kids

import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.AccountPinWrite
import com.github.damontecres.wholphin.kids.AccountPins
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRemoval
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.profilePicturesEnabled
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
