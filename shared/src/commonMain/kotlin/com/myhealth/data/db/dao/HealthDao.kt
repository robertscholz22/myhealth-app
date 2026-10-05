package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.DailyHealthSummaryEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `daily_health_summary` (PLAN §2.2.3); the key is the epoch day. */
@Dao
interface HealthDao {

    @Upsert
    suspend fun upsert(entity: DailyHealthSummaryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DailyHealthSummaryEntity>)

    @Query("SELECT * FROM daily_health_summary WHERE day = :day")
    suspend fun getById(day: Long): DailyHealthSummaryEntity?

    @Query("SELECT * FROM daily_health_summary WHERE day = :day")
    suspend fun getByDay(day: Long): DailyHealthSummaryEntity?

    @Query("DELETE FROM daily_health_summary WHERE day = :day")
    suspend fun deleteById(day: Long)

    @Query(
        "SELECT * FROM daily_health_summary WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC",
    )
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyHealthSummaryEntity>>

    @Query("SELECT * FROM daily_health_summary ORDER BY day DESC LIMIT 1")
    fun observeLatest(): Flow<DailyHealthSummaryEntity?>

    /**
     * Drops rows whose only populated metric is [DailyHealthSummaryEntity.totalEnergyKcal].
     * Health Connect synthesises a basal-metabolic calorie baseline for days no app wrote to, so
     * a long backfill used to leave one such row per empty day (verification BUG-1/NOTE-4).
     */
    @Query(
        "DELETE FROM daily_health_summary WHERE steps IS NULL AND activeEnergyKcal IS NULL " +
            "AND distanceMeters IS NULL AND floors IS NULL AND restingHr IS NULL " +
            "AND avgSpo2Percent IS NULL AND avgRespiratoryRate IS NULL AND hrvRmssdMs IS NULL " +
            "AND vo2Max IS NULL AND bodyBattery IS NULL AND stressAvg IS NULL " +
            "AND trainingReadiness IS NULL",
    )
    suspend fun deleteEmptySummaries(): Int
}
