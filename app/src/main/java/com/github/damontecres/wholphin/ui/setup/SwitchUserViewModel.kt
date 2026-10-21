package com.github.damontecres.wholphin.ui.setup

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.WholphinApplication
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.ifSameIdentity
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.GateDecision
import com.github.damontecres.wholphin.kids.KidProtectionStore
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.PinCheckResult
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.atomic.AtomicReference
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

        private var quickConnectJob: Job? = null

        /** The chooser's switch in progress (kid profiles) */
        private val switchJob = AtomicReference<Deferred<SwitchUserResult>?>(null)

        fun clearSwitchUserState() {
            _state.update { it.copy(switchUserState = LoadingState.Pending) }
        }

        fun resetAttempts() {
            _state.update { it.copy(loginAttempts = 0) }
        }

        fun init() {
            viewModelScope.launchDefault {
                if (BuildConfig.KID_PROFILES_ENABLED) {
                    // Chooser teardown (plan 4.1): no session while the profile screen shows, and no
                    // parental authorization carried over from before
                    kidSessionGate.clearParentUnlock()
                    serverRepository.endSessionForChooser()
                }
                serverRepository.switchServerOrUser()
            }
            quickConnectJob?.cancel()
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
            val job =
                viewModelScope.async(WholphinDispatchers.IO, start = CoroutineStart.LAZY) {
                    try {
                        if (BuildConfig.KID_PROFILES_ENABLED) {
                            val current = kidSessionGate.switchTo(server, user)
                            // Superseded while switching: the newer pick's switch (serialized after this one) wins
                            ensureActive()
                            profileChooserLock.navigateAfterSwitch(current)
                        } else {
                            val current = serverRepository.changeUser(server, user)
                            setupNavigationManager.navigateTo(SetupDestination.AppContent(current))
                        }
                        SwitchUserResult.Success
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: ProfileGateException) {
                        // The refreshed policy needs more than was granted: ask again
                        Timber.i("Switch refused by the gate: %s", ex.decision)
                        askForDecision(user, ex.decision, PinPurpose.SWITCH)
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
                }
            if (BuildConfig.KID_PROFILES_ENABLED) {
                // Kid profiles: a newer pick supersedes an unfinished one, which then never navigates
                switchJob.getAndSet(job)?.cancel()
            }
            job.start()
            return job
        }

        fun login(
            server: JellyfinServer,
            existingUser: JellyfinUser?,
            username: String,
            password: String,
        ) {
            quickConnectJob?.cancel()
            viewModelScope.launchIO {
                try {
                    val api = jellyfin.createApi(baseUrl = server.url)
                    val authenticationResult by api.userApi.authenticateUserByName(
                        username = username,
                        password = password,
                    )
                    finishFreshAuth(server.url, authenticationResult, existingUser)
                } catch (ex: Exception) {
                    Timber.e(ex, "Error logging in user")
                    if (ex is InvalidStatusException && ex.status == 401) {
                        _state.update { it.copy(switchUserState = LoadingState.Error("Invalid username or password")) }
                    } else {
                        setError("Error during login", ex)
                    }
                }
            }
        }

        fun initiateQuickConnect(
            server: JellyfinServer,
            existingUser: JellyfinUser?,
        ) {
            quickConnectJob?.cancel()
            quickConnectJob =
                viewModelScope.launchIO {
                    try {
                        val api = jellyfin.createApi(server.url)
                        var quickConnectStatus =
                            api
                                .quickConnectApi
                                .initiateQuickConnect()
                                .content
                        _state.update { it.copy(quickConnectStatus = quickConnectStatus) }

                        while (!quickConnectStatus.authenticated) {
                            // Two seconds so a phone-approved code signs the TV in promptly.
                            delay(2_000L)
                            quickConnectStatus =
                                api.quickConnectApi
                                    .getQuickConnectState(
                                        secret = quickConnectStatus.secret,
                                    ).content
                            _state.update { it.copy(quickConnectStatus = quickConnectStatus) }
                        }
                        val authenticationResult by api.userApi.authenticateWithQuickConnect(
                            QuickConnectDto(secret = quickConnectStatus.secret),
                        )
                        finishFreshAuth(server.url, authenticationResult, existingUser)
                    } catch (_: CancellationException) {
                        // no-op, user may have canceled
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error during quick connect")
                        if (ex is InvalidStatusException && ex.status == 401) {
                            _state.update {
                                it.copy(
                                    quickConnectEnabled = false,
                                    quickConnectStatus = null,
                                )
                            }
                        }
                        setError("Error with Quick Connect", ex)
                    }
                }
        }

        fun cancelQuickConnect() {
            quickConnectJob?.cancel()
            if (BuildConfig.KID_PROFILES_ENABLED) {
                // The sign-in dialog was closed: an unlock for a switch that sent us there is spent
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
                removeUserNow(user)
            }
        }

        private suspend fun removeUserNow(user: JellyfinUser) {
            serverRepository.removeUser(user)
            val serverUsers = getUsers()
            _state.update { it.copy(users = serverUsers) }
        }

        /**
         * A sign-in that just authenticated with the server (Quick Connect or password). Counts as parental
         * authorization for its target (plan 4.2), but on a protected device an adult without a PIN must
         * set one before anything is published (cancel = no change).
         */
        @VisibleForTesting
        internal suspend fun finishFreshAuth(
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
                _state.update {
                    it.copy(pendingPinSetup = PendingPinSetup(serverUrl, authenticationResult, savedUser))
                }
                return
            }
            completeFreshAuth(serverUrl, authenticationResult, savedUser, null)
        }

        private suspend fun completeFreshAuth(
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            existingUser: JellyfinUser?,
            newPin: String?,
        ) {
            val current = serverRepository.changeUser(serverUrl, authenticationResult, existingUser, newPin = newPin)
            kidSessionGate.grantUnlockForFreshAuth(current.user)
            profileChooserLock.navigateAfterSwitch(current)
        }

        /** The in-flow PIN of a deferred adult sign-in was set: publish it now */
        fun completePinSetup(pin: String) {
            val pending = _state.value.pendingPinSetup ?: return
            viewModelScope.launchIO {
                try {
                    completeFreshAuth(pending.serverUrl, pending.authenticationResult, pending.existingUser, pin)
                    _state.update { it.copy(pendingPinSetup = null) }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error finishing sign-in")
                    _state.update { it.copy(pendingPinSetup = null) }
                    setError("Error during login", ex)
                }
            }
        }

        /** The in-flow PIN setup was cancelled: nothing changes */
        fun cancelPinSetup() {
            _state.update { it.copy(pendingPinSetup = null) }
        }

        /**
         * Kid profiles: a profile was picked on the chooser. PIN profiles always prompt for their PIN, then the
         * switch gate decides whether a parent PIN is needed (plan 4.2, 4.5).
         */
        fun onProfileSelected(user: JellyfinUser) {
            // A new pick supersedes a switch still in progress
            switchJob.getAndSet(null)?.cancel()
            viewModelScope.launchIO {
                // A new pick starts without any earlier unlock
                kidSessionGate.clearParentUnlock()
                if (user.hasPin) {
                    showPrompt(ChooserPrompt.Pin(target = user, pinOwner = user, purpose = PinPurpose.SWITCH))
                } else {
                    continueSwitch(user)
                }
            }
        }

        private suspend fun continueSwitch(user: JellyfinUser) {
            when (val decision = kidSessionGate.evaluate(user)) {
                GateDecision.Allow -> {
                    val result = trySwitchUser(user).await()
                    if (result is SwitchUserResult.Error) {
                        // A failed switch spends the unlock: it must not open another profile later
                        kidSessionGate.clearParentUnlock()
                        _state.update { it.copy(switchError = result.copy(user = user)) }
                    }
                }

                else -> {
                    askForDecision(user, decision, PinPurpose.SWITCH)
                }
            }
        }

        private suspend fun askForDecision(
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
                        ChooserPrompt.Pin(target = target, pinOwner = parents.first(), purpose = purpose)
                    }

                    else -> {
                        ChooserPrompt.PickParent(target, parents, locked = false, purpose = purpose)
                    }
                }
            showPrompt(prompt)
        }

        fun pickParent(parent: JellyfinUser) {
            val prompt = _state.value.prompt as? ChooserPrompt.PickParent ?: return
            showPrompt(ChooserPrompt.Pin(target = prompt.target, pinOwner = parent, purpose = prompt.purpose))
        }

        fun submitPin(pin: String) {
            val prompt = _state.value.prompt as? ChooserPrompt.Pin ?: return
            viewModelScope.launchIO {
                val owner = prompt.pinOwner
                val result =
                    if (owner.profileKind == ProfileKind.ADULT) {
                        // The unlock is bound to this target and action only
                        val purpose =
                            when (prompt.purpose) {
                                PinPurpose.SWITCH -> UnlockPurpose.Switch(prompt.target.rowId)
                                PinPurpose.DELETE -> UnlockPurpose.Remove(prompt.target.rowId)
                            }
                        kidSessionGate.verifyParentPin(owner, pin, purpose)
                    } else {
                        kidSessionGate.verifyProfilePin(owner, pin)
                    }
                when (result) {
                    PinCheckResult.Correct -> {
                        showPrompt(null)
                        when (prompt.purpose) {
                            PinPurpose.SWITCH -> {
                                continueSwitch(prompt.target)
                            }

                            PinPurpose.DELETE -> {
                                continueRemove(prompt.target, pinChecked = true)
                            }
                        }
                    }

                    is PinCheckResult.Wrong -> {
                        showPrompt(prompt.copy(message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)))
                    }

                    is PinCheckResult.LockedOut -> {
                        val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                        showPrompt(prompt.copy(message = context.getString(R.string.kid_pin_locked_out, seconds)))
                    }
                }
            }
        }

        /** A PIN prompt or parent pick was dismissed: nothing changes and any unlock is dropped */
        fun dismissPrompt() {
            showPrompt(null)
            kidSessionGate.clearParentUnlock()
        }

        fun clearSwitchError() {
            _state.update { it.copy(switchError = null) }
        }

        private fun showPrompt(prompt: ChooserPrompt?) {
            _state.update { it.copy(prompt = prompt) }
        }

        /**
         * Long-press delete (plan 4.2): a kid row goes freely ("Remove from this TV"); any other row needs that
         * profile's PIN; a pinless adult on a protected device needs another adult's unlock.
         */
        private fun requestRemoveUser(user: JellyfinUser) {
            viewModelScope.launchIO {
                continueRemove(user, pinChecked = false)
            }
        }

        private suspend fun continueRemove(
            user: JellyfinUser,
            pinChecked: Boolean,
        ) {
            when {
                user.profileKind == ProfileKind.KID || pinChecked -> {
                    removeUserNow(user)
                    kidSessionGate.clearParentUnlock()
                }

                user.hasPin -> {
                    showPrompt(ChooserPrompt.Pin(target = user, pinOwner = user, purpose = PinPurpose.DELETE))
                }

                !kidProtectionStore.isProtected() -> {
                    removeUserNow(user)
                }

                else -> {
                    askForDecision(user, GateDecision.LockedAdult, PinPurpose.DELETE)
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
