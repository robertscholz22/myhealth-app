package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.GoalEntity
import com.myhealth.domain.model.GoalStatus
import kotlinx.coroutines.flow.Flow

/** DAO for `goal` (PLAN §2.2.4). Priority 1 is the primary goal. */
@Dao
interface GoalDao {

    @Upsert
    suspend fun upsert(entity: GoalEntity): Long

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("DELETE FROM goal WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM goal WHERE status = :status ORDER BY priority ASC, targetDay ASC")
    fun observeByStatus(status: GoalStatus): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal ORDER BY status ASC, priority ASC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goal WHERE status = 'ACTIVE' ORDER BY priority ASC LIMIT 1")
    fun observePrimary(): Flow<GoalEntity?>

    @Query("UPDATE goal SET status = :status, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updateStatus(id: Long, status: GoalStatus, updatedAtMillis: Long)

    @Query("UPDATE goal SET priority = :priority, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updatePriority(id: Long, priority: Int, updatedAtMillis: Long)

    /**
     * The "only one primary goal" invariant of P6.1: every other goal at priority 1 drops to 2.
     * Kept as a single statement so promoting a goal can never leave two primaries behind.
     */
    @Query(
        "UPDATE goal SET priority = 2, updatedAtMillis = :updatedAtMillis " +
            "WHERE id != :keepGoalId AND priority <= 1",
    )
    suspend fun demoteOtherPrimaries(keepGoalId: Long, updatedAtMillis: Long)
}
