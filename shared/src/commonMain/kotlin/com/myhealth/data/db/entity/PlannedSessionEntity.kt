package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import kotlinx.serialization.Serializable

/**
 * `planned_session` (PLAN §2.2.4) — a session the user (or the suggester) intends to do.
 *
 * Both links are soft (`SET_NULL`): deleting a plan or the activity that completed the session
 * must not delete the session itself. [locked] marks a user-pinned session the suggester must
 * not move (constraint C6, §3.5.3).
 */
@Serializable
@Entity(
    tableName = "planned_session",
    foreignKeys = [
        ForeignKey(
            entity = TrainingPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ActivitySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedActivityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = StrengthWorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["planId"], name = "idx_planned_plan"),
        Index(value = ["day"], name = "idx_planned_day"),
        Index(value = ["linkedActivityId"], name = "idx_planned_activity"),
        Index(value = ["workoutId"], name = "idx_planned_workout"),
    ],
)
data class PlannedSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val planId: Long? = null,
    val day: Long,
    val startMinuteOfDay: Int? = null,
    val sportType: SportType,
    val sessionType: SessionType,
    val intensity: Intensity,
    val targetDurationMin: Int? = null,
    val targetDistanceMeters: Double? = null,
    val targetPaceSecPerKm: Int? = null,
    val estimatedTrimp: Double? = null,
    val description: String? = null,
    val rationale: String? = null,
    val status: PlannedStatus = PlannedStatus.PLANNED,
    val locked: Boolean = false,
    val linkedActivityId: Long? = null,
    val sourceSuggestionId: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** The `WorkoutStructure` of §3.11 as JSON (P14, DB v6). */
    val structureJson: String? = null,
    /** The strength workout this session runs (P14, DB v6); `SET_NULL`, index `idx_planned_workout`. */
    val workoutId: Long? = null,
)
