package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myhealth.domain.model.Feedback
import kotlinx.serialization.Serializable

/**
 * `exercise_progress` (PLAN §P16, P16.1) — one row per catalog exercise holding what to prescribe
 * next: the current load, the current reps or hold, the last feedback and whether the numbers are
 * still the engine's body-weight estimate.
 *
 * The primary key is the `ExerciseCatalog` id itself ([exerciseId]), not an autoincrement: there
 * is exactly one state per exercise, and keying on the id makes the upsert idempotent without a
 * unique index. Like `strength_workout_exercise.exerciseId` it is deliberately **not** a foreign
 * key — the catalog is a Kotlin object, not a table (§3.12.1).
 *
 * [loadKg] is `null` for a bodyweight exercise and per hand for the dumbbell lifts
 * `ProgressionDefaults.isPerHand` names; exactly one of [reps] / [seconds] is set.
 */
@Serializable
@Entity(tableName = "exercise_progress")
data class ExerciseProgressEntity(
    @PrimaryKey val exerciseId: String,
    val loadKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val lastFeedback: Feedback? = null,
    val isEstimated: Boolean = true,
    /** Epoch day of the last change. */
    val updatedDay: Long = 0L,
)
