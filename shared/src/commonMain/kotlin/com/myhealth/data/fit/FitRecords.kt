package com.myhealth.data.fit

import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/**
 * The plain, SDK-free shape of one decoded `.fit` activity file (PLAN P7.1).
 *
 * Nothing from `com.garmin.fit` leaks past [FitFileDecoder]: every timestamp is already a UTC
 * epoch-millis value and every enum is its FIT profile name as a `String`. That is what makes
 * [FitToDomainMapper] unit-testable against JSON fixtures shaped exactly like this class (P7.2) —
 * a binary FIT file cannot be authored by hand.
 *
 * Positions are the one exception and stay in their native FIT unit (semicircles): the mapper
 * owns the conversion to the `1e7 * degrees` integers the domain stores, so the conversion itself
 * is covered by a fixture-driven test.
 */
@Serializable
data class FitFileData(
    val fileId: FitFileId? = null,
    val sessions: List<FitSession> = emptyList(),
    val laps: List<FitLap> = emptyList(),
    val records: List<FitRecord> = emptyList(),
    /**
     * `activity.local_timestamp - activity.timestamp` in seconds, i.e. the UTC offset the device
     * was set to. Present only when the file carries an `activity` message; recorded for
     * provenance, never used to shift an instant (all instants are absolute UTC already).
     */
    val localTimestampOffsetSec: Long? = null,
)

/** `file_id` — the identity half of the `externalId` derivation of §2.4. */
@Serializable
data class FitFileId(
    /** FIT `File` enum name, e.g. `ACTIVITY`. */
    val type: String? = null,
    val manufacturer: Int? = null,
    val product: Int? = null,
    val serialNumber: Long? = null,
    val timeCreatedMillis: Long? = null,
)

/** `session` — one activity inside the file (multisport files carry several). */
@Serializable
data class FitSession(
    val startAtMillis: Long,
    /** FIT `Sport` enum name, e.g. `RUNNING`; `null` when the file omits it. */
    val sport: String? = null,
    /** FIT `SubSport` enum name, e.g. `TREADMILL`. */
    val subSport: String? = null,
    val sportProfileName: String? = null,
    val totalElapsedSec: Double? = null,
    val totalTimerSec: Double? = null,
    val totalDistanceMeters: Double? = null,
    val totalCalories: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val avgCadenceSpm: Double? = null,
    val totalAscentM: Double? = null,
    /** `session.avg_power`, watts (P12). */
    val avgPowerW: Int? = null,
    /** `session.max_power`, watts (P12). */
    val maxPowerW: Int? = null,
    /** `session.normalized_power`, watts (P12). */
    val normalizedPowerW: Int? = null,
)

/** `lap` — mirrors `activity_lap` (§2.2.2) before it is bound to an activity id. */
@Serializable
data class FitLap(
    val messageIndex: Int? = null,
    val startAtMillis: Long,
    val totalElapsedSec: Double? = null,
    val totalTimerSec: Double? = null,
    val totalDistanceMeters: Double? = null,
    val totalCalories: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgSpeedMps: Double? = null,
)

/** `record` — one sample of the activity's time series. Every channel is independently absent. */
@Serializable
data class FitRecord(
    val timestampMillis: Long,
    val hr: Int? = null,
    /** Cumulative distance from the start of the activity, metres. */
    val distanceMeters: Double? = null,
    val speedMps: Double? = null,
    val cadenceSpm: Int? = null,
    val altitudeM: Double? = null,
    /** `record.power`, watts (P12). */
    val powerW: Int? = null,
    /** Native FIT semicircles; converted by [FitToDomainMapper] (`deg = semicircles * 180 / 2^31`). */
    val positionLatSemicircles: Int? = null,
    val positionLongSemicircles: Int? = null,
)

/**
 * The FIT epoch is 1989-12-31T00:00:00Z, 631 065 600 s after the Unix epoch (PLAN P7.1). A FIT
 * `date_time` is "seconds since the FIT epoch", so the conversion is a plain addition.
 */
object FitEpoch {

    const val OFFSET_SECONDS: Long = 631_065_600L

    fun toUnixMillis(fitSeconds: Long): Long = (fitSeconds + OFFSET_SECONDS) * 1000L

    fun toFitSeconds(unixMillis: Long): Long = unixMillis / 1000L - OFFSET_SECONDS
}

/**
 * FIT stores latitude/longitude as *semicircles*: a signed 32-bit integer covering ±180°, so
 * `degrees = semicircles * 180 / 2^31` (PLAN P7.1).
 */
object Semicircles {

    const val DEGREES_PER_SEMICIRCLE: Double = 180.0 / 2147483648.0

    fun toDegrees(semicircles: Int): Double = semicircles * DEGREES_PER_SEMICIRCLE

    /** The `1e7 * degrees` integer the domain's [com.myhealth.domain.model.GeoPoint] stores. */
    fun toE7(semicircles: Int): Int = (toDegrees(semicircles) * 1e7).roundToLong().toInt()
}
