package com.github.damontecres.wholphin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinServerUsers
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.JellyfinUserPreferences
import com.github.damontecres.wholphin.data.model.PolicyState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface JellyfinServerDao {
    @Insert(onConflict = OnConflictStrategy.Companion.IGNORE)
    fun addServer(server: JellyfinServer): Long

    @Update
    fun updateServer(server: JellyfinServer): Int

    @Transaction
    fun addOrUpdateServer(server: JellyfinServer) {
        val result = addServer(server)
        if (result == -1L) {
            updateServer(server)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun addUser(user: JellyfinUser): Long

    @Update
    fun updateUser(user: JellyfinUser): Int

    @Transaction
    fun addOrUpdateUser(user: JellyfinUser): JellyfinUser {
        val result = addUser(user)
        if (result == -1L) {
            val toSave =
                if (user.rowId <= 0) {
                    val temp = getUser(user.serverId, user.id)!!
                    user.copy(rowId = temp.rowId)
                } else {
                    user
                }
            updateUser(toSave)
            return toSave
        }
        return user.copy(rowId = result.toInt())
    }

    @Query("SELECT * FROM users WHERE serverId = :serverId AND id = :userId")
    fun getUser(
        serverId: UUID,
        userId: UUID,
    ): JellyfinUser?

    @Query("SELECT * FROM users WHERE serverId = :serverId AND id = :userId")
    fun getUserFlow(
        serverId: UUID,
        userId: UUID,
    ): Flow<JellyfinUser?>

    /**
     * Kid profiles (review round 10): sets only the PIN and login requirement of a saved profile, unless that would
     * remove the PIN its row has right then and [allowPinRemoval] is false. The check and the write are one statement.
     *
     * @return the number of rows written: 0 if refused, or if there is no such row
     */
    @Query(
        "UPDATE users SET pin = :pin, requireLogin = :requireLogin WHERE rowId = :rowId AND " +
            "(:allowPinRemoval OR TRIM(COALESCE(:pin, '')) != '' OR TRIM(COALESCE(pin, '')) = '')",
    )
    fun updateUserAuthUnlessPinRemoved(
        rowId: Int,
        pin: String?,
        requireLogin: Boolean,
        allowPinRemoval: Boolean,
    ): Int

    /**
     * Sets only the app preferences of a saved profile (review round 10): the rest of its row is read in the same
     * transaction, so a PIN or login requirement saved meanwhile is kept
     */
    @Transaction
    fun updateUserAppPreferences(
        rowId: Int,
        appPreferences: JellyfinUserPreferences,
    ) {
        val saved = getUserByRowId(rowId) ?: return
        updateUser(saved.copy(appPreferences = appPreferences))
    }

    @Query("SELECT * FROM users WHERE rowId = :rowId")
    fun getUserByRowId(rowId: Int): JellyfinUser?

    /** Sets only the UI language of a saved profile */
    @Query("UPDATE users SET uiLanguage = :uiLanguage WHERE rowId = :rowId")
    fun updateUserLanguage(
        rowId: Int,
        uiLanguage: String?,
    )

    /** Kid profiles: refresh only the server-policy columns of a saved profile */
    @Query("UPDATE users SET maxParentalRating = :maxParentalRating, policyState = :policyState WHERE rowId = :rowId")
    fun updateUserPolicy(
        rowId: Int,
        maxParentalRating: Int?,
        policyState: PolicyState,
    )

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    fun getUsersFlow(serverId: UUID): Flow<List<JellyfinUser>>

    @Query("SELECT COUNT(*) FROM users WHERE serverId = :serverId")
    fun countUsers(serverId: UUID): Int

    /** Kid profiles: saved rows the server policy marked as kids (any server) */
    @Query("SELECT COUNT(*) FROM users WHERE policyState = 'kid'")
    fun countKidUsers(): Int

    @Query("DELETE FROM servers WHERE id = :serverId")
    fun deleteServer(serverId: UUID)

    @Query("DELETE FROM users WHERE serverId = :serverId AND id = :userId")
    fun deleteUser(
        serverId: UUID,
        userId: UUID,
    )

    @Transaction
    @Query("SELECT * FROM servers")
    fun getServers(): List<JellyfinServerUsers>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :serverId")
    fun getServer(serverId: UUID): JellyfinServerUsers?
}
