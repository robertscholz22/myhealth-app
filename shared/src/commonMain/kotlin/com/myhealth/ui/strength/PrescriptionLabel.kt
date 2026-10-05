package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import kotlin.math.floor
import kotlin.math.round

/**
 * "3 × 8 @ 50 kg" / "3 × 40 s" / "3 × 12" (bodyweight) / "3 × 8 @ 2 × 12.5 kg" (per hand) /
 * "~3 × 5 @ 40 kg" (estimated) — the one-line prescription text PLAN §P16 "Where it shows" prints
 * on the workout editor's rows, the workouts list, the planned-session card's workout line and the
 * set-log sheet.
 *
 * Pure: [sets] comes from the workout row — `ExercisePrescription` never carries a set count, since
 * the progression state is per exercise, not per workout — everything else from [prescription].
 * "~" prefixes an estimate `ProgressionEngine.initial` guessed from body weight that no feedback
 * has confirmed yet ([ExercisePrescription.isEstimated]).
 */
fun prescriptionLabel(sets: Int, prescription: ExercisePrescription): String {
    val prefix = if (prescription.isEstimated) "~" else ""
    val unit = prescription.reps?.let { "$sets × $it" } ?: "$sets × ${prescription.seconds ?: 0} s"
    val load = prescription.loadKg?.let { kg ->
        val text = formatLoadKg(kg)
        if (prescription.perHand) " @ 2 × $text kg" else " @ $text kg"
    }.orEmpty()
    return "$prefix$unit$load"
}

/**
 * "8 reps @ 50 kg" / "40 s" / "~5 reps @ 40 kg" / "2 × 12.5 kg per hand" / "12 reps" (bodyweight) —
 * [ExerciseDetailScreen]'s Progression card "current prescription" line (P16.2). Unlike
 * [prescriptionLabel], there is no workout row here to say how many sets a session should be, so
 * this states only the per-set target.
 */
fun prescriptionOnlyLabel(prescription: ExercisePrescription): String {
    val prefix = if (prescription.isEstimated) "~" else ""
    val unit = prescription.reps?.let { "$it reps" } ?: "${prescription.seconds ?: 0} s"
    val load = prescription.loadKg?.let { kg ->
        val text = formatLoadKg(kg)
        if (prescription.perHand) " @ 2 × $text kg per hand" else " @ $text kg"
    }.orEmpty()
    return "$prefix$unit$load"
}

/**
 * "Bench press 3 × 8 @ 50 kg, Squat 3 × 5 @ 40 kg, Barbell row 3 × 8 @ 30 kg…" — the planned-session
 * card's workout line (PLAN §P16 "Where it shows", P16.2): the first three rows of [workout], each
 * as its own name and [prescriptionLabel], joined by ", ", with an ellipsis when the workout has
 * more. Built straight from the row's own stored numbers — never marked "~", since a workout row is
 * what the plan actually says, not an estimate — a workout with no rows is `null`, not "".
 */
fun workoutPrescriptionLine(workout: StrengthWorkout): String? {
    if (workout.exercises.isEmpty()) return null
    val lines = workout.exercises.sortedBy { it.orderIndex }.take(WORKOUT_LINE_EXERCISE_COUNT).map { row ->
        val name = ExerciseCatalog.byId(row.exerciseId)?.name ?: row.exerciseId
        "$name ${prescriptionLabel(row.sets, row.asFallbackPrescription())}"
    }
    val suffix = if (workout.exercises.size > WORKOUT_LINE_EXERCISE_COUNT) "…" else ""
    return lines.joinToString(", ") + suffix
}

private const val WORKOUT_LINE_EXERCISE_COUNT = 3

/** A stored [StrengthWorkoutExercise] row as the [ExercisePrescription] [prescriptionLabel] wants —
 * never estimated, since it is what the workout row itself already says. */
private fun StrengthWorkoutExercise.asFallbackPrescription(): ExercisePrescription = ExercisePrescription(
    exerciseId = exerciseId,
    loadKg = loadKg,
    reps = reps,
    seconds = seconds,
    isEstimated = false,
    perHand = ProgressionDefaults.isPerHand(exerciseId) && loadKg != null,
)

/** "50" for a whole number, "32.5" otherwise — never "50.0" (loads are always a multiple of the
 * 1.0/2.5 kg equipment increment, so one fraction digit is always enough). Locale-independent: a
 * kg amount inside [prescriptionLabel] is a number embedded in a wider non-localized string, not a
 * standalone display value, so it does not go through `ui/common/Numbers.kt`'s locale-aware
 * formatters. */
internal fun formatLoadKg(value: Double): String {
    val rounded = round(value * 10.0) / 10.0
    return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
}
