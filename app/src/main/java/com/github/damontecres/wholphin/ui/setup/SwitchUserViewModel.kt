package com.github.damontecres.wholphin.ui.setup

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.ifSameIdentity
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.GateDecision
import com.github.damontecres.wholphin.kids.KidProtectionStore
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileChooserLock
import com.github.damontecres.wholphin.kids.ProfileGateException
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.UnlockPurpose
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

@HiltViewModel(assistedFactory = SwitchUserViewModel.Factory::class)
class SwitchUserViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        val jellyfin: Jellyfin,
        val serverRepository: ServerRepository,
        val serverDao: JellyfinServerDao,
        val navigationManager: NavigationManager,
        val setupNavigationManager: SetupNavigationManager,
        val imageUrlService: ImageUrlService,
        private val kidSessionGate: KidSessionGateImpl,
        private val kidProtectionStore: KidProtectionStore,
        private val profileChooserLock: ProfileChooserLock,
        @Assisted val server: JellyfinServer,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(server: JellyfinServer): SwitchUserViewModel
        }

        private val _state = MutableStateFlow(SwitchUserState())
        val state: StateFlow<SwitchUserState> = _state

        /**
         * Kid profiles: the chooser flow in progress (review round 4), or none; guarded by [flowLock]. Every flow the
         * user starts on the chooser (a profile pick with its gate, PIN prompts and switch; a sign-in with its deferred
         * PIN setup; a removal) runs under one [ChooserFlow]. Dismissal, Back or a new flow leaves it: its step is
         * cancelled, and its identity is checked before it grants an unlock, shows a prompt, switches or removes, in
         * the step that publishes, and where it navigates.
         */
        private var flow: ChooserFlow? = null
        private var flowIds = 0L
        private val flowLock = Any()

        /** The step of the flow left last, which a new flow waits for (review round 6); guarded by [flowLock] */
        private var leftStep: Job? = null

        fun clearSwitchUserState() {
            _state.update { it.copy(switchUserState = LoadingState.Pending) }
        }

        fun resetAttempts() {
            _state.update { it.copy(loginAttempts = 0) }
        }

        fun init() {
            // A fresh chooser: nothing started before it continues
            leaveFlow()
            viewModelScope.launchDefault {
                if (BuildConfig.KID_PROFILES_ENABLED) {
                    // Chooser teardown (plan 4.1): no session (nor saved current profile) while the profile screen
                    // shows, and no parental authorization carried over from before
                    kidSessionGate.clearParentUnlock()
                    serverRepository.endSessionForChooser()
                } else {
                    serverRepository.switchServerOrUser()
                }
            }
            viewModelScope.launchIO {
                _state.update { SwitchUserState() }
                try {
                    val serverUsers = getUsers()
                    val (serverVersion, supported) = checkServerVersion()
                    _state.update {
                        it.copy(
                            loading = LoadingState.Success,
                            users = serverUsers,
                            serverVersion = serverVersion,
                            serverVersionSupported = supported,
                        )
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching users for server $server")
                    _state.update {
                        it.copy(
                            loading = LoadingState.Error(ex),
                        )
                    }
                }
            }

            viewModelScope.launchIO {
                try {
                    val quickConnect by
                        jellyfin
                            .createApi(
                                server.url,
                                httpClientOptions =
                                    HttpClientOptions(
                                        requestTimeout = 6.seconds,
                                        connectTimeout = 6.seconds,
                                        socketTimeout = 6.seconds,
                                    ),
                            ).quickConnectApi
                            .getQuickConnectEnabled()
                    _state.update { it.copy(quickConnectEnabled = quickConnect) }
                } catch (_: CancellationException) {
                    // no-op, user may have canceled
                } catch (ex: Exception) {
                    Timber.w(ex, "Error checking quick connect for server ${server.url}")
                    _state.update { it.copy(quickConnectEnabled = false) }
                }
            }
        }

        fun trySwitchUser(user: JellyfinUser): Deferred<SwitchUserResult> {
            if (!BuildConfig.KID_PROFILES_ENABLED) {
                return viewModelScope.async(WholphinDispatchers.IO) { switchUser(null, user) }
            }
            // Kid profiles: a switch is a flow of its own, so it supersedes an unfinished one
            return beginFlow { flow ->
                viewModelScope.async(WholphinDispatchers.IO, start = CoroutineStart.LAZY) {
                    inFlow(flow) { switchUser(flow, user) }
                }
            }
        }

        /**
         * Starts a new chooser flow, leaving the current one, with [step] (created lazily) as its first step. Called
         * in the order the user acted, so the latest flow wins.
         */
        private fun <J : Job> beginFlow(step: (ChooserFlow) -> J): J {
            val job =
                synchronized(flowLock) {
                    leaveFlowLocked()
                    // The left flow's step is cancelled; the new flow waits for it to end (and withdraw what it
                    // published) before it evaluates anything (review round 6)
                    val next = ChooserFlow(++flowIds, leftStep)
                    flow = next
                    step(next).also { next.step = it }
                }
            job.start()
            return job
        }

        /**
         * Runs [step] (created lazily) as [flow]'s next step, if [flow] is still current and not busy. Returns its job,
         * or null when it wasn't started.
         */
        private fun continueFlow(
            flow: ChooserFlow,
            step: (ChooserFlow) -> Job,
        ): Job? {
            val job =
                synchronized(flowLock) {
                    if (this.flow !== flow || flow.step?.isActive == true) return null
                    step(flow).also { flow.step = it }
                }
            job.start()
            return job
        }

        private fun launchStep(
            flow: ChooserFlow,
            block: suspend CoroutineScope.() -> Unit,
        ): Job = viewModelScope.launchIO(start = CoroutineStart.LAZY) { inFlow(flow) { block() } }

        /**
         * Runs a step of [flow], once the step of the flow left before it has ended; a session it published but never
         * navigated into is withdrawn when it ends
         */
        private suspend fun <T> inFlow(
            flow: ChooserFlow,
            block: suspend () -> T,
        ): T =
            try {
                flow.follows?.join()
                block()
            } finally {
                withdrawUnfinished(flow)
            }

        /**
         * [flow] published a session, then was left (or failed) before navigating into it: that session ends again,
         * unless a newer one was published meanwhile, so nothing a left flow published stays behind the chooser.
         */
        private suspend fun withdrawUnfinished(flow: ChooserFlow) {
            val published =
                synchronized(flowLock) {
                    flow.published?.takeIf { !flow.navigated }.also { flow.published = null }
                } ?: return
            Timber.i("Chooser flow %s was left after publishing: withdrawing its session", flow.id)
            withContext(NonCancellable) { serverRepository.endSessionForChooser(published) }
        }

        /** Dismissal, Back or a new flow: the current flow is cancelled and can no longer grant, publish or navigate */
        private fun leaveFlow() {
            synchronized(flowLock) { leaveFlowLocked() }
        }

        private fun leaveFlowLocked() {
            flow?.step?.let {
                it.cancel()
                leftStep = it
            }
            flow = null
            // Whatever the left flow was asking for goes with it
            _state.update { it.copy(prompt = null, pendingPinSetup = null) }
        }

        /** Runs [block] only while [flow] is current, atomically with leaving it; returns whether it ran */
        private inline fun ifCurrent(
            flow: ChooserFlow,
            block: () -> Unit,
        ): Boolean =
            synchronized(flowLock) {
                if (this.flow !== flow) return false
                block()
                true
            }

        private fun ensureCurrent(flow: ChooserFlow) {
            if (!ifCurrent(flow) {}) throw ChooserFlowLeft(flow.id)
        }

        /** Updates the state for [flow], which must still be current: a left flow never shows anything again */
        private fun updateState(
            flow: ChooserFlow,
            transform: (SwitchUserState) -> SwitchUserState,
        ) {
            if (!ifCurrent(flow) { _state.update(transform) }) throw ChooserFlowLeft(flow.id)
        }

        private fun showPrompt(
            flow: ChooserFlow,
            prompt: ChooserPrompt?,
        ) = updateState(flow) { it.copy(prompt = prompt) }

        /** Grants an unlock only while [flow] is current (see [KidSessionGateImpl.verifyParentPin]) */
        private fun admit(flow: ChooserFlow): (grant: () -> Unit) -> Unit = { grant -> ifCurrent(flow, grant) }

        /**
         * [flow]'s check in the non-suspending step that publishes a session: only the current flow publishes, and it
         * records what it published
         */
        private fun publication(flow: ChooserFlow): (CurrentUser) -> Unit = { published -> updateFlow(flow) { flow.published = published } }

        private fun updateFlow(
            flow: ChooserFlow,
            block: () -> Unit,
        ) {
            if (!ifCurrent(flow, block)) throw ChooserFlowLeft(flow.id)
        }

        /**
         * Navigates into [current] only if [flow], still current, published it and it wasn't withdrawn meanwhile; the
         * main-thread step that navigates marks it entered with the session coordinator (review round 6), and the entry
         * is then recorded durably, even if this step is cancelled on its way back (review round 7)
         */
        private suspend fun navigateInto(
            flow: ChooserFlow,
            current: CurrentUser,
        ) {
            try {
                profileChooserLock.navigateAfterSwitch(current) {
                    synchronized(flowLock) {
                        (this.flow === flow && flow.published === current && serverRepository.markEntered(flow, current))
                            .also { if (it) flow.navigated = true }
                    }
                }
            } finally {
                if (synchronized(flowLock) { flow.navigated }) {
                    withContext(NonCancellable) { serverRepository.finishEntry() }
                }
            }
        }

        /** [flow] is null for the upstream (no kid profiles) switch */
        private suspend fun switchUser(
            flow: ChooserFlow?,
            user: JellyfinUser,
        ): SwitchUserResult =
            try {
                if (flow != null) {
                    ensureCurrent(flow)
                    val current = kidSessionGate.switchTo(server, user, authorize = publication(flow), entryOwner = flow)
                    navigateInto(flow, current)
                } else {
                    val current = serverRepository.changeUser(server, user)
                    setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                }
                SwitchUserResult.Success
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: ProfileGateException) {
                // The refreshed policy (or an unlock that ran out) needs more than was granted: ask again
                Timber.i("Switch refused by the gate: %s", ex.decision)
                if (flow != null) askForDecision(flow, user, ex.decision, PinPurpose.SWITCH)
                SwitchUserResult.Success
            } catch (ex: InvalidStatusException) {
                when (ex.status) {
                    401 -> {
                        Timber.w(ex, "Error switching user 401")
                        SwitchUserResult.Error(
                            context.getString(R.string.login_credentials_expired),
                            true,
                        )
                    }

                    403 -> {
                        Timber.w(ex, "Error switching user 403")
                        SwitchUserResult.Error(
                            context.getString(R.string.login_not_authorized),
                            false,
                        )
                    }

                    else -> {
                        Timber.e(ex, "Error switching user")
                        SwitchUserResult.Error(ex.localizedMessage, true)
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error switching user")
                SwitchUserResult.Error(ex.localizedMessage, true)
            }

        fun login(
            server: JellyfinServer,
            existingUser: JellyfinUser?,
            username: String,
            password: String,
        ) {
            signIn(
                server.url,
                existingUser,
                onError = { ex ->
                    if (ex is InvalidStatusException && ex.status == 401) {
                        _state.update { it.copy(switchUserState = LoadingState.Error("Invalid username or password")) }
                    } else {
                        setError("Error during login", ex)
                    }
                },
            ) {
                val api = jellyfin.createApi(baseUrl = server.url)
                api.userApi
                    .authenticateUserByName(
                        username = username,
                        password = password,
                    ).content
            }
        }

        fun initiateQuickConnect(
            server: JellyfinServer,
            existingUser: JellyfinUser?,
        ) {
            signIn(
                server.url,
                existingUser,
                onError = { ex ->
                    if (ex is InvalidStatusException && ex.status == 401) {
                        _state.update {
                            it.copy(
                                quickConnectEnabled = false,
                                quickConnectStatus = null,
                            )
                        }
                    }
                    setError("Error with Quick Connect", ex)
                },
            ) { flow ->
                val api = jellyfin.createApi(server.url)
                var quickConnectStatus =
                    api
                        .quickConnectApi
                        .initiateQuickConnect()
                        .content
                updateState(flow) { it.copy(quickConnectStatus = quickConnectStatus) }

                while (!quickConnectStatus.authenticated) {
                    // Two seconds so a phone-approved code signs the TV in promptly.
                    delay(2_000L)
                    quickConnectStatus =
                        api.quickConnectApi
                            .getQuickConnectState(
                                secret = quickConnectStatus.secret,
                            ).content
                    updateState(flow) { it.copy(quickConnectStatus = quickConnectStatus) }
                }
                api.userApi
                    .authenticateWithQuickConnect(
                        QuickConnectDto(secret = quickConnectStatus.secret),
                    ).content
            }
        }

        /**
         * A sign-in on the chooser (password or Quick Connect) as a flow of its own, from [authenticate] through the
         * deferred PIN setup to the switch. [onError] reports a failure while the flow is still current.
         */
        @VisibleForTesting
        internal fun signIn(
            serverUrl: String,
            existingUser: JellyfinUser?,
            onError: (Exception) -> Unit,
            authenticate: suspend (ChooserFlow) -> AuthenticationResult,
        ): Job =
            beginFlow { flow ->
                launchStep(flow) {
                    try {
                        val authenticationResult = authenticate(flow)
                        finishFreshAuth(flow, serverUrl, authenticationResult, existingUser)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error signing in")
                        ifCurrent(flow) { onError(ex) }
                    }
                }
            }

        fun cancelQuickConnect() {
            // The sign-in dialog was closed (or switched to a password): its flow is left
            leaveFlow()
            if (BuildConfig.KID_PROFILES_ENABLED) {
                // An unlock for a switch that sent us there is spent
                kidSessionGate.clearParentUnlock()
            }
            _state.update {
                it.copy(
                    quickConnectStatus = null,
                )
            }
        }

        fun removeUser(user: JellyfinUser) {
            if (BuildConfig.KID_PROFILES_ENABLED) {
                requestRemoveUser(user)
                return
            }
            viewModelScope.launchIO {
                removeUserNow(null, user)
            }
        }

        /** Removes [user]; for kid profiles only while [flow] is still current when the removal is committed */
        private suspend fun removeUserNow(
            flow: ChooserFlow?,
            user: JellyfinUser,
        ) {
            if (flow == null) {
                serverRepository.removeUser(user)
            } else {
                serverRepository.removeUser(user, authorize = { ensureCurrent(flow) })
            }
            val serverUsers = getUsers()
            _state.update { it.copy(users = serverUsers) }
        }

        /**
         * A sign-in that just authenticated with the server (Quick Connect or password). Counts as parental
         * authorization for its target (plan 4.2), but on a protected device an adult without a PIN must
         * set one before anything is published (cancel = no change).
         */
        private suspend fun finishFreshAuth(
            flow: ChooserFlow,
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            existingUser: JellyfinUser?,
        ) {
            if (!BuildConfig.KID_PROFILES_ENABLED) {
                val current = serverRepository.changeUser(serverUrl, authenticationResult, existingUser)
                setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                return
            }
            val authedUser = authenticationResult.user
            val policy = authedUser?.policy
            val isAdult = policy != null && policy.maxParentalRating == null
            // The profile picked on the chooser counts only if it is the identity that just authenticated;
            // otherwise that identity's own saved row (or none), so nobody inherits another profile's PIN
            val serverId = authenticationResult.serverId?.toUUIDOrNull() ?: server.id
            val savedUser =
                existingUser.ifSameIdentity(serverId, authedUser?.id)
                    ?: authedUser?.let { withContext(WholphinDispatchers.IO) { serverDao.getUser(serverId, it.id) } }
            if (isAdult && savedUser?.pin.isNullOrBlank() && kidProtectionStore.isProtected()) {
                Timber.i("Adult without a PIN on a protected device: PIN setup before sign-in")
                // The flow waits for the PIN; cancelling the setup leaves it
                updateState(flow) {
                    it.copy(pendingPinSetup = PendingPinSetup(serverUrl, authenticationResult, savedUser))
                }
                return
            }
            completeFreshAuth(flow, serverUrl, authenticationResult, savedUser, null)
        }

        private suspend fun completeFreshAuth(
            flow: ChooserFlow,
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            existingUser: JellyfinUser?,
            newPin: String?,
        ) {
            ensureCurrent(flow)
            val current =
                serverRepository.changeUser(
                    serverUrl,
                    authenticationResult,
                    existingUser,
                    newPin = newPin,
                    authorize = publication(flow),
                    entryOwner = flow,
                )
            kidSessionGate.grantUnlockForFreshAuth(current.user, admit(flow))
            navigateInto(flow, current)
        }

        /** The in-flow PIN of a deferred adult sign-in was set: the sign-in's flow publishes it now */
        fun completePinSetup(pin: String) {
            // A new PIN is exactly 4 presses; the PIN screen never sends anything else
            if (!PinRules.isValidNewPin(pin)) return
            val flow: ChooserFlow
            val pending: PendingPinSetup
            synchronized(flowLock) {
                flow = this.flow ?: return
                pending = _state.value.pendingPinSetup ?: return
            }
            continueFlow(flow) {
                launchStep(flow) {
                    try {
                        completeFreshAuth(flow, pending.serverUrl, pending.authenticationResult, pending.existingUser, pin)
                        ifCurrent(flow) { _state.update { it.copy(pendingPinSetup = null) } }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error finishing sign-in")
                        ifCurrent(flow) {
                            _state.update { it.copy(pendingPinSetup = null) }
                            setError("Error during login", ex)
                        }
                    }
                }
            }
        }

        /** The in-flow PIN setup was cancelled (Back): its sign-in is left and nothing changes */
        fun cancelPinSetup() {
            leaveFlow()
        }

        /**
         * Kid profiles: a profile was picked on the chooser. PIN profiles always prompt for their PIN, then the
         * switch gate decides whether a parent PIN is needed (plan 4.2, 4.5).
         */
        fun onProfileSelected(user: JellyfinUser) {
            beginFlow { flow ->
                // A new pick starts without any earlier unlock (cleared together with leaving the earlier flow, so
                // that flow can't grant one after this)
                kidSessionGate.clearParentUnlock()
                launchStep(flow) {
                    if (user.hasPin) {
                        showPrompt(flow, pinPrompt(target = user, pinOwner = user, purpose = PinPurpose.SWITCH))
                    } else {
                        continueSwitch(flow, user)
                    }
                }
            }
        }

        /** The pick of [user] continues, as a step of [flow] */
        private suspend fun continueSwitch(
            flow: ChooserFlow,
            user: JellyfinUser,
        ) {
            ensureCurrent(flow)
            when (val decision = kidSessionGate.evaluate(user)) {
                GateDecision.Allow -> {
                    val result = switchUser(flow, user)
                    if (result is SwitchUserResult.Error) {
                        updateFlow(flow) {
                            // A failed switch spends its unlock: it must not open that profile later. Only this
                            // switch's own unlock, never one granted for another selection.
                            kidSessionGate.clearParentUnlock(UnlockPurpose.Switch(user.rowId))
                            _state.update { it.copy(switchError = result.copy(user = user)) }
                        }
                    }
                }

                else -> {
                    askForDecision(flow, user, decision, PinPurpose.SWITCH)
                }
            }
        }

        private suspend fun askForDecision(
            flow: ChooserFlow,
            target: JellyfinUser,
            decision: GateDecision,
            purpose: PinPurpose,
        ) {
            val parents =
                getSavedUsers().filter {
                    it.profileKind == ProfileKind.ADULT && it.hasPin && it.rowId != target.rowId
                }
            val prompt =
                when {
                    decision == GateDecision.LockedAdult -> {
                        ChooserPrompt.PickParent(target, parents, locked = true, purpose = purpose)
                    }

                    parents.size == 1 -> {
                        pinPrompt(target = target, pinOwner = parents.first(), purpose = purpose)
                    }

                    else -> {
                        ChooserPrompt.PickParent(target, parents, locked = false, purpose = purpose)
                    }
                }
            showPrompt(flow, prompt)
        }

        fun pickParent(parent: JellyfinUser) {
            synchronized(flowLock) {
                // The prompt showing always belongs to the current flow
                val prompt = _state.value.prompt as? ChooserPrompt.PickParent ?: return
                _state.update {
                    it.copy(prompt = pinPrompt(target = prompt.target, pinOwner = parent, purpose = prompt.purpose))
                }
            }
        }

        /**
         * Checks the PIN typed into the current PIN prompt, as its flow's next step. The returned job (null when nothing
         * was started) ends with that step, so the prompt stays latched until then.
         */
        fun submitPin(pin: String): Job? {
            val flow: ChooserFlow
            val prompt: ChooserPrompt.Pin
            synchronized(flowLock) {
                flow = this.flow ?: return null
                prompt = _state.value.prompt as? ChooserPrompt.Pin ?: return null
            }
            // The PIN continues the flow it was asked for, as its next step
            return continueFlow(flow) {
                launchStep(flow) {
                    val result =
                        try {
                            checkPromptPin(flow, prompt, pin)
                        } catch (ex: CancellationException) {
                            // Including ChooserFlowLeft
                            throw ex
                        } catch (ex: Exception) {
                            // Eg a storage failure: the same prompt says so and takes another try
                            Timber.w(ex, "PIN check failed")
                            showPrompt(flow, prompt.copy(message = context.getString(R.string.kids_error_generic)))
                            return@launchStep
                        }
                    when (result) {
                        PinCheckResult.Correct -> {
                            showPrompt(flow, null)
                            when (prompt.purpose) {
                                PinPurpose.SWITCH -> {
                                    continueSwitch(flow, prompt.target)
                                }

                                PinPurpose.DELETE -> {
                                    continueRemove(flow, prompt.target, pinChecked = true)
                                }
                            }
                        }

                        is PinCheckResult.Wrong -> {
                            showPrompt(flow, prompt.copy(message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)))
                        }

                        is PinCheckResult.LockedOut -> {
                            val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                            showPrompt(
                                flow,
                                prompt.copy(
                                    message = context.getString(R.string.kid_pin_locked_out, seconds),
                                    lockedUntilMillis = result.retryAtMillis,
                                ),
                            )
                        }
                    }
                }
            }
        }

        private val promptIds = AtomicLong()

        /** A new PIN prompt, with its own identity (see [ChooserPrompt.Pin.id]) */
        private fun pinPrompt(
            target: JellyfinUser,
            pinOwner: JellyfinUser,
            purpose: PinPurpose,
        ) = ChooserPrompt.Pin(target = target, pinOwner = pinOwner, purpose = purpose, id = promptIds.incrementAndGet())

        private suspend fun checkPromptPin(
            flow: ChooserFlow,
            prompt: ChooserPrompt.Pin,
            pin: String,
        ): PinCheckResult {
            val owner = prompt.pinOwner
            return if (owner.profileKind == ProfileKind.ADULT) {
                // The unlock is bound to this target and action only, and granted only while this flow is still current
                val purpose =
                    when (prompt.purpose) {
                        PinPurpose.SWITCH -> UnlockPurpose.Switch(prompt.target.rowId)
                        PinPurpose.DELETE -> UnlockPurpose.Remove(prompt.target.rowId)
                    }
                kidSessionGate.verifyParentPin(owner, pin, purpose, admit(flow))
            } else {
                kidSessionGate.verifyProfilePin(owner, pin)
            }
        }

        /**
         * A PIN prompt or parent pick was dismissed: its flow is left (a PIN still being checked grants nothing and
         * opens nothing) and any unlock is dropped
         */
        fun dismissPrompt() {
            synchronized(flowLock) {
                leaveFlowLocked()
                kidSessionGate.clearParentUnlock()
            }
        }

        /**
         * Add kid or Add User is opening (review round 5): the flow in progress is left first, together with any
         * unlock it holds, so a slow pick can't publish or navigate behind that dialog. Called before it shows.
         */
        fun leaveForOtherAction() {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            synchronized(flowLock) {
                leaveFlowLocked()
                kidSessionGate.clearParentUnlock()
            }
        }

        fun clearSwitchError() {
            _state.update { it.copy(switchError = null) }
        }

        /**
         * Long-press delete (plan 4.2): a kid row goes freely ("Remove from this TV"); any other row needs that
         * profile's PIN; a pinless adult on a protected device needs another adult's unlock.
         */
        private fun requestRemoveUser(user: JellyfinUser) {
            beginFlow { flow -> launchStep(flow) { continueRemove(flow, user, pinChecked = false) } }
        }

        private suspend fun continueRemove(
            flow: ChooserFlow,
            user: JellyfinUser,
            pinChecked: Boolean,
        ) {
            when {
                user.profileKind == ProfileKind.KID || pinChecked -> {
                    removeUserNow(flow, user)
                    ifCurrent(flow) { kidSessionGate.clearParentUnlock() }
                }

                user.hasPin -> {
                    showPrompt(flow, pinPrompt(target = user, pinOwner = user, purpose = PinPurpose.DELETE))
                }

                !kidProtectionStore.isProtected() -> {
                    removeUserNow(flow, user)
                }

                else -> {
                    askForDecision(flow, user, GateDecision.LockedAdult, PinPurpose.DELETE)
                }
            }
        }

        private suspend fun getSavedUsers(): List<JellyfinUser> =
            withContext(WholphinDispatchers.IO) {
                serverDao.getServer(server.id)?.users.orEmpty()
            }

        private suspend fun getUsers(): List<JellyfinUserAndImage> =
            withContext(WholphinDispatchers.IO) {
                val api = jellyfin.createApi(server.url)
                val knownUsers =
                    serverDao
                        .getServer(server.id)
                        ?.users
                        .orEmpty()
                        .map {
                            JellyfinUserAndImage(
                                user = it,
                                imageUrl = api.imageApi.getUserImageUrl(it.id),
                                known = true,
                            )
                        }
                val knownUserIds = knownUsers.map { it.user.id }
                val publicUsers =
                    api.userApi
                        .getPublicUsers()
                        .content
                        .map {
                            JellyfinUser(
                                id = it.id,
                                name = it.name,
                                serverId = server.id,
                                accessToken = null,
                            )
                        }.filter { it.id !in knownUserIds }
                        .map {
                            JellyfinUserAndImage(
                                user = it,
                                imageUrl = api.imageApi.getUserImageUrl(it.id),
                                known = false,
                            )
                        }

                knownUsers + publicUsers
            }

        private suspend fun checkServerVersion(): Pair<String?, ServerVersionSupported> {
            val api = jellyfin.createApi(server.url)
            val systemInfo by api.systemApi.getPublicSystemInfo()
            val serverVersion = systemInfo.version?.let { ServerVersion.fromString(it) }
            return if (serverVersion == null) {
                systemInfo.version to ServerVersionSupported.UNKNOWN
            } else if (serverVersion < WholphinApplication.minimumServerVersion) {
                systemInfo.version to ServerVersionSupported.NOT_SUPPORTED
            } else {
                systemInfo.version to ServerVersionSupported.SUPPORTED
            }
        }

        private fun setError(
            msg: String? = null,
            ex: Exception? = null,
        ) {
            _state.update {
                it.copy(
                    loginAttempts = it.loginAttempts + 1,
                    switchUserState = LoadingState.Error(msg, ex),
                )
            }
        }
    }

/**
 * Kid profiles: one flow the user started on the chooser (review round 4), owned by [SwitchUserViewModel]: its
 * identity, its step in progress, and the session it published (and whether it navigated into it). [follows] is the
 * step of the flow left before it, which its first step waits for (review round 6).
 */
class ChooserFlow internal constructor(
    val id: Long,
    internal val follows: Job? = null,
) {
    internal var step: Job? = null
    internal var published: CurrentUser? = null
    internal var navigated = false
}

/** The chooser flow [flowId] was left (dismissed, Backed out of or replaced): it stops without side effects */
class ChooserFlowLeft(
    flowId: Long,
) : CancellationException("Chooser flow $flowId was left")

data class JellyfinUserAndImage(
    val user: JellyfinUser,
    val imageUrl: String?,
    val known: Boolean,
)

/** Why a PIN is asked for on the chooser */
enum class PinPurpose { SWITCH, DELETE }

/** Kid profiles: what the chooser is asking for */
sealed interface ChooserPrompt {
    val target: JellyfinUser

    /** [pinOwner]'s PIN; for a parent unlock the owner differs from [target] */
    data class Pin(
        override val target: JellyfinUser,
        val pinOwner: JellyfinUser,
        val purpose: PinPurpose,
        val message: String? = null,
        /**
         * This prompt's identity: a replacement prompt (eg a kid's own PIN, then a parent's) gets a new one and so a
         * fresh entry; wrong tries are copies and keep it. Set by the view model; 0 in a hand-made prompt.
         */
        val id: Long = 0L,
        /** While locked out, the entry takes no presses until then */
        val lockedUntilMillis: Long = 0L,
    ) : ChooserPrompt {
        val isParentPin: Boolean get() = pinOwner.rowId != target.rowId
    }

    /** Pick which parent unlocks [target]; [locked] = a pinless adult on a protected device */
    data class PickParent(
        override val target: JellyfinUser,
        val parents: List<JellyfinUser>,
        val locked: Boolean,
        val purpose: PinPurpose,
    ) : ChooserPrompt
}

/** An adult's completed sign-in held back until they set a PIN (protected device) */
data class PendingPinSetup(
    val serverUrl: String,
    val authenticationResult: AuthenticationResult,
    val existingUser: JellyfinUser?,
)

data class SwitchUserState(
    // LoadingState for fetching available users
    val loading: LoadingState = LoadingState.Pending,
    val quickConnectEnabled: Boolean = false,
    val quickConnectStatus: QuickConnectResult? = null,
    val users: List<JellyfinUserAndImage> = emptyList(),
    // LoadingState for while adding/switching users
    val switchUserState: LoadingState = LoadingState.Pending,
    val loginAttempts: Int = 0,
    val serverVersion: String? = null,
    val serverVersionSupported: ServerVersionSupported = ServerVersionSupported.UNKNOWN,
    // Kid profiles
    val prompt: ChooserPrompt? = null,
    val pendingPinSetup: PendingPinSetup? = null,
    val switchError: SwitchUserResult.Error? = null,
)

/**
 * Result of trying to switch users via [SwitchUserViewModel.trySwitchUser]
 */
sealed interface SwitchUserResult {
    data object Success : SwitchUserResult

    data class Error(
        val errorMessage: String?,
        val showLogin: Boolean,
        val user: JellyfinUser? = null,
    ) : SwitchUserResult
}
