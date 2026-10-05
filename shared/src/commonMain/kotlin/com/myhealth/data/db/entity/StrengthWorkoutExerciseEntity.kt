package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `strength_workout_exercise` (PLAN §2.2.7, P14) — the ordered children of a
 * [StrengthWorkoutEntity], deleted with it (`CASCADE`).
 *
 * [exerciseId] is an `ExerciseCatalog` id (`BARBELL_BACK_SQUAT`, `PUSH_UP`, …) and deliberately
 * **not** a foreign key: the catalog is a Kotlin object, not a table (§3.12.1).
 *
 * `uq_swe_order` keeps one row per position, so a reorder has to compact the indices rather than
 * leave a hole — the repository does that in P14.4. Exactly one of `reps`/`seconds` is set; that
 * too is a repository rule, because SQLite cannot express it without a `CHECK` Room would have to
 * carry in every future migration.
 */
@Serializable
@Entity(
    tableName = "strength_workout_exercise",
    foreignKeys = [
        ForeignKey(
            entity = StrengthWorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workoutId"], name = "idx_swe_workout"),
        Index(value = ["workoutId", "orderIndex"], unique = true, name = "uq_swe_order"),
    ],
)
data class StrengthWorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val workoutId: Long,
    val orderIndex: Int,
    val exerciseId: String,
    val sets: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val isBodyweight: Boolean = false,
    val restSec: Int? = null,
    val note: String? = null,
)
