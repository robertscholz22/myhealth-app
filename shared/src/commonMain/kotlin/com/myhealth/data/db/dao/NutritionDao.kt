package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.NutritionTargetSnapshotEntity
import com.myhealth.data.db.entity.WaterLogEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `nutrition_target_snapshot` and `water_log` (PLAN §2.2.5); both are keyed by epoch day. */
@Dao
interface NutritionDao {

    @Upsert
    suspend fun upsertTarget(entity: NutritionTargetSnapshotEntity)

    @Upsert
    suspend fun upsertTargets(entities: List<NutritionTargetSnapshotEntity>)

    @Query("SELECT * FROM nutrition_target_snapshot WHERE day = :day")
    suspend fun getTarget(day: Long): NutritionTargetSnapshotEntity?

    @Query("SELECT * FROM nutrition_target_snapshot WHERE day = :day")
    suspend fun getById(day: Long): NutritionTargetSnapshotEntity?

    @Query("DELETE FROM nutrition_target_snapshot WHERE day = :day")
    suspend fun deleteById(day: Long)

    @Query("SELECT * FROM nutrition_target_snapshot WHERE day = :day")
    fun observeTarget(day: Long): Flow<NutritionTargetSnapshotEntity?>

    @Query(
        "SELECT * FROM nutrition_target_snapshot WHERE day BETWEEN :fromDay AND :toDay ORDER BY day ASC",
    )
    fun observeTargets(fromDay: Long, toDay: Long): Flow<List<NutritionTargetSnapshotEntity>>

    // ---- water ---------------------------------------------------------------------------------

    @Upsert
    suspend fun upsertWater(entity: WaterLogEntity): Long

    @Query("SELECT * FROM water_log WHERE day = :day ORDER BY atMinuteOfDay ASC")
    fun observeWaterDay(day: Long): Flow<List<WaterLogEntity>>

    @Query("SELECT COALESCE(SUM(ml), 0) FROM water_log WHERE day = :day")
    fun observeWaterTotal(day: Long): Flow<Int>

    @Query("DELETE FROM water_log WHERE id = :id")
    suspend fun deleteWaterById(id: Long)
}
