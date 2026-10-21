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
         * (eg an unlock that expired meanwhile): nothing is published and the journalled switch is aborted.
         */
        suspend fun changeUser(
            server: JellyfinServer,
            user: JellyfinUser,
            beforePublish: (suspend (JellyfinUser) -> Unit)? = null,
            authorize: (() -> Unit)? = null,
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
                        publishSwitch(updatedServer, updatedUser, userDto, authorize).also {
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
         */
        private suspend fun publishSwitch(
            server: JellyfinServer,
            user: JellyfinUser,
            userDto: UserDto,
            authorize: (() -> Unit)?,
        ): CurrentUser {
            val kidProfiles = BuildConfig.KID_PROFILES_ENABLED
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
                    authorize?.invoke()
                    // [published] records that the session changed even if a cancellation is delivered on the way back
                    assignSession(currentUser, userDto)
                    published = currentUser
                }
            } catch (ex: Throwable) {
                if (switchId != null) {
                    withContext(NonCancellable) {
                        val publishedUser = published
                        if (publishedUser != null) {
                            // Published, then interrupted: the session is the destination's, so is its ceiling
                            kidProtectionStore.completeSwitch(switchId, publishedUser.user.profileCeiling)
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
                // The switch completed: the session ceiling is the destination's (plan 4.2)
                withContext(NonCancellable) { kidProtectionStore.completeSwitch(switchId, currentUser.user.profileCeiling) }
            }
            return currentUser
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
            authorize: (() -> Unit)? = null,
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
                        return@withContext changeUser(server, user, beforePublish, authorize)
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

        suspend fun removeUser(user: JellyfinUser) {
            sessionMutex.withLock {
                // Ends the session only if it still is this user's
                val ended =
                    commitSession {
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
         * late refresh never lands over a newer session's policy (or an ended session)
         */
        fun updateUserDto(userDto: UserDto) {
            _currentUserDto.update {
                if (currentUser?.id == userDto.id && it?.id == userDto.id) userDto else it
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

@Serializable
data class CurrentUser(
    val server: JellyfinServer,
    val user: JellyfinUser,
)
