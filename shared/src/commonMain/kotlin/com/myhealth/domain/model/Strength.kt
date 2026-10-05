package com.myhealth.domain.model

import kotlin.math.ceil

/**
 * The strength feature's models (PLAN §2.2.7 / §3.12.1 / §3.12.3): the catalog entry [Exercise],
 * which is code rather than a table, plus the three stored models — a named, ordered list of
 * exercises, its rows, and the optional per-set log written when a session is marked done.
 *
 * Nothing stored refers to [Exercise] by reference: [StrengthWorkoutExercise] keeps only the
 * catalog's stable [StrengthWorkoutExercise.exerciseId] string, so a catalog entry can be renamed
 * or re-described in code without a migration.
 */

/**
 * One entry of `ExerciseCatalog` (§3.12.1). [id] is stable and stored
 * (`strength_workout_exercise.exerciseId`); [name] and [cue] are English copy that lives in the
 * domain under the same exemption as the rationale strings — the UI renders them verbatim.
 *
 * [primary] and [secondary] are disjoint by construction (`ex03`) and drive both the body figure
 * (§3.12.2) and `MuscleLoadEngine`'s per-workout share (§3.12.4, primary 1.0 / secondary 0.5).
 */
data class Exercise(
    val id: String,
    val name: String,
    val primary: Set<MuscleGroup>,
    val secondary: Set<MuscleGroup> = emptySet(),
    val equipment: Equipment,
    val pattern: MovementPattern,
    /** Performed one side at a time — a set means one side (lunges, single-leg RDL, side plank). */
    val unilateral: Boolean = false,
    /** Prescribed as a hold in seconds rather than as a rep count (`ex08`). */
    val isTimed: Boolean = false,
    val cue: String,
) {
    /** Every group the exercise touches, primary first — what the body figure highlights. */
    val allMuscles: Set<MuscleGroup> get() = primary + secondary

    val isBodyweightOnly: Boolean get() = equipment == Equipment.BODYWEIGHT

    /**
     * P17: a stretch / joint rotation / foam-roll rather than a lift. Every mobility entry is
     * [isTimed], carries no load (`ProgressionDefaults.carriesLoad`) and is never substituted for
     * a strength movement.
     */
    val isMobility: Boolean get() = pattern == MovementPattern.MOBILITY
}
data class StrengthWorkout(
    val id: Long,
    val name: String,
    val kind: StrengthWorkoutKind,
    /** Stable id of a built-in (`UPPER_A`, `LOWER_A`, …); unique where not null. */
    val templateId: String? = null,
    /** Seeded from `StrengthTemplates`; a user's editable copy carries `false`. */
    val isBuiltIn: Boolean = false,
    val notes: String? = null,
    /** Always in [StrengthWorkoutExercise.orderIndex] order. */
    val exercises: List<StrengthWorkoutExercise> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** P19: the Workouts screen's "Use in suggestions" checkbox (DB v8). */
    val useInSuggestions: Boolean = true,
) {
    /**
     * How long the workout takes, rounded up (§3.12.3):
     * `ceil((Σ sets × (workSec + restSec) + 480) / 60)` with `workSec = seconds ?: reps × 3` and
     * a default rest of [DEFAULT_REST_SEC]. The flat 480 s is warm-up plus changeovers.
     *
     * Computed, never stored — a template edited in code must not leave a stale number in a row.
     */
    val estimatedMinutes: Int
        get() {
            val workingSec = exercises.sumOf { row ->
                val workSec = row.seconds ?: ((row.reps ?: 0) * SEC_PER_REP)
                row.sets.toLong() * (workSec + (row.restSec ?: DEFAULT_REST_SEC)).toLong()
            }
            return ceil((workingSec + OVERHEAD_SEC).toDouble() / 60.0).toInt()
        }

    companion object {
        /** The rest used when a row does not name one (§3.12.3). */
        const val DEFAULT_REST_SEC: Int = 90

        /** A counted rep is modelled as three seconds of work. */
        const val SEC_PER_REP: Int = 3

        /** Warm-up plus equipment changes, added once per workout. */
        const val OVERHEAD_SEC: Int = 480
    }
}

/**
 * One row of a [StrengthWorkout] (§2.2.7). Exactly one of [reps] / [seconds] is set — a rep count
 * for a counted exercise, a hold for a timed one; the repository validates it, SQL does not.
 */
data class StrengthWorkoutExercise(
    val id: Long,
    val workoutId: Long,
    val orderIndex: Int,
    /** An `ExerciseCatalog` id, e.g. `BARBELL_BACK_SQUAT`. Not a foreign key. */
    val exerciseId: String,
    val sets: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val isBodyweight: Boolean = false,
    val restSec: Int? = null,
    val note: String? = null,
)

/**
 * One logged set (§2.2.7). Flat by design: the planned session or the completed activity is the
 * header, and both links are soft, so deleting either keeps the fact that the set was done.
 */
data class StrengthSetLog(
    val id: Long,
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
     * How the exercise felt (P16.1, DB v7). Chosen **once per exercise** in the set-log sheet and
     * copied onto every one of its set rows, so a row alone still says what the feedback was; the
     * repository applies it to `exercise_progress` exactly once per exercise and save.
     */
    val feedback: Feedback? = null,
)

/**
 * `exercise_progress` (§P16, P16.1) — the current prescription of one catalog exercise, keyed by
 * its [exerciseId] (one row per exercise, never per workout: the owner squats the same weight
 * whichever workout the squat is in).
 *
 * [loadKg] is `null` for a bodyweight exercise, and **per hand** for the exercises
 * `ProgressionDefaults.isPerHand` names. Exactly one of [reps] / [seconds] is set, matching
 * `Exercise.isTimed`. [isEstimated] marks a state that `ProgressionEngine.initial` guessed from
 * body weight and no one has confirmed yet — the UI prints it with a "~".
 */
data class ExerciseProgress(
    val exerciseId: String,
    val loadKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val lastFeedback: Feedback? = null,
    val isEstimated: Boolean = true,
    /** Epoch day of the last change — what the Progression card prints next to the feedback. */
    val updatedDay: Long = 0L,
)

/**
 * What to do for one exercise **today** (P16.1): the stored [ExerciseProgress] when there is one,
 * otherwise `ProgressionEngine.initial`'s body-weight estimate. Never stored — it is the pure
 * function's result, so the UI can compute it without a write.
 */
data class ExercisePrescription(
    val exerciseId: String,
    val loadKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    /** Estimated from body weight rather than confirmed by a feedback — printed as "~". */
    val isEstimated: Boolean = true,
    /** [loadKg] is the weight of **one** dumbbell/kettlebell, not the pair. */
    val perHand: Boolean = false,
    val lastFeedback: Feedback? = null,
)
