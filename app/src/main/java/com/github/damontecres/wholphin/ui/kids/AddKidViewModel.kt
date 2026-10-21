package com.github.damontecres.wholphin.ui.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidApiResult
import com.github.damontecres.wholphin.kids.KidLevel
import com.github.damontecres.wholphin.kids.KidNames
import com.github.damontecres.wholphin.kids.KidProblemCodes
import com.github.damontecres.wholphin.kids.KidProfile
import com.github.damontecres.wholphin.kids.KidProfilesApi
import com.github.damontecres.wholphin.kids.KidQuickConnect
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.UnlockPurpose
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.ProfilePictureResult
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * The chooser's "Add kid profile" flow (kid-profiles plan 4.3, contract 5.4): unlock a parent,
 * list the account's kids, create one or pick one, then sign it in on this TV with Quick
 * Connect through [KidSessionGate.signInKid]. A created kid is never created again: retries and
 * expired codes reconnect the same kid.
 */
@HiltViewModel
class AddKidViewModel
    @Inject
    constructor(
        private val api: KidProfilesApi,
        private val gate: KidSessionGate,
        private val quickConnect: KidQuickConnect,
        private val pictures: ProfilePictureService,
        private val serverRepository: ServerRepository,
        private val setupNavigationManager: SetupNavigationManager,
    ) : ViewModel() {
        private val _step = MutableStateFlow<AddKidStep>(AddKidStep.Loading)
        val step: StateFlow<AddKidStep> = _step

        private lateinit var server: JellyfinServer
        private var parent: JellyfinUser? = null
        private var job: Job? = null

        // One Idempotency-Key per form submission (name + level), reused on every retry of it.
        private var pendingKey: String? = null
        private var pendingBody: Pair<String, KidLevel>? = null
        private var form = AddKidStep.Form()
        private var avatar: Avatar? = null
        private var kids: List<KidProfile> = emptyList()

        val avatars: List<Avatar> get() = pictures.avatars()

        fun start(
            server: JellyfinServer,
            users: List<JellyfinUser>,
        ) {
            this.server = server
            job?.cancel()
            parent = null
            avatar = null
            form = AddKidStep.Form()
            val parents = parentsOf(users, gate)
            when (parents.size) {
                0 -> _step.value = AddKidStep.Failed(AddKidError.NoParent, Retry.None)
                1 -> selectParent(parents.single())
                else -> _step.value = AddKidStep.PickParent(parents)
            }
        }

        fun selectParent(user: JellyfinUser) =
            launch {
                parent = user
                _step.value =
                    when {
                        user.hasPin -> AddKidStep.EnterPin(user)

                        // Trust on first use is allowed only before the first kid (plan 4.2).
                        !gate.isDeviceProtected.first() -> AddKidStep.CreateFirstPin(user)

                        else -> AddKidStep.ParentLocked(user)
                    }
            }

        fun submitPin(pin: String) =
            launch {
                val user = parent ?: return@launch
                when (val result = gate.verifyParentPin(user, pin, UnlockPurpose.AddKid)) {
                    PinCheckResult.Correct -> loadKids()
                    is PinCheckResult.Wrong -> _step.value = AddKidStep.EnterPin(user, PinError.Wrong(result.attemptsBeforeLockout))
                    is PinCheckResult.LockedOut -> _step.value = AddKidStep.EnterPin(user, PinError.LockedOut(result.retryAtMillis))
                }
            }

        /** First PIN for a pinless parent on a device that has no kids yet, then unlock with it. */
        fun createFirstPin(pin: String) =
            launch {
                val user = parent ?: return@launch
                if (gate.isDeviceProtected.first()) {
                    _step.value = AddKidStep.ParentLocked(user)
                    return@launch
                }
                serverRepository.updateUserAuth(user, pin, false)
                val withPin = user.copy(pin = pin, requireLogin = false)
                parent = withPin
                when (gate.verifyParentPin(withPin, pin, UnlockPurpose.AddKid)) {
                    PinCheckResult.Correct -> loadKids()
                    else -> _step.value = AddKidStep.EnterPin(withPin)
                }
            }

        fun createNew() {
            _step.value = form
        }

        fun updateForm(
            name: String = form.name,
            level: KidLevel? = form.level,
        ) {
            form = form.copy(name = name, level = level, nameError = null, error = null)
            _step.value = form
        }

        fun submitForm() {
            val name = KidNames.validOrNull(form.name)
            val level = form.level
            when {
                name == null -> showForm(nameError = NameError.Invalid)
                level == null -> showForm(error = AddKidError.PickLevel)
                avatars.isEmpty() -> create(name, level)
                else -> _step.value = AddKidStep.PickPicture(name, level, avatar?.id)
            }
        }

        /** Picture step: [picked] is saved after the kid signs in; null skips it. */
        fun pickPicture(picked: Avatar?) {
            avatar = picked
            val name = KidNames.validOrNull(form.name) ?: return showForm(nameError = NameError.Invalid)
            val level = form.level ?: return showForm(error = AddKidError.PickLevel)
            create(name, level)
        }

        fun signInExisting(kid: KidProfile) {
            avatar = null
            connect(kid)
        }

        fun retry() {
            when (val step = _step.value) {
                is AddKidStep.Failed -> {
                    when (val retry = step.retry) {
                        Retry.LoadKids -> {
                            launch { loadKids() }
                        }

                        is Retry.Create -> {
                            create(retry.name, retry.level)
                        }

                        is Retry.Connect -> {
                            connect(retry.kid)
                        }

                        Retry.None -> {}
                    }
                }

                else -> {}
            }
        }

        fun backToForm() = showForm()

        /** Leaving the flow drops the parent's unlock (plan 4.1). */
        fun cancel() {
            job?.cancel()
            gate.clearParentUnlock()
        }

        override fun onCleared() {
            gate.clearParentUnlock()
        }

        private fun showForm(
            nameError: NameError? = null,
            error: AddKidError? = null,
        ) {
            form = form.copy(nameError = nameError, error = error)
            _step.value = form
        }

        /**
         * The parent's token, but only while their add-kid unlock is still valid (plan 4.1: 5 minutes). Otherwise
         * the flow goes back to the parent's PIN and null is returned.
         */
        private fun authorizedToken(): String? {
            val user = parent ?: return null
            if (gate.validUnlockFor(user.rowId, UnlockPurpose.AddKid) == null) {
                Timber.i("Parent unlock expired or not for this flow; asking for the PIN again")
                _step.value = AddKidStep.EnterPin(user)
                return null
            }
            return user.accessToken ?: run {
                fail(AddKidError.SignInAgain, Retry.None)
                null
            }
        }

        private suspend fun loadKids() {
            val token = authorizedToken() ?: return
            _step.value = AddKidStep.Loading
            when (val result = api.list(token)) {
                is KidApiResult.Ok -> {
                    kids = result.value.kids
                    _step.value =
                        if (kids.isEmpty()) form else AddKidStep.ChooseKid(kids, result.value.limit)
                }

                is KidApiResult.Problem -> {
                    fail(errorFor(result), Retry.LoadKids)
                }

                is KidApiResult.NetworkError -> {
                    fail(AddKidError.Network, Retry.LoadKids)
                }
            }
        }

        private fun create(
            name: String,
            level: KidLevel,
        ) = launch {
            if (authorizedToken() == null) return@launch
            if (pendingBody != name to level) {
                pendingKey = UUID.randomUUID().toString()
                pendingBody = name to level
            }
            val key = pendingKey!!
            _step.value = AddKidStep.Working(Work.Creating, name)
            var inProgressWaits = 0
            while (true) {
                val token = authorizedToken() ?: return@launch
                when (val result = api.create(token, key, name, level)) {
                    is KidApiResult.Ok -> {
                        pendingKey = null
                        pendingBody = null
                        return@launch connectNow(result.value)
                    }

                    is KidApiResult.NetworkError -> {
                        return@launch fail(AddKidError.Network, Retry.Create(name, level))
                    }

                    is KidApiResult.Problem -> {
                        when (result.code) {
                            KidProblemCodes.REQUEST_IN_PROGRESS -> {
                                if (inProgressWaits++ < MAX_IN_PROGRESS_WAITS) {
                                    delay(IN_PROGRESS_WAIT_MS)
                                    continue
                                }
                                return@launch fail(AddKidError.Generic, Retry.Create(name, level))
                            }

                            KidProblemCodes.KID_NAME_TAKEN -> {
                                return@launch showForm(nameError = NameError.Taken)
                            }

                            KidProblemCodes.INVALID_REQUEST -> {
                                return@launch showForm(nameError = NameError.Invalid)
                            }

                            KidProblemCodes.IDEMPOTENCY_CONFLICT -> {
                                // Bug guard: the next attempt gets a fresh key.
                                pendingKey = null
                                pendingBody = null
                                return@launch fail(AddKidError.Generic, Retry.Create(name, level))
                            }

                            else -> {
                                return@launch fail(errorFor(result), Retry.Create(name, level))
                            }
                        }
                    }
                }
            }
        }

        private fun connect(kid: KidProfile) = launch { connectNow(kid) }

        private suspend fun connectNow(kid: KidProfile) {
            if (authorizedToken() == null) return
            _step.value = AddKidStep.Working(Work.Connecting, kid.displayName)
            var freshCodes = 0
            while (true) {
                if (authorizedToken() == null) return
                val code =
                    try {
                        quickConnect.initiate(server.url)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Quick Connect initiate failed")
                        return fail(AddKidError.Network, Retry.Connect(kid))
                    }
                val token = authorizedToken() ?: return
                val connected =
                    when (val result = api.connect(token, kid.id, code.code)) {
                        is KidApiResult.Ok -> {
                            result.value
                        }

                        is KidApiResult.NetworkError -> {
                            return fail(AddKidError.Network, Retry.Connect(kid))
                        }

                        is KidApiResult.Problem -> {
                            when (result.code) {
                                KidProblemCodes.QUICK_CONNECT_INVALID_CODE -> {
                                    // Expired or used code: a new code for the same kid, once.
                                    if (freshCodes++ < 1) continue
                                    return fail(AddKidError.Generic, Retry.Connect(kid))
                                }

                                KidProblemCodes.KID_NOT_FOUND -> {
                                    return loadKids()
                                }

                                else -> {
                                    return fail(errorFor(result), Retry.Connect(kid))
                                }
                            }
                        }
                    }
                if (!awaitAuthenticated(code.secret)) {
                    if (freshCodes++ < 1) continue
                    return fail(AddKidError.Generic, Retry.Connect(kid))
                }
                val auth =
                    try {
                        quickConnect.authenticate(server.url, code.secret)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Quick Connect sign-in failed")
                        return fail(AddKidError.Generic, Retry.Connect(kid))
                    }
                val displayName = connected.displayName.ifBlank { kid.displayName }
                // Signing the kid in is the last use of the parent's authority: still valid?
                if (authorizedToken() == null) return
                val current =
                    try {
                        gate.signInKid(server.url, auth, displayName)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Kid sign-in failed")
                        return fail(AddKidError.Generic, Retry.Connect(kid))
                    }
                savePicture(current.user.id, auth.accessToken, displayName)
                gate.clearParentUnlock()
                _step.value = AddKidStep.Done
                setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                return
            }
        }

        private suspend fun awaitAuthenticated(secret: String): Boolean {
            repeat(MAX_POLLS) {
                val authenticated =
                    try {
                        quickConnect.isAuthenticated(server.url, secret)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Quick Connect poll failed")
                        return false
                    }
                if (authenticated) return true
                delay(POLL_MS)
            }
            return false
        }

        /** The kid's own session saves the picture; a failure never blocks signing in. */
        private suspend fun savePicture(
            userId: UUID,
            token: String?,
            name: String,
        ) {
            val picked = avatar ?: return
            if (token == null) return
            val result = pictures.save(ProfilePictureTarget(server.url, userId, token, name), picked)
            if (result is ProfilePictureResult.Failed) Timber.w("Kid picture not saved; it can be set in Settings")
        }

        private fun fail(
            error: AddKidError,
            retry: Retry,
        ) {
            _step.value = AddKidStep.Failed(error, retry)
        }

        private fun launch(block: suspend () -> Unit) {
            job?.cancel()
            job = viewModelScope.launch { block() }
        }

        companion object {
            const val POLL_MS = 1_000L
            const val MAX_POLLS = 30
            const val IN_PROGRESS_WAIT_MS = 2_000L
            const val MAX_IN_PROGRESS_WAITS = 5

            /** Saved adults who could unlock the flow (signed in, server says adult). */
            fun parentsOf(
                users: List<JellyfinUser>,
                gate: KidSessionGate,
            ): List<JellyfinUser> = users.filter { it.accessToken != null && gate.profileKind(it) == ProfileKind.ADULT }

            fun errorFor(problem: KidApiResult.Problem): AddKidError =
                when (problem.code) {
                    KidProblemCodes.KIDS_UNAVAILABLE -> AddKidError.NotAvailable
                    KidProblemCodes.KID_LIMIT_REACHED -> AddKidError.LimitReached
                    KidProblemCodes.ACCESS_REQUIRED -> AddKidError.AccessInactive
                    KidProblemCodes.AUTHENTICATION_REQUIRED -> AddKidError.SignInAgain
                    KidProblemCodes.KID_NOT_ACTIVE -> AddKidError.KidNotActive
                    KidProblemCodes.KID_NAME_TAKEN -> AddKidError.NameTaken
                    KidProblemCodes.RATE_LIMITED -> AddKidError.RateLimited(problem.retryAfterMillis?.let { minutesFrom(it) })
                    else -> AddKidError.Generic
                }

            private fun minutesFrom(millis: Long): Int = ((millis + 59_999) / 60_000).toInt().coerceAtLeast(1)
        }
    }

sealed interface AddKidStep {
    data object Loading : AddKidStep

    data class PickParent(
        val parents: List<JellyfinUser>,
    ) : AddKidStep

    data class EnterPin(
        val parent: JellyfinUser,
        val error: PinError? = null,
    ) : AddKidStep

    data class CreateFirstPin(
        val parent: JellyfinUser,
    ) : AddKidStep

    /** A pinless adult on a protected TV (plan 4.2): unlocking them is the chooser's job. */
    data class ParentLocked(
        val parent: JellyfinUser,
    ) : AddKidStep

    data class ChooseKid(
        val kids: List<KidProfile>,
        val limit: Int,
    ) : AddKidStep

    data class Form(
        val name: String = "",
        val level: KidLevel? = null,
        val nameError: NameError? = null,
        val error: AddKidError? = null,
    ) : AddKidStep

    data class PickPicture(
        val name: String,
        val level: KidLevel,
        val selected: String?,
    ) : AddKidStep

    data class Working(
        val work: Work,
        val kidName: String,
    ) : AddKidStep

    data class Failed(
        val error: AddKidError,
        val retry: Retry,
    ) : AddKidStep

    data object Done : AddKidStep
}

enum class Work { Creating, Connecting }

enum class NameError { Invalid, Taken }

sealed interface PinError {
    data class Wrong(
        val attemptsBeforeLockout: Int,
    ) : PinError

    data class LockedOut(
        val retryAtMillis: Long,
    ) : PinError
}

sealed interface AddKidError {
    data object NoParent : AddKidError

    data object NotAvailable : AddKidError

    data object LimitReached : AddKidError

    data object NameTaken : AddKidError

    data object PickLevel : AddKidError

    data object AccessInactive : AddKidError

    data object SignInAgain : AddKidError

    data object KidNotActive : AddKidError

    data class RateLimited(
        val minutes: Int?,
    ) : AddKidError

    data object Network : AddKidError

    data object Generic : AddKidError
}

sealed interface Retry {
    data object None : Retry

    data object LoadKids : Retry

    data class Create(
        val name: String,
        val level: KidLevel,
    ) : Retry

    data class Connect(
        val kid: KidProfile,
    ) : Retry
}
