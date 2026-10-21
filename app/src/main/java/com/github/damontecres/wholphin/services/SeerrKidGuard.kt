package com.github.damontecres.wholphin.services

import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/**
 * WeaselFin (kid profiles): who may use Seerr (Discover and Requests) from this TV.
 *
 * Kids must never reach the request server, so this fails closed: a user counts as
 * an adult only when the app holds that same user's server policy and the policy
 * has no parental rating ceiling. A kid profile is a Jellyfin user with a
 * MaxParentalRating; an unknown policy (not fetched yet, or fetched for a different
 * user) is treated as a kid too.
 *
 * Pure, so it is unit-tested without Android.
 */
object SeerrKidGuard {
    fun isConfirmedAdult(
        userId: UUID?,
        userDto: UserDto?,
    ): Boolean {
        if (userId == null || userDto == null || userDto.id != userId) return false
        val policy = userDto.policy ?: return false
        return policy.maxParentalRating == null
    }
}
