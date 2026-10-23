package com.github.damontecres.wholphin.ui.kids

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.AccountPinWrite
import com.github.damontecres.wholphin.kids.AccountPins
import com.github.damontecres.wholphin.kids.GateDecision
import com.github.damontecres.wholphin.kids.KidApiResult
import com.github.damontecres.wholphin.kids.KidLevel
import com.github.damontecres.wholphin.kids.KidNames
import com.github.damontecres.wholphin.kids.KidProblemCodes
import com.github.damontecres.wholphin.kids.KidProfile
import com.github.damontecres.wholphin.kids.KidProfilesApi
import com.github.damontecres.wholphin.kids.KidQuickConnect
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinCreate
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileGateException
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.QuickConnectPoll
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.api.AuthenticationResult
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * The chooser's "Add kid profile" flow (kid-profiles plan 4.3, contract 5.4, 12.3, 12.4): unlock a parent, make sure
 * every other saved adult has a PIN, then create a new kid or add an existing one (one of the account's own, or another
 * account's kid paired with a code), and sign it in on this TV through [KidSessionGate.signInKid]. A created kid is
 * never created again: retries and expired codes reconnect the same kid.
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
        private val accountPins: AccountPins,
    ) : ViewModel() {
        private val _step = MutableStateFlow<AddKidStep>(AddKidStep.Loading)
        val step: StateFlow<AddKidStep> = _step

        private lateinit var server: JellyfinServer
        private var parent: JellyfinUser? = null
        private var job: Job? = null

        // The PIN check in progress (review round 3): its unlock is admitted only while it is still this one, under
        // [attemptLock], so a cancel (Back) or a newer check in between leaves no unlock behind
        private val attemptLock = Any()
        private var currentAttempt: Any? = null

        // One Idempotency-Key per form submission (name + level), reused on every retry of it.
        private var pendingKey: String? = null
        private var pendingBody: Pair<String, KidLevel>? = null
        private var form = AddKidStep.Form()
        private var avatar: Avatar? = null

        /** The TV's saved profiles: the other adults (contract 12.4) and the kids already here (12.3) */
        private var users: List<JellyfinUser> = emptyList()

        /**
         * Contract 12.3: a paired kid's sign-in, kept while the parent's PIN is asked again. The flow owns it until it is
         * handed to the gate: if the flow is left first, it is signed out (review round 1).
         */
        private var pendingPair: AuthenticationResult? = null

        /**
         * Whether the flow still owns [pendingPair]'s token (review round 2): not once a failed sign-in may have saved or
         * published it, which makes it this TV's, never to be signed out
         */
        private var pendingPairOwned = false

        val avatars: List<Avatar> get() = pictures.avatars(isKid = true)

        fun start(
            server: JellyfinServer,
            users: List<JellyfinUser>,
        ) {
            this.server = server
            job?.cancel()
            parent = null
            avatar = null
            abandonPendingPair()
            form = AddKidStep.Form()
            this.users = users
            val parents = parentsOf(users, gate)
            when (parents.size) {
                0 -> _step.value = AddKidStep.Failed(AddKidError.NoParent, Retry.None)
                1 -> selectParent(parents.single())
                else -> _step.value = AddKidStep.PickParent(parents)
            }
        }

        fun selectParent(picked: JellyfinUser) =
            launch {
                // Contract 11.2: the account's PIN before the prompt shows (best effort; the cached one otherwise)
                _step.value = AddKidStep.Loading
                val user = accountPins.refresh(picked)
                parent = user
                _step.value =
                    when {
                        user.hasPin -> AddKidStep.EnterPin(user)

                        // Trust on first use is allowed only before the first kid (plan 4.2).
                        !gate.isDeviceProtected.first() -> AddKidStep.CreateFirstPin(user)

                        else -> AddKidStep.ParentLocked(user)
                    }
            }

        /** Checks the parent's PIN; the returned job ends when the check (and the step after it) is done. */
        fun submitPin(pin: String): Job =
            launch {
                val user = parent ?: return@launch
                val result =
                    try {
                        verifyAsCurrentAttempt(user, pin)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        // Eg a storage failure: say so and let them try again
                        Timber.w(ex, "Parent PIN check failed")
                        _step.value = AddKidStep.EnterPin(user, PinError.CheckFailed)
                        return@launch
                    }
                when (result) {
                    PinCheckResult.Correct -> afterUnlock()
                    is PinCheckResult.Wrong -> _step.value = AddKidStep.EnterPin(user, PinError.Wrong(result.attemptsBeforeLockout))
                    is PinCheckResult.LockedOut -> _step.value = AddKidStep.EnterPin(user, PinError.LockedOut(result.retryAtMillis))
                }
            }

        /** Whether the last PIN check was wrong (or locked out), as the step after it says */
        fun lastPinWasWrong(): Boolean =
            (_step.value as? AddKidStep.EnterPin)?.error.let { it is PinError.Wrong || it is PinError.LockedOut }

        /** First PIN for a pinless parent on a device that has no kids yet, then unlock with it. */
        fun createFirstPin(pin: String) =
            launch {
                val user = parent ?: return@launch
                // A new PIN is exactly 4 presses; the PIN screen never sends anything else
                if (!PinRules.isValidNewPin(pin)) return@launch
                if (gate.isDeviceProtected.first()) {
                    _step.value = AddKidStep.ParentLocked(user)
                    return@launch
                }
                // The account first (contract 11.2): if that fails nothing changes, and the panel says so
                if (accountPins.set(user, pin, false) != AccountPinWrite.Saved) {
                    _step.value = AddKidStep.CreateFirstPin(user, saveFailed = true)
                    return@launch
                }
                val withPin = user.copy(pin = pin, requireLogin = false)
                parent = withPin
                when (verifyAsCurrentAttempt(withPin, pin)) {
                    PinCheckResult.Correct -> afterUnlock()
                    else -> _step.value = AddKidStep.EnterPin(withPin)
                }
            }

        /** "Create a new kid profile" (contract 5.4 create) */
        fun createNew() {
            _step.value = form
        }

        /** "Add an existing kid profile" (contract 12.3); also "Get a new code" after a refused one */
        fun addExisting() = launch { pair() }

        /**
         * Contract 12.4 "Create PIN" for [adult], who is listed, saved to that adult's account with their own token first
         * (11.2). Review round 1: create-only, so a PIN the account already has is adopted, never overwritten; and the save
         * is the flow's own work, cancelled when the flow is left (or the PIN screen dismissed), with the parent's add-kid
         * unlock checked right before the account is written and again before the TV's cache is.
         */
        suspend fun createAdultPin(
            adult: JellyfinUser,
            pin: String,
        ): AdultPinOutcome {
            var outcome = AdultPinOutcome.NOT_SAVED
            val save =
                launch {
                    outcome =
                        try {
                            saveAdultPin(adult, pin)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            // Review round 2: eg a storage failure; the PIN screen says it wasn't saved
                            Timber.w(ex, "Adult PIN save failed")
                            AdultPinOutcome.NOT_SAVED
                        }
                }
            try {
                save.join()
            } catch (ex: CancellationException) {
                save.cancel()
                throw ex
            }
            return outcome
        }

        private suspend fun saveAdultPin(
            adult: JellyfinUser,
            pin: String,
        ): AdultPinOutcome {
            val listed = _step.value as? AddKidStep.AdultPins ?: return AdultPinOutcome.NOT_SAVED
            val user = parent ?: return AdultPinOutcome.NOT_SAVED
            if (!PinRules.isValidNewPin(pin) || listed.adults.none { it.rowId == adult.rowId }) return AdultPinOutcome.NOT_SAVED
            if (!unlocked()) return AdultPinOutcome.NOT_SAVED
            val save = currentCoroutineContext().job
            val result =
                accountPins.createIfNoPin(adult, pin) {
                    save.isActive && gate.validUnlockFor(user.rowId, UnlockPurpose.AddKid) != null
                }
            val others = listed.adults.filter { it.rowId != adult.rowId }
            return when (result) {
                PinCreate.CREATED -> {
                    users = users.map { if (it.rowId == adult.rowId) it.copy(pin = pin) else it }
                    showAdultPins(others)
                    AdultPinOutcome.SAVED
                }

                PinCreate.ALREADY_SET -> {
                    showAdultPins(others)
                    AdultPinOutcome.ALREADY_HAD_PIN
                }

                PinCreate.NOT_ELIGIBLE -> {
                    // Gone from this TV, signed out or no longer an adult: nothing to ask of them
                    users = users.filter { it.rowId != adult.rowId }
                    showAdultPins(others)
                    AdultPinOutcome.NOT_SAVED
                }

                PinCreate.REFUSED -> {
                    // The unlock ran out (asks for the PIN again) or the flow was left
                    unlocked()
                    AdultPinOutcome.NOT_SAVED
                }

                PinCreate.FAILED -> {
                    AdultPinOutcome.NOT_SAVED
                }
            }
        }

        /**
         * Contract 12.4 "Remove from this TV" for [adult], who is listed. The parent's add-kid unlock authorizes it (on the
         * chooser a pinless adult on a protected TV needs another adult's PIN too, plan 4.2), checked again in the step
         * that removes it.
         */
        fun removeAdult(adult: JellyfinUser) =
            launch {
                val listed = _step.value as? AddKidStep.AdultPins ?: return@launch
                val user = parent ?: return@launch
                if (listed.adults.none { it.rowId == adult.rowId } || !unlocked()) return@launch
                _step.value = AddKidStep.Loading
                // A PIN set meanwhile (website, another TV) counts: they stay, and the chooser would ask for their own PIN
                val fresh = accountPins.refresh(adult)
                if (fresh.hasPin) {
                    users = users.map { if (it.rowId == fresh.rowId) fresh else it }
                } else {
                    try {
                        serverRepository.removeUser(fresh) {
                            if (gate.validUnlockFor(user.rowId, UnlockPurpose.AddKid) == null) {
                                throw ProfileGateException(GateDecision.NeedsParentUnlock)
                            }
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: ProfileGateException) {
                        Timber.i("Adult removal refused: %s", ex.decision)
                        _step.value = AddKidStep.EnterPin(user)
                        return@launch
                    } catch (ex: Exception) {
                        Timber.w(ex, "Couldn't remove an adult profile")
                        _step.value = listed.copy(removeFailed = true)
                        return@launch
                    }
                    users = users.filter { it.rowId != adult.rowId }
                }
                showAdultPins(listed.adults.filter { it.rowId != adult.rowId })
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
                        Retry.Pair -> {
                            launch { pendingPair?.let { finishPairing(it) } ?: pair() }
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

        /**
         * Checks [pin] as the flow's current attempt. Verification finishes even if the flow is left meanwhile (the
         * attempt counts towards the backoff), but its unlock is granted only if this attempt is still current then.
         */
        private suspend fun verifyAsCurrentAttempt(
            user: JellyfinUser,
            pin: String,
        ): PinCheckResult {
            val attempt = Any()
            synchronized(attemptLock) { currentAttempt = attempt }
            return gate.verifyParentPin(user, pin, UnlockPurpose.AddKid) { grant ->
                synchronized(attemptLock) { if (currentAttempt === attempt) grant() }
            }
        }

        /** Leaving the flow drops the parent's unlock (plan 4.1), and a PIN still being checked grants none. */
        fun cancel() {
            synchronized(attemptLock) {
                currentAttempt = null
                job?.cancel()
                abandonPendingPair()
                gate.clearParentUnlock()
            }
        }

        override fun onCleared() {
            synchronized(attemptLock) {
                currentAttempt = null
                abandonPendingPair()
                gate.clearParentUnlock()
            }
        }

        /** Review round 1: a kid's sign-in the flow kept but won't use is signed out, not left as an orphan session */
        private fun abandonPendingPair() {
            val abandoned = pendingPair ?: return
            val owned = pendingPairOwned
            pendingPair = null
            pendingPairOwned = false
            if (owned) signOutAbandoned(abandoned)
        }

        /**
         * Review round 2: whether a sign-in the gate failed with was saved or published before it failed (then it is this
         * TV's). Fails safe: when that can't be told, it counts as in use and is never signed out.
         */
        private suspend fun inUseAfterFailure(auth: AuthenticationResult): Boolean {
            val token = auth.accessToken ?: return true
            return try {
                serverRepository.isTokenInUse(token)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't tell whether a failed sign-in was kept")
                true
            }
        }

        /** A pairing sign-in the gate failed with, kept for a retry; the flow owns it only if it wasn't kept by the TV */
        private suspend fun keepFailedPair(auth: AuthenticationResult) {
            withContext(NonCancellable) {
                val owned = !inUseAfterFailure(auth)
                pendingPair = auth
                pendingPairOwned = owned
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (ex: CancellationException) {
                abandonPendingPair()
                throw ex
            }
        }

        /** A connect sign-in the gate failed with: signed out unless the TV kept it ([inUseAfterFailure]) */
        private suspend fun releaseFailedSignIn(auth: AuthenticationResult) {
            withContext(NonCancellable) { if (!inUseAfterFailure(auth)) signOutAbandoned(auth) }
        }

        /** Signs [auth]'s token out (best effort, bounded by [KidQuickConnect.signOut]), even after the flow is gone */
        private fun signOutAbandoned(auth: AuthenticationResult) {
            val token = auth.accessToken ?: return
            val url = server.url
            Timber.i("Signing out a Quick Connect sign-in no flow will use")
            // Its own job, so leaving the flow (or clearing the view model) doesn't cancel it
            viewModelScope.launch(NonCancellable) { quickConnect.signOut(url, token) }
        }

        /**
         * Quick Connect's sign-in, owned by the flow from the moment the server issues the token (review round 1): the
         * result always comes back here (bounded by [AUTHENTICATE_TIMEOUT_MS]), and if the flow was left meanwhile its
         * token is signed out before the cancellation goes on. Throws a plain exception on failure or timeout.
         */
        private suspend fun authenticateOwned(secret: String): AuthenticationResult {
            // Review round 2: set by the implementation as soon as the result is decoded, before any cancellable return,
            // so a timeout or cancellation on the way back still finds the token
            val issued = AtomicReference<AuthenticationResult?>(null)
            try {
                val returned =
                    withContext(NonCancellable) {
                        withTimeout(AUTHENTICATE_TIMEOUT_MS) { quickConnect.authenticate(server.url, secret) { issued.set(it) } }
                    }
                issued.compareAndSet(null, returned)
                currentCoroutineContext().ensureActive()
            } catch (ex: CancellationException) {
                issued.get()?.let { signOutAbandoned(it) }
                if (ex is TimeoutCancellationException) throw IllegalStateException("Quick Connect sign-in timed out", ex)
                throw ex
            }
            return issued.get() ?: throw IllegalStateException("Quick Connect sign-in returned nothing")
        }

        private fun showForm(
            nameError: NameError? = null,
            error: AddKidError? = null,
        ) {
            form = form.copy(nameError = nameError, error = error)
            _step.value = form
        }

        /**
         * Whether the parent's add-kid unlock is still valid (plan 4.1: 5 minutes). Otherwise the flow goes back to the
         * parent's PIN and false is returned.
         */
        private fun unlocked(): Boolean {
            val user = parent ?: return false
            if (gate.validUnlockFor(user.rowId, UnlockPurpose.AddKid) != null) return true
            Timber.i("Parent unlock expired or not for this flow; asking for the PIN again")
            _step.value = AddKidStep.EnterPin(user)
            return false
        }

        /** The parent's token, but only while their add-kid unlock is still valid ([unlocked]); otherwise null */
        private fun authorizedToken(): String? {
            if (!unlocked()) return null
            return parent?.accessToken ?: run {
                fail(AddKidError.SignInAgain, Retry.None)
                null
            }
        }

        /** The parent was unlocked: a paired kid waiting for it signs in, otherwise the adult PINs come next (12.3, 12.4) */
        private suspend fun afterUnlock() {
            val paired = pendingPair
            if (paired != null) finishPairing(paired) else checkAdultPins()
        }

        /**
         * Contract 12.4: every other saved adult with a token must have a PIN before a kid is added. Their account PINs
         * are pulled first, so an account that already has one counts; the rest are listed, else the kid step is next.
         */
        private suspend fun checkAdultPins() {
            if (!unlocked()) return
            _step.value = AddKidStep.Loading
            val parentRow = parent?.rowId
            val others = users.filter { it.rowId != parentRow && it.accessToken != null && gate.profileKind(it) == ProfileKind.ADULT }
            val pulled = coroutineScope { others.map { async { accountPins.refresh(it) } }.awaitAll() }
            users = users.map { user -> pulled.firstOrNull { it.rowId == user.rowId } ?: user }
            showAdultPins(pulled.filter { !it.hasPin && it.accessToken != null })
        }

        private fun showAdultPins(remaining: List<JellyfinUser>) {
            _step.value = if (remaining.isEmpty()) AddKidStep.ChooseHow else AddKidStep.AdultPins(remaining)
        }

        /**
         * Contract 12.3 "Add an existing kid profile": a Quick Connect code the kid's page on the website approves, renewed
         * by itself when it expires, beside this account's own kids that aren't on this TV yet (5.4 connect). Runs until a
         * sign-in arrives or the screen is left (its job is cancelled, which stops the polling).
         */
        private suspend fun pair() {
            if (!unlocked()) return
            abandonPendingPair()
            _step.value = AddKidStep.Pairing()
            coroutineScope {
                val kids = launch { loadOwnKids() }
                try {
                    runPairing()
                } finally {
                    kids.cancel()
                }
            }
        }

        /** This account's own active kids that aren't saved (with a token) on this TV yet; best effort */
        private suspend fun loadOwnKids() {
            val token = parent?.accessToken ?: return
            when (val result = api.list(token)) {
                is KidApiResult.Ok -> {
                    val here = users.filter { it.accessToken != null }.map { normalisedId(it.id.toString()) }.toSet()
                    val kids =
                        result.value.kids.filter { kid ->
                            kid.state == "active" && kid.mediaUserId?.let { normalisedId(it) !in here } == true
                        }
                    updatePairing { it.copy(kids = kids) }
                }

                is KidApiResult.Problem -> {
                    Timber.i("Couldn't list the account's kids for pairing: %s %s", result.status, result.code)
                }

                is KidApiResult.NetworkError -> {
                    Timber.w(result.cause, "Couldn't list the account's kids for pairing")
                }
            }
        }

        private fun updatePairing(transform: (AddKidStep.Pairing) -> AddKidStep.Pairing) {
            _step.update { (it as? AddKidStep.Pairing)?.let(transform) ?: it }
        }

        /** Shows a code, polls it, and gets a new one when it expires; failures back off instead of looping */
        private suspend fun runPairing() {
            var failures = 0
            while (true) {
                val code =
                    try {
                        quickConnect.initiate(server.url)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Pairing code request failed")
                        updatePairing { it.copy(code = null, trouble = true) }
                        delay(backoffMillis(++failures))
                        continue
                    }
                failures = 0
                updatePairing { it.copy(code = code.code, trouble = false) }
                if (awaitPairing(code.secret) == QuickConnectPoll.EXPIRED) {
                    Timber.i("Pairing code expired; showing a new one")
                    continue
                }
                val auth =
                    try {
                        authenticateOwned(code.secret)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Pairing sign-in failed")
                        delay(backoffMillis(++failures))
                        continue
                    }
                // No suspension from here until the result is refused (signed out) or kept by the flow
                return if (isKid(auth)) finishPairing(auth) else refuse(auth)
            }
        }

        /** Polls [secret] every [PAIR_POLL_MS] until it is approved or expires; a failed poll backs off */
        private suspend fun awaitPairing(secret: String): QuickConnectPoll {
            var failures = 0
            while (true) {
                delay(if (failures == 0) PAIR_POLL_MS else backoffMillis(failures))
                val poll =
                    try {
                        quickConnect.poll(server.url, secret)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Pairing poll failed")
                        failures++
                        updatePairing { it.copy(trouble = true) }
                        continue
                    }
                if (failures > 0) {
                    failures = 0
                    updatePairing { it.copy(trouble = false) }
                }
                if (poll != QuickConnectPoll.WAITING) return poll
            }
        }

        /** Contract 12.3: a grown-up's approval is never saved or published, and its token is signed out (best effort) */
        private suspend fun refuse(auth: AuthenticationResult) {
            Timber.i("Pairing code was approved for an adult profile; refusing it")
            auth.accessToken?.let { token -> withContext(NonCancellable) { quickConnect.signOut(server.url, token) } }
            _step.value = AddKidStep.PairingRefused
        }

        /**
         * Contract 12.3: the paired kid signs in through the gate, named as on the server. If the parent's unlock ran out,
         * [auth] is kept while their PIN is asked again, and the sign-in finishes with it afterwards.
         */
        private suspend fun finishPairing(auth: AuthenticationResult) {
            // A fresh sign-in is the flow's own; a kept one ([keepFailedPair]) keeps what it was
            if (pendingPair !== auth) {
                pendingPair = auth
                pendingPairOwned = true
            }
            if (!unlocked()) return
            val name = auth.user?.name?.takeIf { it.isNotBlank() }
            _step.value = AddKidStep.Working(Work.Connecting, name.orEmpty())
            // Handed to the gate, which may save or publish it: from here leaving the flow must not sign it out
            pendingPair = null
            pendingPairOwned = false
            val current =
                try {
                    gate.signInKid(server.url, auth, name)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: ProfileGateException) {
                    // The unlock ran out while signing in: nothing was published, ask for the PIN again
                    Timber.i("Paired kid sign-in refused: %s", ex.decision)
                    keepFailedPair(auth)
                    parent?.let { _step.value = AddKidStep.EnterPin(it) }
                    return
                } catch (ex: Exception) {
                    // Possibly after publication (eg its journal write): kept, but signed out later only if never kept
                    Timber.w(ex, "Paired kid sign-in failed")
                    keepFailedPair(auth)
                    return fail(AddKidError.Generic, Retry.Pair)
                }
            gate.clearParentUnlock()
            _step.value = AddKidStep.Done
            setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
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
                                    // Contract 3.2: refresh the list (the pairing screen loads it again)
                                    return pair()
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
                        authenticateOwned(code.secret)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Quick Connect sign-in failed")
                        return fail(AddKidError.Generic, Retry.Connect(kid))
                    }
                val displayName = connected.displayName.ifBlank { kid.displayName }
                // Signing the kid in is the last use of the parent's authority: still valid? A retry gets a new code, so
                // a sign-in that isn't used is signed out (review round 1)
                if (authorizedToken() == null) return signOutAbandoned(auth)
                val current =
                    try {
                        gate.signInKid(server.url, auth, displayName)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: ProfileGateException) {
                        // The parent's unlock ran out while signing in: nothing was published, ask for the PIN again
                        Timber.i("Kid sign-in refused: %s", ex.decision)
                        releaseFailedSignIn(auth)
                        val user = parent
                        if (user != null) _step.value = AddKidStep.EnterPin(user) else fail(AddKidError.Generic, Retry.Connect(kid))
                        return
                    } catch (ex: Exception) {
                        Timber.w(ex, "Kid sign-in failed")
                        releaseFailedSignIn(auth)
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
            val result = pictures.save(ProfilePictureTarget(server.url, userId, token, name, isKid = true), picked)
            if (result is ProfilePictureResult.Failed) Timber.w("Kid picture not saved; it can be set in Settings")
        }

        private fun fail(
            error: AddKidError,
            retry: Retry,
        ) {
            _step.value = AddKidStep.Failed(error, retry)
        }

        private fun launch(block: suspend () -> Unit): Job {
            job?.cancel()
            return viewModelScope.launch { block() }.also { job = it }
        }

        companion object {
            const val POLL_MS = 1_000L
            const val MAX_POLLS = 30
            const val IN_PROGRESS_WAIT_MS = 2_000L
            const val MAX_IN_PROGRESS_WAITS = 5

            /** Contract 12.3: how often the pairing code is polled, and the longest wait after failures */
            const val PAIR_POLL_MS = 2_000L
            const val MAX_PAIR_BACKOFF_MS = 30_000L

            /** The longest a Quick Connect sign-in may take; it runs to the end even if the flow is left meanwhile */
            const val AUTHENTICATE_TIMEOUT_MS = 20_000L

            /** The wait after [failures] failures in a row: doubling from twice the poll, at most [MAX_PAIR_BACKOFF_MS] */
            fun backoffMillis(failures: Int): Long = (PAIR_POLL_MS shl failures.coerceIn(1, 4)).coerceAtMost(MAX_PAIR_BACKOFF_MS)

            /** Section 1 kid signal: a kid's policy has a MaxParentalRating; an adult's (or a missing policy) doesn't */
            fun isKid(auth: AuthenticationResult): Boolean = auth.user?.policy?.maxParentalRating != null

            /** A 6-digit code as "123 456", easier to read off a TV */
            fun formatCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

            /** Contract 4: media user ids compare without dashes or case */
            fun normalisedId(id: String): String = id.replace("-", "").lowercase()

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
        /** The PIN couldn't be saved to the account; nothing changed */
        val saveFailed: Boolean = false,
    ) : AddKidStep

    /** A pinless adult on a protected TV (plan 4.2): unlocking them is the chooser's job. */
    data class ParentLocked(
        val parent: JellyfinUser,
    ) : AddKidStep

    /** Contract 12.4: the other saved adults without a PIN; each creates one or leaves this TV before a kid is added */
    data class AdultPins(
        val adults: List<JellyfinUser>,
        val removeFailed: Boolean = false,
    ) : AddKidStep

    /** "Create a new kid profile" or "Add an existing kid profile" (contract 12.3) */
    data object ChooseHow : AddKidStep

    /**
     * Contract 12.3: [code] is the pairing code shown (null while one is fetched), [kids] the account's own kids not on
     * this TV yet, and [trouble] is set while the server can't be reached (it keeps trying)
     */
    data class Pairing(
        val code: String? = null,
        val kids: List<KidProfile> = emptyList(),
        val trouble: Boolean = false,
    ) : AddKidStep

    /** Contract 12.3: the code was approved for a grown-up's profile; nothing was saved */
    data object PairingRefused : AddKidStep

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

/** What [AddKidViewModel.createAdultPin] did (contract 12.4) */
enum class AdultPinOutcome {
    SAVED,

    /** The adult's account already had a PIN; it was adopted and nothing was written */
    ALREADY_HAD_PIN,

    /** Nothing was saved */
    NOT_SAVED,
}

enum class NameError { Invalid, Taken }

sealed interface PinError {
    data class Wrong(
        val attemptsBeforeLockout: Int,
    ) : PinError

    data class LockedOut(
        val retryAtMillis: Long,
    ) : PinError

    /** The check itself failed (not a wrong PIN); retrying is fine */
    data object CheckFailed : PinError
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

    /** Contract 12.3: finish a paired kid's sign-in, or pair again */
    data object Pair : Retry

    data class Create(
        val name: String,
        val level: KidLevel,
    ) : Retry

    data class Connect(
        val kid: KidProfile,
    ) : Retry
}
