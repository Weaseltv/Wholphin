package com.github.damontecres.wholphin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.kids.KidProtectionStore
import com.github.damontecres.wholphin.kids.ProfileCeiling
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileCeiling
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles managing the current server & user as well as adding & removing new ones
 */
@Singleton
class ServerRepository
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        val jellyfin: Jellyfin,
        val serverDao: JellyfinServerDao,
        val apiClient: ApiClient,
        val userPreferencesDataStore: DataStore<AppPreferences>,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val kidProtectionStore: KidProtectionStore,
    ) {
        /**
         * The session coordinator's lock (plan 4.1, 4.2). Every change of the shared [ApiClient]'s credentials,
         * [current] and [currentUserDto] holds it, and lands in one non-suspending [commitSession] step, so a session
         * change never interleaves with another one. Network calls stay outside it.
         */
        private val sessionMutex = Mutex()

        private var _current = MutableStateFlow<CurrentUser?>(null)
        val current: StateFlow<CurrentUser?> = _current

        /** The session the last [assignSession] published; a policy refresh of it doesn't replace it */
        private var publishedSession: CurrentUser? = null

        /**
         * Kid profiles: the last session a switch published, with the session ceiling from before that switch
         * (review round 5) and whether it was entered yet (review round 6). Only a newer publication or a withdrawal
         * replaces it, so a withdrawal still finds it after the chooser teardown ended that session. Changed under
         * [sessionMutex], and only read or changed on the main thread ([commitSession] steps and [markEntered]).
         */
        private var lastPublication: Publication? = null

        private var _currentUserDto = MutableStateFlow<UserDto?>(null)
        val currentUserDto: UserDto? get() = _currentUserDto.value
        val currentUserDtoFlow: StateFlow<UserDto?> get() = _currentUserDto

        val currentServer: JellyfinServer? get() = _current.value?.server
        val currentServerFlow: Flow<JellyfinServer?> get() = _current.map { it?.server }
        val currentUser: JellyfinUser? get() = _current.value?.user

        @OptIn(ExperimentalCoroutinesApi::class)
        val currentUserFlow: Flow<JellyfinUser?>
            get() =
                _current
                    .flatMapLatest { user ->
                        if (user?.user != null) {
                            serverDao.getUserFlow(user.user.serverId, user.user.id)
                        } else {
                            flow { emit(null) }
                        }
                    }

        /**
         * Adds a server to the app database and updated the [ApiClient] to the server's URL
         *
         * The current user is removed
         */
        suspend fun addAndChangeServer(server: JellyfinServer) {
            withContext(ioDispatcher) {
                serverDao.addOrUpdateServer(server)
            }
            sessionMutex.withLock {
                commitSession { assignSession(null, null, baseUrl = server.url) }
            }
        }

        /**
         * The one step that changes the session (plan 4.1, 4.2); the caller holds [sessionMutex]. [step] runs on the
         * main thread without suspending, so it sees the session as it is right then: it revalidates whatever it
         * depends on there, and the shared client's token, [currentUserDto] and [current] change together.
         */
        private suspend fun <T> commitSession(step: () -> T): T = withContext(WholphinDispatchers.Main) { step() }

        /**
         * Sets the shared client's credentials and the session together; only called inside a [commitSession] step.
         * WeaselFin: the user's policy goes out first, so anything that reacts to the new user (the Seerr kid guard)
         * already sees that user's policy.
         */
        private fun assignSession(
            current: CurrentUser?,
            userDto: UserDto?,
            baseUrl: String? = current?.server?.url ?: apiClient.baseUrl,
        ) {
            apiClient.update(baseUrl = baseUrl, accessToken = current?.user?.accessToken)
            _currentUserDto.value = userDto
            _current.value = current
            publishedSession = current
        }

        /**
         * Saves the server & User to the app database and updates the [ApiClient] to use this server & user
         *
         * The user is fetched with a client scoped to this call, so the shared [ApiClient] and [current] only
         * change once the switch completes. [beforePublish] sees the user with its freshly fetched server policy
         * and may throw (eg [com.github.damontecres.wholphin.kids.ProfileGateException]) to abort the switch;
         * the refreshed policy columns of an already saved profile are still recorded. [beforePublish] runs under the
         * session lock that serializes every switch, so its gate check holds until the switch is published.
         * [authorize] runs in the very step that publishes, after the switch was persisted, and may throw to refuse it
         * (eg an unlock that expired meanwhile, or a chooser flow the user left): nothing is published and the
         * journalled switch is aborted. It is given the session about to be published, the one this returns.
         *
         * Kid profiles (review round 6): with an [entryOwner], the session is published but not entered until that
         * owner calls [markEntered] where it navigates into it. Until then, the next switch (or gate evaluation) that
         * isn't [entryOwner]'s withdraws it first; see [withdrawUnentered]. Its switch stays journalled until then too
         * (review round 7): the session ceiling reads unknown until [finishEntry] records the entry, so a process death
         * before that restarts gated, never at the published profile's ceiling.
         */
        suspend fun changeUser(
            server: JellyfinServer,
            user: JellyfinUser,
            beforePublish: (suspend (JellyfinUser) -> Unit)? = null,
            authorize: ((CurrentUser) -> Unit)? = null,
            entryOwner: Any? = null,
        ): CurrentUser =
            withContext(ioDispatcher) {
                if (server.id != user.serverId) {
                    throw IllegalStateException("User is not part of the server")
                }
                Timber.v("Changing user to ${user.name} on ${server.url}")
                // Same client & device identity as the shared client, so the server sees one device
                val scopedApi =
                    jellyfin.createApi(
                        server.url,
                        user.accessToken,
                        apiClient.clientInfo,
                        apiClient.deviceInfo,
                        apiClient.httpClientOptions,
                    )
                val userDto by scopedApi.userApi.getCurrentUser()
                val updatedServer =
                    try {
                        val sysInfo by scopedApi.systemApi.getPublicSystemInfo()
                        server.copy(name = sysInfo.serverName, version = sysInfo.version)
                    } catch (ex: Exception) {
                        Timber.w(ex, "Exception fetching public system info")
                        server
                    }
                val updatedUser =
                    user
                        .copy(
                            id = userDto.id,
                            name = userDto.name,
                        ).withPolicyOf(userDto)
                // One session mutation at a time (plan 4.1, 4.2): the fresh gate check, the journal, publication and
                // completion of a switch never interleave with another switch, so a session can't end up with the
                // ceiling of the profile switched to just before or after it
                val currentUser =
                    sessionMutex.withLock {
                        // Kid profiles (review round 6): never gated or published against the ceiling of a session
                        // a left flow published but never entered
                        withdrawUnenteredLocked(entryOwner)
                        if (beforePublish != null) {
                            try {
                                beforePublish.invoke(updatedUser)
                            } catch (ex: Exception) {
                                if (updatedUser.rowId > 0) {
                                    if (BuildConfig.KID_PROFILES_ENABLED && updatedUser.profileKind != ProfileKind.ADULT) {
                                        // Never a kid row on an unprotected device
                                        kidProtectionStore.protect()
                                    }
                                    serverDao.updateUserPolicy(
                                        updatedUser.rowId,
                                        updatedUser.maxParentalRating,
                                        updatedUser.policyState,
                                    )
                                }
                                throw ex
                            }
                        }
                        publishSwitch(updatedServer, updatedUser, userDto, authorize, entryOwner).also {
                            getServerSharedPreferences(context).edit(true) {
                                putString(SERVER_URL_KEY, updatedServer.url)
                                putString(ACCESS_TOKEN_KEY, it.user.accessToken)
                            }
                        }
                    }
                return@withContext currentUser
            }

        /**
         * Saves and publishes a switch that passed the gate; the caller holds [sessionMutex]. Kid profiles: the
         * switch is journalled first, and whether it ends completed (destination's ceiling) or aborted (previous
         * ceiling) depends only on whether [current] was actually published, never on how the dispatcher returned:
         * a cancellation that lands after publication still completes it (plan 4.2). [authorize] is checked in the
         * publishing step itself; if it throws, nothing is published and the switch is aborted.
         *
         * Review round 7: a session published for an [entryOwner] doesn't complete its switch yet. The journal is held
         * open ([KidProtectionStore.holdSwitch]) until [finishEntry] records the entry or a withdrawal closes it.
         */
        private suspend fun publishSwitch(
            server: JellyfinServer,
            user: JellyfinUser,
            userDto: UserDto,
            authorize: ((CurrentUser) -> Unit)?,
            entryOwner: Any?,
        ): CurrentUser {
            val kidProfiles = BuildConfig.KID_PROFILES_ENABLED
            // Kid profiles: what a withdrawal of this session restores (review round 5)
            val ceilingBefore = if (kidProfiles) kidProtectionStore.sessionCeiling() else null
            // Kid profiles: protection is on before a kid row is admitted, and the switch is journalled so an
            // interruption before it completes restarts gated (unknown ceiling), never at a higher one
            val switchId = if (kidProfiles) kidProtectionStore.beginSwitch(protect = user.profileKind != ProfileKind.ADULT) else null
            var published: CurrentUser? = null
            try {
                serverDao.addOrUpdateServer(server)
                val savedUser = serverDao.addOrUpdateUser(user)
                userPreferencesDataStore.updateData {
                    it
                        .toBuilder()
                        .apply {
                            currentServerId = server.id.toServerString()
                            currentUserId = savedUser.id.toServerString()
                        }.build()
                }
                val currentUser = CurrentUser(server, savedUser)
                commitSession {
                    // No suspension between this last authorization check and publication (plan 4.1, 4.3)
                    authorize?.invoke(currentUser)
                    // [published] records that the session changed even if a cancellation is delivered on the way back
                    assignSession(currentUser, userDto)
                    published = currentUser
                    if (ceilingBefore != null) {
                        lastPublication = Publication(currentUser, ceilingBefore, entryOwner, switchId.takeIf { entryOwner != null })
                    }
                }
            } catch (ex: Throwable) {
                if (switchId != null) {
                    withContext(NonCancellable) {
                        val publishedUser = published
                        if (publishedUser != null) {
                            // Published, then interrupted: the session is the destination's, so is its ceiling
                            settlePublished(switchId, ceilingBefore!!, publishedUser, entryOwner)
                        } else {
                            // Nothing was published: the previous session ceiling still applies
                            kidProtectionStore.abortSwitch(switchId)
                        }
                    }
                }
                throw ex
            }
            val currentUser = published!!
            if (switchId != null) {
                withContext(NonCancellable) { settlePublished(switchId, ceilingBefore!!, currentUser, entryOwner) }
            }
            return currentUser
        }

        /**
         * Kid profiles: the switch [switchId] published [published]. Without an [entryOwner] it completes, and the
         * session ceiling is the destination's from now on (plan 4.2). With one, the journal stays open until that
         * owner enters it (review round 7), recording what entering or withdrawing it applies.
         */
        private suspend fun settlePublished(
            switchId: String,
            ceilingBefore: ProfileCeiling,
            published: CurrentUser,
            entryOwner: Any?,
        ) {
            if (entryOwner == null) {
                kidProtectionStore.completeSwitch(switchId, published.user.profileCeiling)
            } else {
                kidProtectionStore.holdSwitch(switchId, ceilingBefore, published.user.profileCeiling)
            }
        }

        /**
         * Restores a session for the given server & user such as when the app reopens
         *
         * If user has a PIN, this returns false
         */
        suspend fun restoreSession(
            serverId: UUID?,
            userId: UUID?,
        ): CurrentUser? {
            if (serverId == null || userId == null) {
                sessionMutex.withLock { commitSession { assignSession(null, null) } }
                return null
            }
            val serverAndUsers =
                withContext(ioDispatcher) {
                    serverDao.getServer(serverId)
                } ?: return null
            if (_current.value.isIdentity(serverId, userId)) {
                restoreCurrentSession(serverId, userId)?.let { return it }
                // Kid profiles: another session was published meanwhile; never fall back to an ungated switch
                if (BuildConfig.KID_PROFILES_ENABLED) return null
            }
            val user = serverAndUsers.users.firstOrNull { it.id == userId } ?: return null
            return changeUser(serverAndUsers.server, user)
        }

        /**
         * The restore shortcut: re-applies the current session's credentials to the shared [ApiClient] if
         * [serverId]/[userId] is still the current session once the session lock is held, then (kid profiles)
         * refreshes its policy outside the lock.
         *
         * @return the session, or null (nothing changed) if another session was published meanwhile
         */
        suspend fun restoreCurrentSession(
            serverId: UUID,
            userId: UUID,
        ): CurrentUser? {
            sessionMutex.withLock {
                // Kid profiles (review round 6): a session nobody entered is withdrawn, never restored
                withdrawUnenteredLocked(null)
                commitSession {
                    _current.value?.takeIf { it.isIdentity(serverId, userId) }?.also {
                        Timber.v("Restoring session for current user, so shortcut")
                        apiClient.update(baseUrl = it.server.url, accessToken = it.user.accessToken)
                    }
                }
            } ?: return null
            if (BuildConfig.KID_PROFILES_ENABLED) {
                try {
                    updateUserDto()
                } catch (ex: Exception) {
                    // Keep the cached policy columns
                    Timber.w(ex, "Could not refresh the current user's policy")
                }
            }
            return _current.value?.takeIf { it.isIdentity(serverId, userId) }
        }

        suspend fun fetchLastUsedServer(serverId: UUID?): JellyfinServer? =
            withContext(ioDispatcher) {
                serverId?.let { serverDao.getServer(serverId)?.server }
            }

        /**
         * Kid profiles chooser teardown (plan 4.1): ends the in-memory session so account-bound work keyed on
         * [current] is cancelled and the shared [ApiClient] holds no token while the profile screen shows.
         * The saved current profile is cleared too (like [switchServerOrUser]); saved profiles, protection and the
         * session ceiling are untouched. A session published after this was called is left alone.
         */
        suspend fun endSessionForChooser() {
            val ending = _current.value
            sessionMutex.withLock {
                val ended =
                    commitSession {
                        _current.value.isSameIdentity(ending).also { if (it) assignSession(null, null) }
                    }
                if (ended) clearSavedSession(clearServer = true)
            }
        }

        /**
         * Kid profiles: withdraws [published], a session a chooser flow published but that flow was left before it
         * navigated into it (review round 4). Ends it like [endSessionForChooser], but only while [published] is still
         * the session that was published last; any later session is left alone. The session ceiling its switch set
         * goes too (review round 5): back to the one from before that switch, never higher (plan 4.1, 4.2), even if the
         * chooser teardown already ended the session.
         */
        suspend fun endSessionForChooser(published: CurrentUser) {
            sessionMutex.withLock {
                withContext(NonCancellable) {
                    val publication =
                        commitSession {
                            lastPublication?.takeIf { it.session === published }?.also { lastPublication = null }
                        }
                    withdrawLocked(published, publication)
                }
            }
        }

        /**
         * Kid profiles (review round 6): withdraws the last published session if it is still waiting to be entered
         * by an owner other than [owner] (a chooser flow left before it navigated into it), like
         * [endSessionForChooser]: its session ends and the session ceiling goes back to the one from before its
         * switch. Called before a gate evaluation, so nothing is ever evaluated against that session's ceiling.
         */
        suspend fun withdrawUnentered(owner: Any? = null) {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            sessionMutex.withLock { withdrawUnenteredLocked(owner) }
        }

        /** [withdrawUnentered]; the caller holds [sessionMutex] */
        private suspend fun withdrawUnenteredLocked(owner: Any?) {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            // An entry its owner hasn't recorded yet is recorded first (review round 7)
            finishEntryLocked()
            // Taken off [lastPublication] in one main-thread step, so it can't be entered any more, and then
            // withdrawn even if the caller is cancelled meanwhile
            withContext(NonCancellable) {
                val publication =
                    commitSession {
                        lastPublication?.takeIf { !it.entered && it.owner !== owner }?.also { lastPublication = null }
                    } ?: return@withContext
                Timber.i("Withdrawing a published session nobody entered before the next switch")
                withdrawLocked(publication.session, publication)
            }
        }

        /**
         * Withdraws [published]: [publication]'s ceiling from before its switch first (never raising the stored one),
         * so no later switch is gated against the withdrawn session's ceiling, then the session itself if it is still
         * the one published last. The caller holds [sessionMutex] and has taken [publication] off [lastPublication].
         */
        private suspend fun withdrawLocked(
            published: CurrentUser,
            publication: Publication?,
        ) {
            // Review round 7: its switch's journal, if still held open, closes by its id
            if (publication != null) kidProtectionStore.withdrawSwitch(publication.ceilingBefore, publication.heldSwitchId)
            val ended =
                commitSession {
                    (publishedSession === published && _current.value != null).also { if (it) assignSession(null, null) }
                }
            if (ended) clearSavedSession(clearServer = true)
        }

        /**
         * Kid profiles (review round 6): [owner] enters [published], the session its switch published with that
         * [owner]. Called on the main thread, in the very step that navigates into it.
         *
         * @return whether it may navigate: false if [published] was withdrawn or replaced meanwhile
         */
        fun markEntered(
            owner: Any,
            published: CurrentUser,
        ): Boolean {
            if (!BuildConfig.KID_PROFILES_ENABLED) return true
            val publication = lastPublication?.takeIf { it.session === published && it.owner === owner } ?: return false
            publication.entered = true
            return true
        }

        /**
         * Kid profiles (review round 7): records durably that the session [markEntered] marked entered was entered,
         * closing its switch's journal with the published profile's ceiling. Until then the session ceiling reads
         * unknown (gated). The owner calls this right after the step that navigated, even if it is cancelled; the next
         * withdrawal check ([withdrawUnentered], before every switch and gate evaluation) records it too, so an entered
         * session never stays journalled while the app runs.
         */
        suspend fun finishEntry() {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            sessionMutex.withLock { finishEntryLocked() }
        }

        /** [finishEntry]; the caller holds [sessionMutex] */
        private suspend fun finishEntryLocked() {
            withContext(NonCancellable) {
                val switchId =
                    commitSession {
                        lastPublication?.takeIf { it.entered }?.let { publication ->
                            publication.heldSwitchId.also { publication.heldSwitchId = null }
                        }
                    } ?: return@withContext
                kidProtectionStore.enterSwitch(switchId)
            }
        }

        /** Clears the saved current profile (and server); the caller holds [sessionMutex] */
        private suspend fun clearSavedSession(clearServer: Boolean) {
            userPreferencesDataStore.updateData {
                it
                    .toBuilder()
                    .apply {
                        if (clearServer) currentServerId = ""
                        currentUserId = ""
                    }.build()
            }
        }

        /**
         * Given a successful [AuthenticationResult], switch to the user that just authenticated
         */
        suspend fun changeUser(
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            existingUser: JellyfinUser?,
            displayName: String? = null,
            newPin: String? = null,
            beforePublish: (suspend (JellyfinUser) -> Unit)? = null,
            authorize: ((CurrentUser) -> Unit)? = null,
            entryOwner: Any? = null,
        ) = withContext(ioDispatcher) {
            val accessToken = authenticationResult.accessToken
            if (accessToken != null) {
                val authedUser = authenticationResult.user
                val server =
                    authenticationResult.serverId?.toUUIDOrNull()?.let {
                        JellyfinServer(
                            id = it,
                            name = authedUser?.serverName,
                            url = serverUrl,
                            null,
                        )
                    }
                if (server != null) {
                    val savedUser =
                        if (!BuildConfig.KID_PROFILES_ENABLED) {
                            existingUser
                        } else {
                            // Kid profiles: the selected profile is reused only if it is the one that just
                            // authenticated; anyone else gets their own saved row (keeping its PIN) or a new one
                            existingUser.ifSameIdentity(server.id, authedUser?.id)
                                ?: authedUser?.let { serverDao.getUser(server.id, it.id) }
                        }
                    val user =
                        authedUser?.let {
                            if (savedUser != null) {
                                Timber.d("Re-using existing user")
                                savedUser.copy(
                                    // Upstream: authenticating via the server always removes the PIN.
                                    // Kid profiles keep it, so re-auth can't strip a parent's protection.
                                    pin = newPin ?: if (BuildConfig.KID_PROFILES_ENABLED) savedUser.pin else null,
                                    accessToken = accessToken,
                                    lastUsed = ZonedDateTime.now(),
                                    displayName = displayName ?: savedUser.displayName,
                                )
                            } else {
                                Timber.d("Creating new user")
                                JellyfinUser(
                                    id = it.id,
                                    name = it.name,
                                    serverId = server.id,
                                    accessToken = accessToken,
                                    pin = newPin,
                                    lastUsed = ZonedDateTime.now(),
                                    displayName = displayName,
                                )
                            }
                        }
                    if (user != null) {
                        return@withContext changeUser(server, user, beforePublish, authorize, entryOwner)
                    } else {
                        throw IllegalArgumentException("Authentication result's user was null")
                    }
                } else {
                    throw IllegalArgumentException("Authentication result's serverId not valid: ${authenticationResult.serverId}")
                }
            } else {
                throw IllegalArgumentException("Authentication result's access token was null")
            }
        }

        /**
         * Removes [user] from this TV, ending the session if it is theirs. [authorize] runs first in the coordinator's
         * main-thread step and may throw to refuse the removal (kid profiles: the chooser flow that asked was left).
         */
        suspend fun removeUser(
            user: JellyfinUser,
            authorize: (() -> Unit)? = null,
        ) {
            sessionMutex.withLock {
                // Ends the session only if it still is this user's
                val ended =
                    commitSession {
                        authorize?.invoke()
                        (_current.value?.user?.id == user.id).also { if (it) assignSession(null, null) }
                    }
                if (ended) clearSavedSession(clearServer = false)
            }
            withContext(ioDispatcher) {
                serverDao.deleteUser(user.serverId, user.id)
            }
        }

        suspend fun removeServer(server: JellyfinServer) {
            sessionMutex.withLock {
                val ended =
                    commitSession {
                        (_current.value?.server?.id == server.id).also { if (it) assignSession(null, null, baseUrl = null) }
                    }
                if (ended) clearSavedSession(clearServer = true)
            }
            withContext(ioDispatcher) {
                serverDao.deleteServer(server.id)
            }
        }

        suspend fun switchServerOrUser() {
            val leaving = _current.value
            sessionMutex.withLock {
                // A session published after this was called keeps its saved pointer
                if (_current.value.isSameIdentity(leaving)) clearSavedSession(clearServer = true)
            }
        }

        suspend fun updateUserAuth(
            user: JellyfinUser,
            pin: String?,
            requireLogin: Boolean,
        ) {
            if (BuildConfig.KID_PROFILES_ENABLED && user.hasPin && pin.isNullOrBlank() && kidProtectionStore.isProtected()) {
                // Plan 4.2: PIN removal is blocked on a protected device
                throw IllegalStateException("The PIN can't be removed while kid protection is on")
            }
            val newUser = user.copy(pin = pin, requireLogin = requireLogin)
            val updatedUser = serverDao.addOrUpdateUser(newUser)
            sessionMutex.withLock {
                commitSession {
                    // Updating current user, so push out the change
                    _current.update { if (it.isIdentity(user.serverId, updatedUser.id)) it?.copy(user = updatedUser) else it }
                }
            }
        }

        suspend fun authorizeQuickConnect(code: String): Boolean =
            withContext(ioDispatcher) {
                val userId = current.value?.user?.id
                if (userId == null) {
                    Timber.e("No user logged in for Quick Connect authorization")
                    throw IllegalStateException("Must be logged in to authorize Quick Connect")
                }
                val response = apiClient.quickConnectApi.authorizeQuickConnect(code, userId)
                response.content
            }

        /**
         * Update [currentUserDto] by querying the server
         */
        suspend fun updateUserDto() {
            val userDto by apiClient.userApi.getCurrentUser()
            updateUserDto(userDto)
            if (BuildConfig.KID_PROFILES_ENABLED) {
                refreshCurrentPolicy(userDto)
            }
        }

        /**
         * Kid profiles: record a freshly fetched policy of the current user in Room (and the session ceiling,
         * so a level changed on the portal applies to this session)
         */
        private suspend fun refreshCurrentPolicy(userDto: UserDto) {
            if (userDto.policy == null) return
            // Under the switch lock: a refresh of the previous session never writes its ceiling over a newer switch
            sessionMutex.withLock {
                val cur = _current.value ?: return
                if (cur.user.id != userDto.id) return
                val refreshed = cur.user.withPolicyOf(userDto)
                if (refreshed.policyState == cur.user.policyState && refreshed.maxParentalRating == cur.user.maxParentalRating) {
                    return
                }
                // Protection first, so the Room row never says kid on an unprotected device
                recordSessionCeiling(refreshed)
                withContext(ioDispatcher) {
                    serverDao.updateUserPolicy(refreshed.rowId, refreshed.maxParentalRating, refreshed.policyState)
                }
                commitSession {
                    _current.update { if (it?.user?.id == refreshed.id) it.copy(user = refreshed) else it }
                }
            }
        }

        private suspend fun recordSessionCeiling(user: JellyfinUser) {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            val ceiling = user.profileCeiling
            if (user.profileKind != ProfileKind.ADULT) {
                // First kid (or a profile we can't classify) admitted: this TV is protected from now on
                kidProtectionStore.markProtected(ceiling)
            }
            kidProtectionStore.setSessionCeiling(ceiling)
        }

        /**
         * Update [currentUserDto] with the specified [UserDto]
         *
         * This will only update if the [UserDto] is for the [currentUser] and replaces that same user's [UserDto], so a
         * late refresh never lands over a newer session's policy (or an ended session). Goes through the session
         * coordinator (review round 4): the same-user check and the update are one main-thread step under the
         * session lock, so they can't straddle a switch.
         */
        suspend fun updateUserDto(userDto: UserDto) {
            sessionMutex.withLock {
                commitSession {
                    _currentUserDto.update {
                        if (_current.value?.user?.id == userDto.id && it?.id == userDto.id) userDto else it
                    }
                }
            }
        }

        companion object {
            fun getServerSharedPreferences(context: Context): SharedPreferences =
                context.getSharedPreferences(
                    "${context.packageName}_server",
                    Context.MODE_PRIVATE,
                )

            const val SERVER_URL_KEY = "current.server"
            const val ACCESS_TOKEN_KEY = "current.accessToken"
        }
    }

/**
 * [this] profile only if it is the identity that authenticated ([serverId] and [userId] match), else null
 */
fun JellyfinUser?.ifSameIdentity(
    serverId: UUID?,
    userId: UUID?,
): JellyfinUser? = this?.takeIf { serverId != null && userId != null && it.serverId == serverId && it.id == userId }

/** Whether [this] session is [serverId]/[userId]'s */
fun CurrentUser?.isIdentity(
    serverId: UUID,
    userId: UUID,
): Boolean = this != null && server.id == serverId && user.id == userId

/** Whether [this] and [other] are the same profile's session, or both no session */
fun CurrentUser?.isSameIdentity(other: CurrentUser?): Boolean =
    if (this == null || other == null) this == other else isIdentity(other.server.id, other.user.id)

/**
 * The saved profile updated with the server policy in [userDto]. A missing policy keeps the cached values.
 */
fun JellyfinUser.withPolicyOf(userDto: UserDto): JellyfinUser {
    val policy = userDto.policy ?: return this
    val maxRating = policy.maxParentalRating
    return copy(
        maxParentalRating = maxRating,
        policyState = if (maxRating != null) PolicyState.KID else PolicyState.ADULT,
    )
}

/**
 * A session a switch published and the session ceiling from before that switch. With an [owner], it is [entered]
 * only once that owner navigated into it (review round 6), and [heldSwitchId] is its switch while that journal is still
 * held open (review round 7); only read or changed on the main thread.
 */
private class Publication(
    val session: CurrentUser,
    val ceilingBefore: ProfileCeiling,
    val owner: Any?,
    var heldSwitchId: String?,
) {
    var entered: Boolean = owner == null
}

@Serializable
data class CurrentUser(
    val server: JellyfinServer,
    val user: JellyfinUser,
)
