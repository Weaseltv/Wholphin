package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/*
 * Contract 12.5: when the select-user page opens, every saved profile with a token asks the server who it is
 * (`GET /Users/Me` with its own token). A kid answered with 401 (its media user was deleted, or its sessions were ended
 * from the website) leaves this TV. Every answer also brings the profile's picture tag, which the chooser puts in the
 * image URL so a picture changed on the website or another TV shows.
 */

/** What `GET /Users/Me` with a saved profile's own token said (contract 12.5) */
sealed interface ProfileStatus {
    /** The token works; [primaryImageTag] is the profile's picture now (null: none) */
    data class Active(
        val primaryImageTag: String?,
    ) : ProfileStatus

    /** HTTP 401: the user is gone, or its sessions were ended */
    data object SignedOut : ProfileStatus

    /** A network error or any other status: nothing changes */
    data object Unknown : ProfileStatus
}

fun interface ProfileStatusRemote {
    /** Never throws but for cancellation */
    suspend fun status(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
    ): ProfileStatus
}

/** What [SavedProfileChecks.check] found: the picture tags of the profiles that answered, and the kids it removed */
data class ProfileCheckResult(
    val imageTags: Map<UUID, String?> = emptyMap(),
    val removed: Set<UUID> = emptySet(),
)

@Singleton
class SavedProfileChecks
    @Inject
    constructor(
        private val remote: ProfileStatusRemote,
        private val serverRepository: ServerRepository,
        private val pictures: ProfilePictureService,
    ) {
        /**
         * Checks every profile in [users] (a server's saved profiles) that has a token, in parallel. Kids that got a 401
         * are removed from this TV with their cached PIN and picture; adults never are, and kid protection stays on.
         */
        suspend fun check(
            serverUrl: String,
            users: List<JellyfinUser>,
        ): ProfileCheckResult {
            if (!BuildConfig.KID_PROFILES_ENABLED) return ProfileCheckResult()
            val statuses =
                coroutineScope {
                    users
                        .mapNotNull { user -> user.accessToken?.let { token -> user to token } }
                        .map { (user, token) ->
                            async {
                                val status = withTimeoutOrNull(TIMEOUT) { remote.status(serverUrl, user.id, token) }
                                user to (status ?: ProfileStatus.Unknown)
                            }
                        }.awaitAll()
                }
            val removed =
                statuses
                    .filter { (user, status) -> status == ProfileStatus.SignedOut && user.profileKind == ProfileKind.KID }
                    .mapNotNull { (user, _) -> user.id.takeIf { removeKid(user) } }
                    .toSet()
            val tags =
                statuses
                    .mapNotNull { (user, status) -> (status as? ProfileStatus.Active)?.let { user.id to it.primaryImageTag } }
                    .toMap()
            return ProfileCheckResult(tags, removed)
        }

        /**
         * Removes [kid] from this TV if its saved row is still this kid with this token and isn't the current session's
         * (review round 1: one step under the session lock); its cached picture state goes only if it was removed
         */
        private suspend fun removeKid(kid: JellyfinUser): Boolean =
            try {
                Timber.i("Kid profile %s was signed out on the server; removing it from this TV", kid.rowId)
                serverRepository.removeSignedOutKid(kid).also { removed ->
                    if (removed) pictures.forget(kid.id) else Timber.i("Kid profile %s changed meanwhile; kept", kid.rowId)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't remove kid profile %s", kid.rowId)
                false
            }

        companion object {
            /** Best effort: the chooser never waits long for an answer */
            val TIMEOUT = 6.seconds
        }
    }

/** A failed `GET /Users/Me`: only HTTP 401 means the profile was signed out; anything else changes nothing */
fun profileStatusOf(error: Exception): ProfileStatus =
    if (error is InvalidStatusException && error.status == 401) ProfileStatus.SignedOut else ProfileStatus.Unknown

/** [ProfileStatusRemote] over Jellyfin, with the profile's own token and short timeouts */
@Singleton
class JellyfinProfileStatusRemote
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
    ) : ProfileStatusRemote {
        override suspend fun status(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
        ): ProfileStatus =
            try {
                withContext(WholphinDispatchers.IO) {
                    val api =
                        jellyfin.createApi(
                            baseUrl = serverUrl,
                            accessToken = accessToken,
                            httpClientOptions =
                                HttpClientOptions(requestTimeout = TIMEOUT, connectTimeout = TIMEOUT, socketTimeout = TIMEOUT),
                        )
                    val me by api.userApi.getCurrentUser()
                    // Only this profile's own answer counts
                    if (me.id == userId) ProfileStatus.Active(me.primaryImageTag) else ProfileStatus.Unknown
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't check a saved profile")
                profileStatusOf(ex)
            }

        private companion object {
            val TIMEOUT = 5.seconds
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileStatusModule {
    @Binds
    abstract fun profileStatusRemote(impl: JellyfinProfileStatusRemote): ProfileStatusRemote
}
