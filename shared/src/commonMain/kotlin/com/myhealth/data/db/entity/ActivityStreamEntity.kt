package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `activity_stream` (PLAN §2.2.2) — one row per activity. The series are stored as JSON arrays
 * rather than one row per sample: at personal scale this keeps the table small and the decode is
 * a single read (§2.2.2).
 *
 * [sampleOffsetsSecJson] is the shared time axis (seconds from the session's `startAtMillis`);
 * every other channel is index-aligned with it.
 */
@Serializable
@Entity(
    tableName = "activity_stream",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActivityStreamEntity(
    @PrimaryKey val activityId: Long,
    /** `[0,1,2,…]` seconds from the session start. */
    val sampleOffsetsSecJson: String,
    /** `[null,132,133,…]` bpm; null entries are strap dropouts. */
    val hrJson: String? = null,
    /** Cumulative metres. */
    val distanceMetersJson: String? = null,
    val speedMpsJson: String? = null,
    /** Steps/min for runs and walks, **revolutions per minute for CYCLE rides** (P12). */
    val cadenceSpmJson: String? = null,
    val altitudeMJson: String? = null,
    /** `[[lat1e7,lng1e7],…]`. */
    val latLngE7Json: String? = null,
    /** `[210,215,…]` watts (P12, DB v5); absent for every non-power activity. */
    val powerWJson: String? = null,
    val sampleCount: Int,
    /** Median sampling interval; decides whether a PR split is flagged `estimated` (§3.4). */
    val medianIntervalSec: Double,
)
