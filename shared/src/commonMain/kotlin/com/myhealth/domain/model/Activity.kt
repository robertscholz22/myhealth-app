package com.myhealth.domain.model

import kotlinx.serialization.Serializable

/**
 * Light view used by lists (PLAN §2.3) — no streams, no laps. Mirrors `activity_session` minus
 * the merge/audit bookkeeping columns.
 */
data class ActivitySummary(
    val id: Long,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val day: Long,
    val sportType: SportType,
    val sportGroup: SportGroup,
    val title: String?,
    val durationSec: Int,
    val elapsedSec: Int,
    val distanceMeters: Double?,
    val activeEnergyKcal: Double?,
    val totalEnergyKcal: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    /** Steps/min for runs and walks, **revolutions per minute for CYCLE rides** (P12). */
    val avgCadenceSpm: Double?,
    val elevationGainM: Double?,
    /** Average cycling power, watts (P12). */
    val avgPowerW: Int? = null,
    /** Maximum cycling power, watts (P12). */
    val maxPowerW: Int? = null,
    /** Normalized power, watts — from the source, or `PowerMath.normalizedPower` (P12). */
    val normalizedPowerW: Int? = null,
    val trimp: Double?,
    val loadMethod: LoadMethod?,
    val rpe: Int?,
    val note: String?,
    val primarySource: ActivitySource,
    /** All sources that contributed to this merged row (§2.4) — drives the list row's source badges. */
    val mergedSources: List<ActivitySource>,
    val hasStreams: Boolean,
)

/**
 * The canonical, merged activity (§2.2.2), loaded "full": carries decoded [streams] and [laps]
 * rather than the JSON blob / child rows an entity would have (§2.3).
 */
data class ActivitySession(
    val id: Long,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val day: Long,
    val sportType: SportType,
    val sportGroup: SportGroup,
    val title: String?,
    val durationSec: Int,
    val elapsedSec: Int,
    val distanceMeters: Double?,
    val activeEnergyKcal: Double?,
    val totalEnergyKcal: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    /** Steps/min for runs and walks, **revolutions per minute for CYCLE rides** (P12). */
    val avgCadenceSpm: Double?,
    val elevationGainM: Double?,
    /** Average cycling power, watts (P12). */
    val avgPowerW: Int? = null,
    /** Maximum cycling power, watts (P12). */
    val maxPowerW: Int? = null,
    /** Normalized power, watts — from the source, or `PowerMath.normalizedPower` (P12). */
    val normalizedPowerW: Int? = null,
    val trimp: Double?,
    val loadMethod: LoadMethod?,
    val rpe: Int?,
    val note: String?,
    val primarySource: ActivitySource,
    val mergedSources: List<ActivitySource>,
    val dedupeBucket: String,
    val userEditedFields: List<String>,
    val hasStreams: Boolean,
    val streams: ActivityStreams?,
    val laps: List<Lap>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** One point of a GPS track, as `1e7 * degrees` integers (matches the FIT/HC representation). */
@Serializable
data class GeoPoint(val latE7: Int, val lngE7: Int)

/**
 * Decoded time-series for one activity (backed by JSON columns in `activity_stream`, §2.2.2).
 *
 * Design note: HR is modeled as `List<Int?>` rather than `IntArray` + a sentinel value (e.g. -1)
 * because a missing HR reading is a legitimate, common data point (strap dropout) and a sentinel
 * would either collide with a real value or require every consumer to remember the magic number.
 * Every other channel is a plain `DoubleArray`/`IntArray` since those are always numeric-or-absent
 * as a whole column (the column itself is nullable), not sample-by-sample.
 *
 * `equals`/`hashCode` are hand-written because the default data-class implementation compares
 * array properties by reference, not content.
 */
@Serializable
data class ActivityStreams(
    val sampleOffsetsSec: IntArray,
    val hr: List<Int?>,
    val distanceMeters: DoubleArray? = null,
    val speedMps: DoubleArray? = null,
    /** Steps/min for runs and walks, **revolutions per minute for CYCLE rides** (P12). */
    val cadenceSpm: DoubleArray? = null,
    val altitudeM: DoubleArray? = null,
    val latLngE7: List<GeoPoint>? = null,
    /** Cycling power in watts, last-value-carried-forward like the other numeric channels (P12). */
    val powerW: IntArray? = null,
    val sampleCount: Int,
    val medianIntervalSec: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ActivityStreams) return false
        return sampleOffsetsSec.contentEquals(other.sampleOffsetsSec) &&
            hr == other.hr &&
            distanceMeters.contentEqualsOrNull(other.distanceMeters) &&
            speedMps.contentEqualsOrNull(other.speedMps) &&
            cadenceSpm.contentEqualsOrNull(other.cadenceSpm) &&
            altitudeM.contentEqualsOrNull(other.altitudeM) &&
            latLngE7 == other.latLngE7 &&
            powerW.contentEqualsOrNull(other.powerW) &&
            sampleCount == other.sampleCount &&
            medianIntervalSec == other.medianIntervalSec
    }

    override fun hashCode(): Int {
        var result = sampleOffsetsSec.contentHashCode()
        result = 31 * result + hr.hashCode()
        result = 31 * result + (distanceMeters?.contentHashCode() ?: 0)
        result = 31 * result + (speedMps?.contentHashCode() ?: 0)
        result = 31 * result + (cadenceSpm?.contentHashCode() ?: 0)
        result = 31 * result + (altitudeM?.contentHashCode() ?: 0)
        result = 31 * result + (latLngE7?.hashCode() ?: 0)
        result = 31 * result + (powerW?.contentHashCode() ?: 0)
        result = 31 * result + sampleCount
        result = 31 * result + medianIntervalSec.hashCode()
        return result
    }
}

private fun DoubleArray?.contentEqualsOrNull(other: DoubleArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> this.contentEquals(other)
}

private fun IntArray?.contentEqualsOrNull(other: IntArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> this.contentEquals(other)
}

/** Mirrors `activity_lap` (FIT-derived, §2.2.2). */
data class Lap(
    val id: Long,
    val activityId: Long,
    val lapIndex: Int,
    val startAtMillis: Long,
    val durationSec: Int,
    val distanceMeters: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val energyKcal: Double?,
)

/** Mirrors `activity_source_record` — one row per arrival from each source (§2.2.2). */
data class ActivitySourceRecord(
    val id: Long,
    val activityId: Long?,
    val source: ActivitySource,
    val externalId: String,
    val payloadJson: String,
    val receivedAtMillis: Long,
    /** The `import_record` this arrival came from, or `null` for a sync arrival (undo, DB v4). */
    val importRecordId: Long? = null,
)
