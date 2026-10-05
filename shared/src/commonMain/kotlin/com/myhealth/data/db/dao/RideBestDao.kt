package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.RideBestEntity
import com.myhealth.domain.model.RideBestKind
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `ride_best` (PLAN §2.2.6, P12).
 *
 * All qualifying efforts are kept, so "the PR" is derived, not stored. The two families disagree
 * about what "best" means — watts are better when higher, times when lower — so
 * [observeBestPerKind] picks with a `CASE`: `MAX(value)` for the `POWER_*` kinds and `MIN(value)`
 * for the `TIME_*` kinds, in one grouped query. SQLite's bare-column rule guarantees the other
 * columns come from the row that produced the chosen extreme, which is why the aggregate is
 * written over `value` itself rather than over a `CASE` expression.
 */
@Dao
interface RideBestDao {

    @Upsert
    suspend fun upsert(entity: RideBestEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<RideBestEntity>)

    @Query("SELECT * FROM ride_best WHERE id = :id")
    suspend fun getById(id: Long): RideBestEntity?

    @Query("DELETE FROM ride_best WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** The PR row per kind: the largest watts for `POWER_*`, the smallest seconds for `TIME_*`. */
    @Query(
        "SELECT id, kind, MAX(CASE WHEN kind LIKE 'POWER_%' THEN value ELSE -value END) AS ordering, " +
            "value, activityId, day, isEstimated, createdAtMillis FROM ride_best " +
            "GROUP BY kind ORDER BY kind ASC",
    )
    fun observeBestPerKind(): Flow<List<RideBestRow>>

    @Query(
        "SELECT * FROM ride_best WHERE kind = :kind " +
            "ORDER BY CASE WHEN kind LIKE 'POWER_%' THEN -value ELSE value END ASC LIMIT :limit",
    )
    fun observeByKind(kind: RideBestKind, limit: Int): Flow<List<RideBestEntity>>

    @Query("SELECT * FROM ride_best WHERE activityId = :activityId")
    suspend fun getByActivity(activityId: Long): List<RideBestEntity>

    @Query("DELETE FROM ride_best WHERE activityId = :activityId")
    suspend fun deleteByActivity(activityId: Long)

    /** Every effort on or after [day] — the window `FtpEstimator` reads (P12.2). */
    @Query("SELECT * FROM ride_best WHERE day >= :day ORDER BY day ASC")
    suspend fun getSince(day: Long): List<RideBestEntity>
}

/**
 * The projection of [RideBestDao.observeBestPerKind]. It carries the aggregate's own column
 * (`ordering`) alongside the picked row, because Room refuses to map a query whose result set has
 * a column the entity does not declare.
 */
data class RideBestRow(
    val id: Long,
    val kind: RideBestKind,
    /** The value the `MAX(CASE …)` selected on; negated for the `TIME_*` kinds. Not for display. */
    val ordering: Double,
    val value: Double,
    val activityId: Long?,
    val day: Long,
    val isEstimated: Boolean,
    val createdAtMillis: Long,
)
