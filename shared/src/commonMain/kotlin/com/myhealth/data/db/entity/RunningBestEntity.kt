package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `running_best` (PLAN §2.2.6) — **all** qualifying efforts are kept, not just the best one; "the
 * PR" is `MIN(timeSec)` per distance (see `RunningBestDao.observeBestPerDistance`). A partial
 * unique index is deliberately not used; instead `uq_best_activity_distance` prevents the same
 * activity contributing twice for one distance.
 *
 * Canonical [distanceMeters]: 1000, 1609.34, 3000, 5000, 10000, 15000, 21097.5, 42195.
 */
@Serializable
@Entity(
    tableName = "running_best",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["distanceMeters", "timeSec"], name = "idx_best_distance_time"),
        Index(value = ["activityId", "distanceMeters"], unique = true, name = "uq_best_activity_distance"),
    ],
)
data class RunningBestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val distanceMeters: Double,
    val timeSec: Int,
    val activityId: Long? = null,
    val day: Long,
    /** `FULL_ACTIVITY` / `BEST_SPLIT` / `MANUAL`. */
    val method: String,
    /** True when derived from sparse samples (see `activity_stream.medianIntervalSec`). */
    val isEstimated: Boolean = false,
    val paceSecPerKm: Int,
    val createdAtMillis: Long,
)
