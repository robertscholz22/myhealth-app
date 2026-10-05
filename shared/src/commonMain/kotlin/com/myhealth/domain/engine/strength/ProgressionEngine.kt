package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.Exercise
import kotlin.math.max

/**
 * Double progression from the owner's own feedback (PLAN §P16, P16.1) — pure functions over
 * [ExerciseProgress], so the UI can prescribe without a write and the repository can persist
 * without a rule of its own.
 *
 * **[initial]** seeds the state the first time an exercise is prescribed: a loaded lift starts at
 * `ratio × body weight` rounded **down** to the equipment increment, a bodyweight one carries no
 * load at all, and reps (or seconds) start at the **bottom** of the exercise's range. The state is
 * flagged [ExerciseProgress.isEstimated] until a feedback confirms it.
 *
 * **[next]** applies one feedback:
 *
 * | Feedback | Reps / seconds | Load |
 * |---|---|---|
 * | `TOO_EASY` | +2 reps (+10 s) | at the top of the range: +5 %, reps back to the bottom |
 * | `EASY` | +1 rep (+5 s) | at the top of the range: +2.5 %, reps back to the bottom |
 * | `HARD` | unchanged | unchanged |
 * | `TOO_HARD` | unchanged | −5 %, or −2 reps (−10 s) when there is no load |
 *
 * Every load step is **at least one increment** and never drops below one increment; reps never
 * drop below the bottom of the range. "There is no load" means `loadKg == null` — a bodyweight
 * exercise — which is also §P16's "bodyweight/timed at the top → stay at the top": a *loaded*
 * timed exercise (the farmer's carry) does have a load to add, so it gets the load step and its
 * hold goes back to the bottom. That reading is noted in §P16.
 */
object ProgressionEngine {

    /**
     * The starting state for [exercise] at [bodyWeightKg], stamped [day].
     *
     * Bodyweight exercises keep `loadKg = null`; everything else starts at
     * `ProgressionDefaults.ratioFor(id) × bodyWeightKg` floored to the equipment increment, and at
     * least one increment (a 0.10 × 78 kg curl must not come out as 0 kg).
     */
    fun initial(exercise: Exercise, bodyWeightKg: Double, day: Long = 0L): ExerciseProgress {
        val timed = exercise.isTimed
        return ExerciseProgress(
            exerciseId = exercise.id,
            loadKg = initialLoad(exercise, bodyWeightKg),
            reps = if (timed) null else ProgressionDefaults.repRange(exercise).first,
            seconds = if (timed) ProgressionDefaults.secondsRange(exercise).first else null,
            lastFeedback = null,
            isEstimated = true,
            updatedDay = day,
        )
    }

    /**
     * [state] after one [feedback] on [exercise], stamped [day]. The state stops being an estimate
     * the moment a feedback lands on it, whatever the feedback was.
     */
    fun next(
        state: ExerciseProgress,
        feedback: Feedback,
        exercise: Exercise,
        day: Long = state.updatedDay,
    ): ExerciseProgress {
        val stepped = when (feedback) {
            Feedback.HARD -> state
            Feedback.TOO_HARD -> state.harder(exercise)
            Feedback.EASY -> state.easier(
                exercise,
                ProgressionDefaults.EASY_REPS,
                ProgressionDefaults.EASY_SECONDS,
                ProgressionDefaults.EASY_LOAD_FRACTION,
            )
            Feedback.TOO_EASY -> state.easier(
                exercise,
                ProgressionDefaults.TOO_EASY_REPS,
                ProgressionDefaults.TOO_EASY_SECONDS,
                ProgressionDefaults.TOO_EASY_LOAD_FRACTION,
            )
        }
        return stepped.copy(lastFeedback = feedback, isEstimated = false, updatedDay = day)
    }

    /**
     * What to prescribe for [exercise] today: [state] if there is one, otherwise [initial]'s
     * estimate from [bodyWeightKg]. Pure — the set-log sheet, the workout row and the planned
     * session card all call this without touching the database.
     */
    fun prescription(
        exercise: Exercise,
        state: ExerciseProgress?,
        bodyWeightKg: Double,
    ): ExercisePrescription {
        val effective = state ?: initial(exercise, bodyWeightKg)
        return ExercisePrescription(
            exerciseId = exercise.id,
            loadKg = effective.loadKg,
            reps = effective.reps,
            seconds = effective.seconds,
            isEstimated = effective.isEstimated,
            perHand = ProgressionDefaults.isPerHand(exercise.id) && effective.loadKg != null,
            lastFeedback = effective.lastFeedback,
        )
    }

    // ---- the two directions -------------------------------------------------------------------

    /** `TOO_EASY` / `EASY`: more reps, and a load step once the range is exceeded. */
    private fun ExerciseProgress.easier(
        exercise: Exercise,
        repStep: Int,
        secondStep: Int,
        loadFraction: Double,
    ): ExerciseProgress {
        val timed = exercise.isTimed
        val range = if (timed) ProgressionDefaults.secondsRange(exercise)
        else ProgressionDefaults.repRange(exercise)
        val current = (if (timed) seconds else reps) ?: range.first
        val raised = current + (if (timed) secondStep else repStep)
        if (raised <= range.last) return withCount(timed, raised)
        val load = loadKg ?: return withCount(timed, range.last) // bodyweight: stay at the top
        return withCount(timed, range.first).copy(loadKg = stepLoad(exercise, load, loadFraction, up = true))
    }

    /** `TOO_HARD`: less load where there is load, otherwise fewer reps — never below the floor. */
    private fun ExerciseProgress.harder(exercise: Exercise): ExerciseProgress {
        val timed = exercise.isTimed
        val load = loadKg
        if (load != null) {
            return copy(
                loadKg = stepLoad(exercise, load, ProgressionDefaults.TOO_HARD_LOAD_FRACTION, up = false),
            )
        }
        val range = if (timed) ProgressionDefaults.secondsRange(exercise)
        else ProgressionDefaults.repRange(exercise)
        val current = (if (timed) seconds else reps) ?: range.first
        val step = if (timed) ProgressionDefaults.TOO_HARD_SECONDS else ProgressionDefaults.TOO_HARD_REPS
        return withCount(timed, max(current - step, range.first))
    }

    private fun ExerciseProgress.withCount(timed: Boolean, value: Int): ExerciseProgress =
        if (timed) copy(seconds = value, reps = null) else copy(reps = value, seconds = null)

    /**
     * [load] moved by [fraction], but by at least one increment and never below one increment,
     * rounded half-up to the increment.
     */
    private fun stepLoad(exercise: Exercise, load: Double, fraction: Double, up: Boolean): Double {
        val increment = ProgressionDefaults.incrementKg(exercise)
        val delta = max(load * fraction, increment)
        val raw = if (up) load + delta else load - delta
        return max(ProgressionDefaults.roundToIncrement(raw, increment), increment)
    }

    /**
     * `null` for a bodyweight exercise **and for every P17 mobility drill**, else the floored
     * estimate, at least one increment.
     */
    private fun initialLoad(exercise: Exercise, bodyWeightKg: Double): Double? {
        if (!ProgressionDefaults.carriesLoad(exercise)) return null
        val increment = ProgressionDefaults.incrementKg(exercise)
        val raw = ProgressionDefaults.ratioFor(exercise.id) * bodyWeightKg
        return max(ProgressionDefaults.floorToIncrement(raw, increment), increment)
    }
}
