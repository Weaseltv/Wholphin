package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/*
 * The account PIN (contract 11.2). A profile's PIN belongs to its Jellyfin user, in the DisplayPreferences
 * [AccountPinPrefs.ID] of client [AccountPinPrefs.CLIENT], so it follows the person to every TV and the website can
 * reset it. The TV's saved PIN (Room `users.pin`) is a cache of it. Every profile reads and writes its own with its own
 * saved token, never the shared ApiClient's.
 */

/** Where the account PIN is stored (contract 11.2) */
object AccountPinPrefs {
    const val ID = "weaselplex-profile"
    const val CLIENT = "weaselplex"
    const val KEY_PIN = "pin"
    const val KEY_UPDATED_AT = "pinUpdatedAt"
}

/** The account PIN as read: [pin] empty or missing = no PIN; [updatedAt] missing = never set on the account */
data class AccountPinValue(
    val pin: String?,
    val updatedAt: String?,
)

/** What a pulled account PIN means for the TV's cached one */
sealed interface PinSync {
    /** Nothing to change */
    data object Keep : PinSync

    /** The cache becomes [pin] (null: no PIN) */
    data class Cache(
        val pin: String?,
    ) : PinSync

    /** The account never had a PIN: upload the cached [pin] (one-time migration) */
    data class Upload(
        val pin: String,
    ) : PinSync
}

object AccountPinRules {
    /**
     * Contract 11.2: a PIN on the account replaces the cache. No PIN on the account but one cached: if the account never
     * had one ([AccountPinValue.updatedAt] missing) the cached PIN is uploaded, once; if it was removed elsewhere, the
     * cache follows, except on a kid-protected TV, which never drops a PIN (plan 4.2).
     */
    fun sync(
        account: AccountPinValue,
        cached: String?,
        deviceProtected: Boolean,
    ): PinSync {
        val accountPin = account.pin?.takeIf { it.isNotBlank() }
        val cachedPin = cached?.takeIf { it.isNotBlank() }
        return when {
            accountPin != null -> if (accountPin == cached) PinSync.Keep else PinSync.Cache(accountPin)
            cachedPin == null -> PinSync.Keep
            account.updatedAt.isNullOrBlank() -> PinSync.Upload(cachedPin)
            deviceProtected -> PinSync.Keep
            else -> PinSync.Cache(null)
        }
    }
}

/** The account PIN on the server, with one profile's own token */
interface AccountPinRemote {
    /** Throws when the server can't be reached or refuses */
    suspend fun read(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
    ): AccountPinValue

    /** Merges [pin] (null: no PIN) and [updatedAt] into the profile's prefs, leaving other keys alone; throws on failure */
    suspend fun write(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String?,
        updatedAt: String,
    )

    /**
     * The one-time migration (review round 1): [write], but only if the write's own fresh read finds no PIN and no
     * `pinUpdatedAt` (the account never had a PIN). Returns null when written, else what it found, unchanged.
     */
    suspend fun writeIfUnset(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String,
        updatedAt: String,
    ): AccountPinValue?

    /**
     * Create-only (contract 12.4, review round 1): [write], but only if the write's own fresh read finds no PIN on the
     * account, and only if [mayWrite] still says yes right before the write itself. Throws when the account can't be read
     * or written.
     */
    suspend fun writeIfNoPin(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
        pin: String,
        updatedAt: String,
        mayWrite: () -> Boolean,
    ): CreateOnlyWrite
}

/** What [AccountPinRemote.writeIfNoPin] did */
sealed interface CreateOnlyWrite {
    data object Written : CreateOnlyWrite

    /** The account already has [found]'s PIN; nothing was written */
    data class HasPin(
        val found: AccountPinValue,
    ) : CreateOnlyWrite

    /** Its `mayWrite` said no; nothing was written */
    data object Refused : CreateOnlyWrite
}

/** Whether the account ever had a PIN: one now, or a time it was last set or removed */
val AccountPinValue.everSet: Boolean get() = !pin.isNullOrBlank() || !updatedAt.isNullOrBlank()

/** Result of setting, changing or removing a PIN through [AccountPins.set] */
sealed interface AccountPinWrite {
    data object Saved : AccountPinWrite

    /** The account couldn't be written: nothing changed, on the server or on this TV */
    data object Failed : AccountPinWrite

    /** A kid-protected TV never removes a PIN (plan 4.2): nothing changed */
    data object RemovalBlocked : AccountPinWrite
}

/** Result of [AccountPins.removeWithoutKids] */
enum class PinRemoval {
    REMOVED,

    /** A kid profile is saved on this TV: every grown-up keeps a PIN, nothing changed */
    KIDS_ON_TV,

    /** The account couldn't be written: nothing changed */
    FAILED,
}

/** Result of [AccountPins.createIfNoPin] */
enum class PinCreate {
    /** Written to the account, then the cache */
    CREATED,

    /** The account (or the cache) already has a PIN, which the cache now holds; nothing was written */
    ALREADY_SET,

    /** No longer a saved adult with a token and no PIN; nothing was written */
    NOT_ELIGIBLE,

    /** The authorization ended before the account (or the cache) was written */
    REFUSED,

    /** The account couldn't be read or written: nothing changed */
    FAILED,
}

/**
 * The one place a profile PIN is pulled, set, changed or removed (contract 11.2). Pulls are best effort with a short
 * timeout; the cached PIN is used when the server can't be reached. Writes go to the account first and change nothing
 * on this TV if that fails. One profile's pulls and writes never interleave.
 */
@Singleton
class AccountPins
    @Inject
    constructor(
        private val remote: AccountPinRemote,
        private val serverDao: JellyfinServerDao,
        private val serverRepository: ServerRepository,
        private val store: KidProtectionStore,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** Exposed for tests */
        internal var clock: () -> Instant = Instant::now

        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(
            serverId: UUID,
            userId: UUID,
        ): Mutex = locks.getOrPut("$serverId/$userId") { Mutex() }

        private val enabled: Boolean get() = BuildConfig.KID_PROFILES_ENABLED

        /**
         * Pulls [user]'s account PIN into the cache: when the select-user page opens, before a PIN prompt shows, and once
         * after a wrong entry. Returns the saved row afterwards (with the cached PIN if nothing could be pulled), or
         * [user] if it isn't saved.
         */
        suspend fun refresh(user: JellyfinUser): JellyfinUser {
            if (!enabled) return user
            return lockFor(user.serverId, user.id).withLock {
                val row = savedRow(user) ?: return@withLock user
                val token = row.accessToken ?: return@withLock row
                val url = serverUrl(row) ?: return@withLock row
                val account = pull(url, row.id, token) ?: return@withLock row
                when (val sync = AccountPinRules.sync(account, row.pin, store.isProtected())) {
                    PinSync.Keep -> {
                        row
                    }

                    is PinSync.Cache -> {
                        cache(row, sync.pin)
                    }

                    is PinSync.Upload -> {
                        Timber.i("Uploading profile %s's PIN to its account", row.rowId)
                        // Review round 1: only while the account still never had a PIN; a PIN saved meanwhile (website,
                        // another TV) is adopted instead of overwritten
                        when (val found = migrate(url, row.id, token, sync.pin)) {
                            null -> {
                                row
                            }

                            else -> {
                                val adopt = AccountPinRules.sync(found, row.pin, store.isProtected())
                                if (adopt is PinSync.Cache) cache(row, adopt.pin) else row
                            }
                        }
                    }
                }
            }
        }

        /** The cache becomes [pin]; returns the saved row afterwards */
        private suspend fun cache(
            row: JellyfinUser,
            pin: String?,
        ): JellyfinUser {
            serverRepository.updateCachedPin(row, pin)
            return savedRow(row) ?: row
        }

        /**
         * The account PIN of a profile that isn't published yet, with the token of its fresh sign-in (review round 1: a
         * sign-in reads it before deciding on PIN setup). Null when the account can't be reached.
         */
        suspend fun readAccount(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
        ): AccountPinValue? = if (!enabled) AccountPinValue(null, null) else pull(serverUrl, userId, accessToken)

        /** [refresh] for every saved profile of [serverId] (the select-user page opened) */
        suspend fun refreshAll(serverId: UUID) {
            if (!enabled) return
            val rows = withContext(ioDispatcher) { serverDao.getServer(serverId)?.users.orEmpty() }
            coroutineScope {
                rows.filter { it.accessToken != null }.map { async { refresh(it) } }.awaitAll()
            }
        }

        /**
         * Sets [user]'s PIN (null removes it) and login requirement. The account is written first; if that fails,
         * nothing changes. A login requirement alone is this TV's business, so removing a PIN that isn't there writes
         * only the TV.
         */
        suspend fun set(
            user: JellyfinUser,
            pin: String?,
            requireLogin: Boolean,
        ): AccountPinWrite {
            if (!enabled) {
                serverRepository.updateUserAuth(user, pin, requireLogin)
                return AccountPinWrite.Saved
            }
            return lockFor(user.serverId, user.id).withLock {
                val row = savedRow(user) ?: user
                val newPin = pin?.takeIf { it.isNotBlank() }
                if (newPin == null && row.hasPin && store.isProtected()) return@withLock AccountPinWrite.RemovalBlocked
                if (newPin != null || row.hasPin) {
                    val token = row.accessToken ?: return@withLock AccountPinWrite.Failed
                    val url = serverUrl(row) ?: return@withLock AccountPinWrite.Failed
                    if (!push(url, row.id, token, newPin)) return@withLock AccountPinWrite.Failed
                }
                serverRepository.updateUserAuth(user, newPin, requireLogin)
                AccountPinWrite.Saved
            }
        }

        /** Whether a kid profile is saved on this TV (any server) */
        suspend fun kidsOnTv(): Boolean = withContext(ioDispatcher) { serverDao.countKidUsers() } > 0

        /**
         * Chooser "Remove PIN" (owner 2026-10-25), once [user] entered their PIN: only while no kid profile is saved on
         * this TV. A TV that had kids stays kid-protected after they leave (plan 4.2); with none left that goes off first,
         * as Settings' "Turn off kid protection" does, then the account's PIN is removed. Another TV with kids keeps its
         * cached PIN ([AccountPinRules.sync]).
         */
        suspend fun removeWithoutKids(user: JellyfinUser): PinRemoval {
            if (kidsOnTv()) return PinRemoval.KIDS_ON_TV
            if (store.isProtected() && !store.clearProtection()) return PinRemoval.FAILED
            // A kid added meanwhile protects the TV again, which blocks the removal
            return when (set(user, null, user.requireLogin)) {
                AccountPinWrite.Saved -> PinRemoval.REMOVED
                AccountPinWrite.RemovalBlocked -> PinRemoval.KIDS_ON_TV
                AccountPinWrite.Failed -> PinRemoval.FAILED
            }
        }

        /**
         * Contract 12.4 "Create PIN" for another adult (review round 1): create-only. Under [user]'s write lock it checks
         * again that [user] is still a saved adult with a token and no cached PIN; the write's own fresh read of the account
         * must find no PIN (one found is adopted into the cache instead, [PinCreate.ALREADY_SET]); a read or write that
         * fails changes nothing ([PinCreate.FAILED]). [authorize] is checked right before the account is written and again
         * before the cache is: once it says no, nothing more is written ([PinCreate.REFUSED]).
         */
        suspend fun createIfNoPin(
            user: JellyfinUser,
            pin: String,
            authorize: () -> Boolean,
        ): PinCreate {
            if (!enabled) return PinCreate.FAILED
            return lockFor(user.serverId, user.id).withLock {
                val row = savedRow(user) ?: return@withLock PinCreate.NOT_ELIGIBLE
                if (row.profileKind != ProfileKind.ADULT) return@withLock PinCreate.NOT_ELIGIBLE
                if (row.hasPin) return@withLock PinCreate.ALREADY_SET
                val token = row.accessToken ?: return@withLock PinCreate.NOT_ELIGIBLE
                val url = serverUrl(row) ?: return@withLock PinCreate.FAILED
                if (!authorize()) return@withLock PinCreate.REFUSED
                val written =
                    try {
                        withTimeout(WRITE_TIMEOUT) { remote.writeIfNoPin(url, row.id, token, pin, clock().toString(), authorize) }
                    } catch (ex: TimeoutCancellationException) {
                        Timber.w(ex, "Account PIN create timed out")
                        return@withLock PinCreate.FAILED
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Account PIN create failed")
                        return@withLock PinCreate.FAILED
                    }
                when (written) {
                    CreateOnlyWrite.Written -> {
                        // The account has it now; the TV's cache only while the authorization still holds
                        if (!authorize()) return@withLock PinCreate.REFUSED
                        serverRepository.updateUserAuth(row, pin, row.requireLogin)
                        PinCreate.CREATED
                    }

                    is CreateOnlyWrite.HasPin -> {
                        Timber.i("Profile %s's account already has a PIN; adopting it", row.rowId)
                        cache(row, written.found.pin)
                        PinCreate.ALREADY_SET
                    }

                    CreateOnlyWrite.Refused -> {
                        PinCreate.REFUSED
                    }
                }
            }
        }

        /**
         * Writes the account PIN of a profile that isn't saved on this TV yet (an adult's sign-in held back for a PIN),
         * before that sign-in saves it. Returns whether it was written.
         */
        suspend fun upload(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
            pin: String,
        ): Boolean = !enabled || push(serverUrl, userId, accessToken, pin)

        private suspend fun pull(
            url: String,
            userId: UUID,
            token: String,
        ): AccountPinValue? =
            try {
                withTimeoutOrNull(PULL_TIMEOUT) { remote.read(url, userId, token) }
                    .also { if (it == null) Timber.i("Account PIN pull timed out; using the cached PIN") }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Account PIN pull failed; using the cached PIN")
                null
            }

        private suspend fun push(
            url: String,
            userId: UUID,
            token: String,
            pin: String?,
        ): Boolean =
            try {
                withTimeout(WRITE_TIMEOUT) { remote.write(url, userId, token, pin, clock().toString()) }
                true
            } catch (ex: TimeoutCancellationException) {
                Timber.w(ex, "Account PIN write timed out")
                false
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Account PIN write failed")
                false
            }

        /**
         * The one-time migration of a cached [pin]: returns null when it was written (or couldn't be, best effort), else
         * the account's PIN found in the write's own fresh read, which was left alone
         */
        private suspend fun migrate(
            url: String,
            userId: UUID,
            token: String,
            pin: String,
        ): AccountPinValue? =
            try {
                withTimeout(WRITE_TIMEOUT) { remote.writeIfUnset(url, userId, token, pin, clock().toString()) }
            } catch (ex: TimeoutCancellationException) {
                Timber.w(ex, "Account PIN migration timed out")
                null
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Account PIN migration failed")
                null
            }

        private suspend fun savedRow(user: JellyfinUser): JellyfinUser? =
            withContext(ioDispatcher) { serverDao.getUser(user.serverId, user.id) }

        private suspend fun serverUrl(user: JellyfinUser): String? =
            withContext(ioDispatcher) { serverDao.getServer(user.serverId)?.server?.url }

        companion object {
            /** Best effort: a prompt waits at most this long for the account's PIN */
            val PULL_TIMEOUT = 3.seconds
            val WRITE_TIMEOUT = 10.seconds
        }
    }

/** [AccountPinRemote] over Jellyfin's DisplayPreferences (GET, merge, POST), as [AccountPinPrefs] says */
@Singleton
class JellyfinAccountPinRemote
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
    ) : AccountPinRemote {
        private fun api(
            serverUrl: String,
            accessToken: String,
        ) = jellyfin.createApi(
            baseUrl = serverUrl,
            accessToken = accessToken,
            httpClientOptions =
                HttpClientOptions(
                    requestTimeout = TIMEOUT,
                    connectTimeout = TIMEOUT,
                    socketTimeout = TIMEOUT,
                ),
        )

        override suspend fun read(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
        ): AccountPinValue =
            withContext(WholphinDispatchers.IO) {
                val prefs =
                    api(serverUrl, accessToken)
                        .displayPreferencesApi
                        .getDisplayPreferences(
                            displayPreferencesId = AccountPinPrefs.ID,
                            userId = userId,
                            client = AccountPinPrefs.CLIENT,
                        ).content
                AccountPinValue(prefs.customPrefs[AccountPinPrefs.KEY_PIN], prefs.customPrefs[AccountPinPrefs.KEY_UPDATED_AT])
            }

        override suspend fun write(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
            pin: String?,
            updatedAt: String,
        ) {
            merge(serverUrl, userId, accessToken, pin, updatedAt, onlyIfUnset = false)
        }

        override suspend fun writeIfUnset(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
            pin: String,
            updatedAt: String,
        ): AccountPinValue? = merge(serverUrl, userId, accessToken, pin, updatedAt, onlyIfUnset = true)

        override suspend fun writeIfNoPin(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
            pin: String,
            updatedAt: String,
            mayWrite: () -> Boolean,
        ): CreateOnlyWrite {
            var refused = false
            val found =
                merge(serverUrl, userId, accessToken, pin, updatedAt, onlyIfUnset = false) { found ->
                    when {
                        !found.pin.isNullOrBlank() -> {
                            false
                        }

                        !mayWrite() -> {
                            refused = true
                            false
                        }

                        else -> {
                            true
                        }
                    }
                }
            return when {
                found == null -> CreateOnlyWrite.Written
                refused -> CreateOnlyWrite.Refused
                else -> CreateOnlyWrite.HasPin(found)
            }
        }

        /**
         * GET, merge, POST; with [onlyIfUnset], returns what the GET found instead of writing when the account had a PIN,
         * and likewise when [writeIf] (given what the GET found) says no
         */
        private suspend fun merge(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
            pin: String?,
            updatedAt: String,
            onlyIfUnset: Boolean,
            writeIf: (AccountPinValue) -> Boolean = { true },
        ): AccountPinValue? =
            withContext(WholphinDispatchers.IO) {
                val api = api(serverUrl, accessToken).displayPreferencesApi
                val current =
                    api
                        .getDisplayPreferences(
                            displayPreferencesId = AccountPinPrefs.ID,
                            userId = userId,
                            client = AccountPinPrefs.CLIENT,
                        ).content
                val found =
                    AccountPinValue(current.customPrefs[AccountPinPrefs.KEY_PIN], current.customPrefs[AccountPinPrefs.KEY_UPDATED_AT])
                if (onlyIfUnset && found.everSet) return@withContext found
                if (!writeIf(found)) return@withContext found
                val customPrefs =
                    current.customPrefs.toMutableMap().apply {
                        put(AccountPinPrefs.KEY_PIN, pin.orEmpty())
                        put(AccountPinPrefs.KEY_UPDATED_AT, updatedAt)
                    }
                api.updateDisplayPreferences(
                    displayPreferencesId = AccountPinPrefs.ID,
                    userId = userId,
                    client = AccountPinPrefs.CLIENT,
                    data = current.copy(customPrefs = customPrefs),
                )
                null
            }

        private companion object {
            val TIMEOUT = 5.seconds
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountPinModule {
    @Binds
    abstract fun accountPinRemote(impl: JellyfinAccountPinRemote): AccountPinRemote
}
