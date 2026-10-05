package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.RunningBestEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `running_best` (PLAN §2.2.6).
 *
 * All qualifying efforts are kept, so "the PR" is derived, not stored: [observeBestPerDistance]
 * groups by distance and picks `MIN(timeSec)`. SQLite's bare-column rule guarantees the other
 * columns come from the row that produced the minimum.
 */
@Dao
interface RunningBestDao {

    @Upsert
    suspend fun upsert(entity: RunningBestEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<RunningBestEntity>)

    @Query("SELECT * FROM running_best WHERE id = :id")
    suspend fun getById(id: Long): RunningBestEntity?

    @Query("DELETE FROM running_best WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT id, distanceMeters, MIN(timeSec) AS timeSec, activityId, day, method, " +
            "isEstimated, paceSecPerKm, createdAtMillis FROM running_best " +
            "GROUP BY distanceMeters ORDER BY distanceMeters ASC",
    )
    fun observeBestPerDistance(): Flow<List<RunningBestEntity>>

    @Query(
        "SELECT * FROM running_best WHERE distanceMeters = :distanceMeters " +
            "ORDER BY timeSec ASC LIMIT :limit",
    )
    fun observeByDistance(distanceMeters: Double, limit: Int): Flow<List<RunningBestEntity>>

    /** P19.1: every effort since [fromDay] — the recent-form window VDOT and goal progress read. */
    @Query("SELECT * FROM running_best WHERE day >= :fromDay ORDER BY day ASC, id ASC")
    fun observeSince(fromDay: Long): Flow<List<RunningBestEntity>>

    @Query("SELECT * FROM running_best WHERE activityId = :activityId")
    suspend fun getByActivity(activityId: Long): List<RunningBestEntity>

    @Query("DELETE FROM running_best WHERE activityId = :activityId")
    suspend fun deleteByActivity(activityId: Long)
}
