package com.github.damontecres.wholphin.services

import androidx.work.ListenableWorker
import com.github.damontecres.wholphin.data.ServerRepository
import java.util.UUID

/**
 * Kid profiles (plan 4.1): background workers never touch the foreground session. They only run while their
 * scheduled user is the current session, never call [ServerRepository.restoreSession], and drop results when
 * the session changed while they ran.
 */
internal object WorkerSession {
    /**
     * @return null to go ahead, or the result to return now: success (skip, no work) without a current user
     * (chooser, cold start), retry when another profile is current
     */
    fun check(
        serverRepository: ServerRepository,
        serverId: UUID,
        userId: UUID,
    ): ListenableWorker.Result? {
        val current = serverRepository.current.value ?: return ListenableWorker.Result.success()
        return if (current.server.id == serverId && current.user.id == userId) {
            null
        } else {
            ListenableWorker.Result.retry()
        }
    }

    /** Whether the scheduled user is still the current session (checked after every suspension) */
    fun isCurrent(
        serverRepository: ServerRepository,
        serverId: UUID,
        userId: UUID,
    ): Boolean =
        serverRepository.current.value?.let { it.server.id == serverId && it.user.id == userId } == true
}
