package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ActivitySource
import kotlinx.serialization.Serializable

/**
 * `daily_health_summary` (PLAN §2.2.3) — one row per local day, keyed by epoch [day].
 *
 * [bodyBattery], [stressAvg] and [trainingReadiness] are only ever populated by the Tier-3 Garmin
 * client (P9); they stay null when the data comes from Health Connect.
 */
@Serializable
@Entity(tableName = "daily_health_summary")
data class DailyHealthSummaryEntity(
    @PrimaryKey val day: Long,
    val steps: Int? = null,
    /** HC `TotalCaloriesBurnedRecord` daily aggregate. */
    val totalEnergyKcal: Double? = null,
    /** HC `ActiveCaloriesBurnedRecord`. */
    val activeEnergyKcal: Double? = null,
    val restingHr: Int? = null,
    val distanceMeters: Double? = null,
    val floors: Double? = null,
    val avgSpo2Percent: Double? = null,
    val avgRespiratoryRate: Double? = null,
    val hrvRmssdMs: Double? = null,
    val vo2Max: Double? = null,
    val bodyBattery: Int? = null,
    val stressAvg: Int? = null,
    val trainingReadiness: Int? = null,
    val source: ActivitySource,
    val updatedAtMillis: Long,
)
