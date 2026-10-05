package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.DailyLoadEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `daily_load` (PLAN §2.2.6) — the cached ATL/CTL/recovery series, keyed by epoch day. */
@Dao
interface LoadDao {

    @Upsert
    suspend fun upsert(entity: DailyLoadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DailyLoadEntity>)

    @Query("SELECT * FROM daily_load WHERE day = :day")
    suspend fun getById(day: Long): DailyLoadEntity?

    @Query("DELETE FROM daily_load WHERE day = :day")
    suspend fun deleteById(day: Long)

    @Query("SELECT * FROM daily_load WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC")
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyLoadEntity>>

    /** One-shot counterpart of [observeRange] — the repository's [LoadRepository][com.myhealth.domain.repository.LoadRepository]
     * one-shot `getRange` and the recompute worker both need a snapshot, not a stream (§1.4). */
    @Query("SELECT * FROM daily_load WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC")
    suspend fun getRange(fromDay: Long, toDay: Long): List<DailyLoadEntity>

    @Query("SELECT * FROM daily_load ORDER BY day DESC LIMIT 1")
    suspend fun getLatest(): DailyLoadEntity?

    @Query("SELECT * FROM daily_load ORDER BY day DESC LIMIT 1")
    fun observeLatest(): Flow<DailyLoadEntity?>

    @Query("DELETE FROM daily_load WHERE day >= :fromDay")
    suspend fun deleteFrom(fromDay: Long)

    /** POLISH-13: prunes cached rows that precede the earliest activity (or all rows, when
     * there are no activities and the caller passes `Long.MAX_VALUE`). */
    @Query("DELETE FROM daily_load WHERE day < :beforeDay")
    suspend fun deleteBefore(beforeDay: Long)
}
