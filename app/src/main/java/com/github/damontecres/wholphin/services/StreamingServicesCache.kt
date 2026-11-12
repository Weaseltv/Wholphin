package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.kids.KidContentGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * The member's streaming services ([StreamingCollections.fetch]), remembered for [KEEP] so that going back to Home
 * and opening Movies or TV Shows don't each ask the server for the same list again (owner, 2026-11-12: the request
 * was about a second of server work every time, and Home asked on every return).
 *
 * What is remembered is the server's answer in the server's order. Callers put it in the member's own order
 * afterwards ([StreamingOrderService.sort]), so moving a service or resetting the order still shows at once. A
 * service added, removed or emptied on the server shows up to [KEEP] later.
 *
 * Only in memory, for one member at a time: closing the app or switching member forgets it. Never for a kid session:
 * which services a kid sees is decided by the content rules, whose own lookups last two minutes
 * ([KidContentGuard.CACHE_TTL]), so a kid's list is asked for every time, as before.
 */
@Singleton
class StreamingServicesCache
    @Inject
    constructor(
        private val kidContentGuard: KidContentGuard,
    ) {
        private class Kept(
            val userId: UUID,
            val session: KidContentGuard.Session?,
            /** Whether the answer has descriptions and tags, or only names ([StreamingCollections.fetch]'s namesOnly) */
            val full: Boolean,
            val atMillis: Long,
            val services: List<BaseItemDto>,
        )

        private val lock = Mutex()
        private var kept: Kept? = null

        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        /**
         * The services [userId] can see, in the server's order.
         *
         * @param namesOnly for a caller that reads only each service's id and name; a remembered full answer serves it too
         */
        suspend fun get(
            api: ApiClient,
            userId: UUID,
            namesOnly: Boolean = false,
        ): List<BaseItemDto> {
            val session = kidContentGuard.sessionOf(api)
            if (session?.rules != null) return StreamingCollections.fetch(api, userId, namesOnly)
            // One at a time, so a second caller waits for the first one's answer instead of asking too
            return lock.withLock {
                kept
                    ?.takeIf {
                        it.userId == userId &&
                            it.session == session &&
                            (it.full || namesOnly) &&
                            clock() - it.atMillis in 0 until KEEP.inWholeMilliseconds
                    }?.services
                    ?: StreamingCollections.fetch(api, userId, namesOnly).also { services ->
                        // Not an empty answer, and not one the session changed under
                        if (services.isNotEmpty() && kidContentGuard.sessionOf(api) == session) {
                            kept = Kept(userId, session, !namesOnly, clock(), services)
                        }
                    }
            }
        }

        companion object {
            private val KEEP = 5.minutes
        }
    }
