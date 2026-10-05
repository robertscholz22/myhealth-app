package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myhealth.domain.model.RecoveryBand
import kotlinx.serialization.Serializable

/**
 * `daily_load` (PLAN §2.2.6) — cached load/recovery series, keyed by epoch [day]. The engines
 * return richer objects (with components and typed flags); this row is the cache the UI reads.
 */
@Serializable
@Entity(tableName = "daily_load")
data class DailyLoadEntity(
    @PrimaryKey val day: Long,
    val trimp: Double,
    val sessionCount: Int,
    /** Acute load (7-day EWMA). */
    val atl: Double,
    /** Chronic load (28-day EWMA). */
    val ctl: Double,
    val acwr: Double? = null,
    /** Training stress balance = ctl - atl. */
    val tsb: Double,
    val monotony: Double? = null,
    val strain: Double? = null,
    val recoveryScore: Int? = null,
    val recoveryBand: RecoveryBand? = null,
    val recoveryConfidence: Double = 0.0,
    val flagsCsv: String = "",
    val computedAtMillis: Long,
)
