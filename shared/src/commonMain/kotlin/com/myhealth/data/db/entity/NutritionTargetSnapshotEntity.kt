package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myhealth.domain.model.DayType
import kotlinx.serialization.Serializable

/**
 * `nutrition_target_snapshot` (PLAN §2.2.5) — cached `NutritionTargetEngine` output, keyed by
 * epoch [day], so the UI and widgets are instant and the history is auditable.
 *
 * `TargetRecomputeWorker` recomputes a day when [inputsHash] changes (weight, profile, the plan
 * for that day, actual TDEE).
 */
@Serializable
@Entity(tableName = "nutrition_target_snapshot")
data class NutritionTargetSnapshotEntity(
    @PrimaryKey val day: Long,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val sugarCapG: Int,
    val satFatCapG: Int,
    val saltG: Double,
    val waterMl: Int,
    val bmrKcal: Int,
    val tdeeKcal: Int,
    val dayType: DayType,
    val explanation: String,
    /** `EngineWarningCode` names joined with `,`. */
    val warningsCsv: String = "",
    val inputsHash: String,
    val computedAtMillis: Long,
)
