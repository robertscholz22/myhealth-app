package com.myhealth.ui.strength

import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout

/** One editable set of [SetLogSheet] — a row of `StrengthWorkoutExercise` expanded per set. */
data class SetLogRow(
    val exerciseId: String,
    val exerciseName: String,
    val setIndex: Int,
    val reps: Int?,
    val seconds: Int?,
    val loadKg: Double?,
    val skipped: Boolean = false,
)

/**
 * One [SetLogRow] per set of every row in [workout], pre-filled from [prescriptions] where there is
 * one (P16.2's "pre-filled sets from the prescription"), else from the workout row's own numbers as
 * before (§4.2 "Planned session edit" / "Mark done", P14.7) — what [SetLogSheet] starts from.
 */
fun setLogRowsFor(
    workout: StrengthWorkout,
    prescriptions: Map<String, ExercisePrescription> = emptyMap(),
): List<SetLogRow> = workout.exercises.flatMap { row ->
    val name = ExerciseCatalog.byId(row.exerciseId)?.name ?: row.exerciseId
    val prescription = prescriptions[row.exerciseId]
    (0 until row.sets).map { setIndex ->
        SetLogRow(
            exerciseId = row.exerciseId,
            exerciseName = name,
            setIndex = setIndex,
            reps = prescription?.reps ?: row.reps,
            seconds = prescription?.seconds ?: row.seconds,
            loadKg = prescription?.loadKg ?: row.loadKg,
        )
    }
}

/** [row] as the [StrengthSetLog] the repository stores, once the caller supplies the header the
 * flat table needs (§2.2.7): the day, the planned session it completes, a write timestamp, and the
 * per-exercise [feedback] chosen in the sheet's segmented buttons (P16.2). */
fun SetLogRow.toStrengthSetLog(
    day: Long,
    plannedSessionId: Long,
    completedAtMillis: Long,
    feedback: Feedback?,
): StrengthSetLog = StrengthSetLog(
    id = 0L,
    day = day,
    plannedSessionId = plannedSessionId,
    exerciseId = exerciseId,
    setIndex = setIndex,
    reps = reps,
    seconds = seconds,
    loadKg = loadKg,
    completedAtMillis = completedAtMillis,
    feedback = feedback,
)

/** The distinct exercises of [rows], in first-appearance order, each defaulted to [Feedback.HARD]
 * — what the sheet's per-exercise segmented-button row starts from (PLAN §P16 "load progression":
 * "four segmented buttons, default `HARD`", `slui01`). */
fun defaultFeedbackByExercise(rows: List<SetLogRow>): Map<String, Feedback> =
    rows.distinctBy { it.exerciseId }.associate { it.exerciseId to Feedback.HARD }

/**
 * "Bench press 3 × 9 @ 50 kg; Squat 3 × 6 @ 42.5 kg" (`slui03`) — the detail half of the "Next
 * time: …" snackbar PLAN §P16 "Where it shows" describes, one line per exercise [newStates] that
 * changed, joined by "; " when there is more than one. [rows] supplies the exercise's display name
 * and how many sets were actually logged — `saveSetLogs` only returns the new [ExerciseProgress],
 * which knows neither. An exercise with no new state (no feedback was given for it, or the
 * repository dropped a catalog id it no longer recognises) contributes nothing; an empty result
 * means the caller falls back to a plain "marked done" message instead.
 */
fun nextTimeDetails(rows: List<SetLogRow>, newStates: Map<String, ExerciseProgress>): String {
    val setsByExercise = rows.groupingBy { it.exerciseId }.eachCount()
    val nameByExercise = rows.associate { it.exerciseId to it.exerciseName }
    return newStates.mapNotNull { (exerciseId, progress) ->
        val sets = setsByExercise[exerciseId] ?: return@mapNotNull null
        val name = nameByExercise[exerciseId] ?: exerciseId
        "$name ${prescriptionLabel(sets, progress.asPrescription(exerciseId))}"
    }.joinToString("; ")
}

/** An [ExerciseProgress] as [prescriptionLabel]'s input — the same construction `ProgressionEngine.prescription`
 * would produce for a state that already exists (no body weight needed: the state carries its own load). */
private fun ExerciseProgress.asPrescription(exerciseId: String): ExercisePrescription = ExercisePrescription(
    exerciseId = exerciseId,
    loadKg = loadKg,
    reps = reps,
    seconds = seconds,
    isEstimated = isEstimated,
    perHand = ProgressionDefaults.isPerHand(exerciseId) && loadKg != null,
    lastFeedback = lastFeedback,
)
