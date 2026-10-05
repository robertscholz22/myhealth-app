package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.serialization.Serializable

/**
 * `activity_session` (PLAN §2.2.2) — the canonical, merged activity produced by `ActivityMerger`
 * from the `activity_source_record` rows of one real-world session.
 *
 * [dedupeBucket] is `"${sportGroup}|${startAtMillis / 300_000}"` (§2.4); candidate lookup scans
 * buckets `n-1, n, n+1`. [userEditedFieldsCsv] lists field names the user overrode by hand — the
 * merger must never clobber those.
 */
@Serializable
@Entity(
    tableName = "activity_session",
    indices = [
        Index(value = ["startAtMillis"], name = "idx_act_start"),
        Index(value = ["day"], name = "idx_act_day"),
        Index(value = ["dedupeBucket"], name = "idx_act_bucket"),
        Index(value = ["sportGroup", "startAtMillis"], name = "idx_act_group_start"),
    ],
)
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startAtMillis: Long,
    val endAtMillis: Long,
    /** Local epoch day of [startAtMillis]. */
    val day: Long,
    val sportType: SportType,
    /** Denormalized `sportType.group` — used by the dedupe bucket and by range queries. */
    val sportGroup: SportGroup,
    val title: String? = null,
    /** Moving/timer time when known, else elapsed. */
    val durationSec: Int,
    val elapsedSec: Int,
    val distanceMeters: Double? = null,
    val activeEnergyKcal: Double? = null,
    val totalEnergyKcal: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    /** Steps/min for runs and walks, **revolutions per minute for CYCLE rides** (P12). */
    val avgCadenceSpm: Double? = null,
    val elevationGainM: Double? = null,
    /** Average cycling power, watts (P12, DB v5). */
    val avgPowerW: Int? = null,
    /** Maximum cycling power, watts (P12, DB v5). */
    val maxPowerW: Int? = null,
    /** Normalized power, watts (P12, DB v5). */
    val normalizedPowerW: Int? = null,
    /** Cached load-engine output (§3.2). */
    val trimp: Double? = null,
    val loadMethod: LoadMethod? = null,
    /** 1–10, user entered. */
    val rpe: Int? = null,
    val note: String? = null,
    /** Source of the winning field set. */
    val primarySource: ActivitySource,
    /** e.g. `HEALTH_CONNECT,FIT_IMPORT`. */
    val mergedSourcesCsv: String = "",
    val dedupeBucket: String,
    val userEditedFieldsCsv: String = "",
    val hasStreams: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
