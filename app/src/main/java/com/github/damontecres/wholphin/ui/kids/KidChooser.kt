package com.github.damontecres.wholphin.ui.kids

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.profilePicturesEnabled
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureDialog
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.pinPromptFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Kid state for the chooser and Settings, read only through [KidSessionGate]. */
@HiltViewModel
class KidProfilesViewModel
    @Inject
    constructor(
        val gate: KidSessionGate,
        pictures: ProfilePictureService,
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

        /** Chooser "Change picture": only the profile that is signed in, with its own token. */
        fun canChangePicture(
            user: JellyfinUser,
            isCurrentUser: Boolean,
        ): Boolean = profilePicturesEnabled && isCurrentUser && user.accessToken != null
    }

/**
 * "Change picture" from the chooser's profile menu. A PIN profile proves it is its owner first; that check grants
 * no unlock (review round 5), so nothing outlives a dismissed dialog.
 */
@Composable
fun ChooserChangePicture(
    server: JellyfinServer,
    user: JellyfinUser,
    gate: KidSessionGate,
    onDone: () -> Unit,
) {
    val token = user.accessToken ?: return onDone()
    var unlocked by remember(user) { mutableStateOf(!user.hasPin) }
    if (unlocked) {
        ProfilePictureDialog(
            target = ProfilePictureTarget(server.url, user.id, token, user.name, isKid = gate.profileKind(user) != ProfileKind.ADULT),
            onDismissRequest = onDone,
        )
        return
    }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<PinCheckResult?>(null) }
    var checks by remember { mutableIntStateOf(0) }
    PinEntryDialog(
        onDismissRequest = onDone,
        title = stringResource(R.string.change_picture),
        prompt = pinPromptFor(user.shownName),
        length = PinRules.entryLength(user.pin),
        checks = checks,
        lockedUntilMillis = (error as? PinCheckResult.LockedOut)?.retryAtMillis ?: 0L,
        message =
            when (error) {
                is PinCheckResult.Wrong -> stringResource(R.string.pin_wrong)
                is PinCheckResult.LockedOut -> stringResource(R.string.kids_error_rate_limited_unknown)
                else -> null
            },
        onSubmit = { pin ->
            scope.launch {
                when (val result = gate.checkOwnPin(user, pin)) {
                    PinCheckResult.Correct -> {
                        unlocked = true
                    }

                    else -> {
                        error = result
                        checks++
                    }
                }
            }
        },
    )
}
