package com.github.damontecres.wholphin.ui.kids

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
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

        /** Chooser "Change picture" (contract 11.1): every saved profile with its own token. */
        fun canChangePicture(user: JellyfinUser): Boolean = profilePicturesEnabled && user.accessToken != null

        /** Chooser "Change PIN" (contract 11.2): a saved profile with a PIN and its own token to write the account with. */
        fun canChangePin(user: JellyfinUser): Boolean = BuildConfig.KID_PROFILES_ENABLED && user.hasPin && user.accessToken != null

        /** [user] with its account's PIN pulled into the cache (best effort), before a PIN prompt shows (contract 11.2) */
        suspend fun refreshed(user: JellyfinUser): JellyfinUser = accountPins.refresh(user)

        /** Saves [user]'s new PIN, the account first; returns whether it was saved (nothing changed if not) */
        suspend fun changePin(
            user: JellyfinUser,
            pin: String,
        ): Boolean = accountPins.set(user, pin, user.requireLogin) == AccountPinWrite.Saved
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
 * pick from the adult list, and an adult with a PIN proves they are its owner first. That check grants no unlock
 * (review round 5), so nothing outlives a dismissed dialog.
 */
@Composable
fun ChooserChangePicture(
    server: JellyfinServer,
    user: JellyfinUser,
    gate: KidSessionGate,
    onDone: () -> Unit,
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
    WithAccountPin(user, refresh) { row ->
        var unlocked by remember(row) { mutableStateOf(!row.hasPin) }
        if (unlocked) {
            ProfilePictureDialog(
                target = ProfilePictureTarget(server.url, row.id, token, row.shownName, isKid = kind != ProfileKind.ADULT),
                onDismissRequest = onDone,
            )
            return@WithAccountPin
        }
        var error by remember { mutableStateOf<PinCheckResult?>(null) }
        PinEntryDialog(
            onDismissRequest = onDone,
            title = stringResource(R.string.change_picture),
            prompt = pinPromptFor(row.shownName),
            length = PinRules.entryLength(row.pin),
            lockedUntilMillis = (error as? PinCheckResult.LockedOut)?.retryAtMillis ?: 0L,
            message =
                when (error) {
                    is PinCheckResult.Wrong -> stringResource(R.string.pin_wrong)
                    is PinCheckResult.LockedOut -> stringResource(R.string.kids_error_rate_limited_unknown)
                    else -> null
                },
            // Suspends for the check, so the entry stays latched until it is done
            onSubmit = { pin ->
                val result = gate.checkOwnPin(row, pin)
                when (result) {
                    PinCheckResult.Correct -> unlocked = true
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
