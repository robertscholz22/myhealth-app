package com.myhealth.domain.repository

import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `nutrition_target_snapshot` + `water_log` (PLAN §2.2.5, P4.12/P4.13).
 *
 * [ensureTarget] is the cache-invalidation entry point: it recomputes the day's target only when
 * the `inputsHash` (§P4.12) differs from the stored snapshot, and returns the current target
 * either way. [upsertTarget] is the raw write used by the recompute worker.
 */
interface NutritionRepository {

    fun observeTarget(day: Long): Flow<NutritionTarget?>

    fun observeTargets(fromDay: Long, toDay: Long): Flow<List<NutritionTarget>>

    suspend fun getTarget(day: Long): NutritionTarget?

    suspend fun ensureTarget(day: Long): Outcome<NutritionTarget>

    suspend fun upsertTarget(target: NutritionTarget): Outcome<Unit>

    suspend fun deleteTarget(day: Long): Outcome<Unit>

    // ---- water ---------------------------------------------------------------------------------

    fun observeWaterTotalMl(day: Long): Flow<Int>

    fun observeWaterLogs(day: Long): Flow<List<WaterLog>>

    suspend fun addWater(day: Long, ml: Int, atMinuteOfDay: Int?): Outcome<Long>

    suspend fun deleteWater(id: Long): Outcome<Unit>
}
