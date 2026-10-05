package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** `activity_lap` (PLAN §2.2.2) — FIT-derived laps, owned by their activity. */
@Serializable
@Entity(
    tableName = "activity_lap",
    foreignKeys = [
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activityId"], name = "idx_lap_activity")],
)
data class ActivityLapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val activityId: Long,
    val lapIndex: Int,
    val startAtMillis: Long,
    val durationSec: Int,
    val distanceMeters: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgSpeedMps: Double? = null,
    val energyKcal: Double? = null,
)
