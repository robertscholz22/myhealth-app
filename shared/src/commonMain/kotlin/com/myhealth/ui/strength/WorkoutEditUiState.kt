package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.resources.*
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.body.highlightFor
import com.myhealth.data.time.PlatformClock
import kotlin.math.ceil

/**
 * One editable row of [WorkoutEditDraft] (PLAN §4.2 "Workout edit", P14.7) — mirrors
 * [StrengthWorkoutExercise] minus the ids the repository assigns.
 */
data class WorkoutExerciseDraft(
    val exerciseId: String,
    val sets: Int = DEFAULT_SETS,
    val reps: Int? = DEFAULT_REPS,
    val seconds: Int? = null,
    val loadKg: Double? = null,
    val isBodyweight: Boolean = false,
    val restSec: Int? = null,
    val note: String = "",
) {
    private companion object {
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 10
    }
}

/** [prescriptionLabel]'s input built straight from this row's own numbers (P16.2) — a live caption
 * under each editor row ("3 × 8 @ 50 kg") that always matches what the fields currently say, not an
 * estimate. */
fun WorkoutExerciseDraft.asPrescription(): ExercisePrescription = ExercisePrescription(
    exerciseId = exerciseId,
    loadKg = loadKg,
    reps = reps,
    seconds = seconds,
    isEstimated = false,
    perHand = ProgressionDefaults.isPerHand(exerciseId) && loadKg != null,
)

/**
 * [reps]/[seconds]/[loadKg] filled in from [prescription] — but only where the row does not
 * already carry a value, e.g. a load a previous edit typed in survives (P16.2's "prefill load/reps
 * from `prescriptionFor` when the row has none"). Two callers rely on this: [WorkoutEditViewModel]
 * clears a freshly-added row's generic 10-rep/30-second placeholder before merging, and it fills
 * the `loadKg` an `ExerciseSubstitution` row lost when the template was materialised against "my
 * equipment" — reps/seconds are left alone there, since the template already prescribed them.
 */
fun WorkoutExerciseDraft.withPrescription(prescription: ExercisePrescription): WorkoutExerciseDraft = copy(
    reps = reps ?: prescription.reps,
    seconds = seconds ?: prescription.seconds,
    loadKg = loadKg ?: prescription.loadKg,
)

/** The workout editor's form state (§4.2 "Workout edit"): name, kind, ordered exercise rows. */
data class WorkoutEditDraft(
    val id: Long = 0L,
    val name: String = "",
    val kind: StrengthWorkoutKind = StrengthWorkoutKind.FULL,
    val templateId: String? = null,
    val isBuiltIn: Boolean = false,
    val notes: String = "",
    val exercises: List<WorkoutExerciseDraft> = emptyList(),
    val createdAtMillis: Long = 0L,
) {
    /**
     * §3.12.3's estimate, computed from the draft directly (not by round-tripping through
     * [StrengthWorkout]) so the editor's live minutes stay in step with every keystroke:
     * `ceil((Σ sets × (workSec + restSec) + 480) / 60)`, `workSec = seconds ?: reps × 3`,
     * default rest 90 s (`swui04`: `UPPER_A` → 44).
     */
    val estimatedMinutes: Int
        get() {
            val workingSec = exercises.sumOf { row ->
                val workSec = row.seconds ?: ((row.reps ?: 0) * StrengthWorkout.SEC_PER_REP)
                row.sets.toLong() * (workSec + (row.restSec ?: StrengthWorkout.DEFAULT_REST_SEC)).toLong()
            }
            return ceil((workingSec + StrengthWorkout.OVERHEAD_SEC).toDouble() / 60.0).toInt()
        }
}

/** Row-level and name errors (§4.2); an empty, `false` result means the draft can be saved. */
data class WorkoutValidation(val nameError: Boolean = false, val rowErrors: Map<Int, UiMessage> = emptyMap()) {
    val isValid: Boolean get() = !nameError && rowErrors.isEmpty()
}

/** Blocking errors only (mirrors `validateGoal`/`validatePlannedSession`, §4.2). */
fun validateWorkoutDraft(draft: WorkoutEditDraft): WorkoutValidation {
    val rowErrors = mutableMapOf<Int, UiMessage>()
    draft.exercises.forEachIndexed { index, row ->
        val counted = row.reps != null
        val held = row.seconds != null
        val error = when {
            row.sets < 1 -> UiMessage.of(Res.string.workout_edit_error_sets)
            counted == held -> UiMessage.of(Res.string.workout_edit_error_reps_xor_seconds)
            else -> null
        }
        if (error != null) rowErrors[index] = error
    }
    return WorkoutValidation(nameError = draft.name.isBlank(), rowErrors = rowErrors)
}

/** Appends [exerciseId] as a new last row, defaulting to a hold for a timed exercise and to the
 * catalog's own bodyweight flag (`swui02`). A dropped catalog id leaves the draft unchanged. */
fun WorkoutEditDraft.addExercise(exerciseId: String): WorkoutEditDraft {
    val exercise = ExerciseCatalog.byId(exerciseId) ?: return this
    val row = WorkoutExerciseDraft(
        exerciseId = exercise.id,
        reps = if (exercise.isTimed) null else 10,
        seconds = if (exercise.isTimed) 30 else null,
        isBodyweight = exercise.isBodyweightOnly,
    )
    return copy(exercises = exercises + row)
}

/** Drops the row at [index] (`swui03`). Out-of-range is a no-op. */
fun WorkoutEditDraft.removeExercise(index: Int): WorkoutEditDraft {
    if (index !in exercises.indices) return this
    return copy(exercises = exercises.filterIndexed { i, _ -> i != index })
}

/** Moves the row at [from] to [to], shifting the rows between them (`swui01`). Out-of-range is a
 * no-op. */
fun WorkoutEditDraft.moveExercise(from: Int, to: Int): WorkoutEditDraft {
    if (from !in exercises.indices || to !in exercises.indices || from == to) return this
    val mutable = exercises.toMutableList()
    val row = mutable.removeAt(from)
    mutable.add(to, row)
    return copy(exercises = mutable)
}

/** The domain workout the editor saves. */
fun WorkoutEditDraft.toStrengthWorkout(clock: PlatformClock): StrengthWorkout {
    val now = clock.millis()
    return StrengthWorkout(
        id = id,
        name = name.trim(),
        kind = kind,
        templateId = templateId,
        isBuiltIn = isBuiltIn,
        notes = notes.trim().takeIf { it.isNotEmpty() },
        exercises = exercises.mapIndexed { index, row ->
            StrengthWorkoutExercise(
                id = 0L,
                workoutId = id,
                orderIndex = index,
                exerciseId = row.exerciseId,
                sets = row.sets,
                reps = row.reps,
                seconds = row.seconds,
                loadKg = row.loadKg,
                isBodyweight = row.isBodyweight,
                restSec = row.restSec,
                note = row.note.trim().takeIf { it.isNotEmpty() },
            )
        },
        createdAtMillis = if (id == 0L) now else createdAtMillis,
        updatedAtMillis = now,
    )
}

/** The live body-figure highlight for the editor (§4.2 "Workout edit"): the union/max of every
 * row's [ExerciseCatalog] entry, resolved directly from the draft — no [StrengthWorkout] or clock
 * needed just to preview it. A row naming an id the catalog dropped contributes nothing. */
fun WorkoutEditDraft.highlight(): Map<MuscleGroup, Float> {
    val result = mutableMapOf<MuscleGroup, Float>()
    exercises.forEach { row ->
        val exercise = ExerciseCatalog.byId(row.exerciseId) ?: return@forEach
        highlightFor(exercise).forEach { (group, value) -> result[group] = maxOf(result[group] ?: 0f, value) }
    }
    return result.mapValues { it.value.coerceIn(0f, 1f) }
}

/** Loads an existing workout into a draft (a duplicated built-in becomes an editable copy). */
fun workoutEditDraftOf(workout: StrengthWorkout): WorkoutEditDraft = WorkoutEditDraft(
    id = workout.id,
    name = workout.name,
    kind = workout.kind,
    templateId = workout.templateId,
    isBuiltIn = workout.isBuiltIn,
    notes = workout.notes.orEmpty(),
    exercises = workout.exercises.sortedBy { it.orderIndex }.map { row ->
        WorkoutExerciseDraft(
            exerciseId = row.exerciseId,
            sets = row.sets,
            reps = row.reps,
            seconds = row.seconds,
            loadKg = row.loadKg,
            isBodyweight = row.isBodyweight,
            restSec = row.restSec,
            note = row.note.orEmpty(),
        )
    },
    createdAtMillis = workout.createdAtMillis,
)
