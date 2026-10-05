package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.Feedback
import kotlinx.serialization.Serializable

/**
 * `strength_set_log` (PLAN §2.2.7, P14) — optional per-set logging when a session is marked done.
 *
 * One flat table: the planned session or the completed activity is the header, and both links are
 * `SET_NULL`, so deleting either keeps the record that the set was done. [day] is the index the
 * Load and Strength screens read on, since a log is looked up by date far more often than by its
 * header.
 */
@Serializable
@Entity(
    tableName = "strength_set_log",
    foreignKeys = [
        ForeignKey(
            entity = PlannedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["plannedSessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["day"], name = "idx_ssl_day"),
        Index(value = ["plannedSessionId"], name = "idx_ssl_planned"),
        Index(value = ["activityId"], name = "idx_ssl_activity"),
    ],
)
data class StrengthSetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val day: Long,
    val plannedSessionId: Long? = null,
    val activityId: Long? = null,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val rpe: Int? = null,
    val completedAtMillis: Long,
    /**
     * How the exercise felt (P16.1, DB v7). One feedback is chosen per exercise in the set-log
     * sheet and written onto every set row of that exercise, so the row alone is a complete
     * record; the repository applies it to `exercise_progress` once per exercise and save.
     */
    val feedback: Feedback? = null,
)
