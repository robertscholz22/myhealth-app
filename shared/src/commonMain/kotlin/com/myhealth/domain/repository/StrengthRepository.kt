package com.myhealth.domain.repository

import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * The three strength tables (PLAN §2.2.7, P14) behind one interface: workouts with their ordered
 * exercises, and the optional per-set log.
 *
 * Since P16.1 it also owns `exercise_progress`: the per-exercise load/rep state the progression
 * engine advances, plus [prescriptionFor], which answers "what should I do today" from that state
 * or from a body-weight estimate when the exercise has never been logged.
 *
 * A workout is always read and written **whole** — [upsertWorkout] takes the header plus the
 * complete ordered exercise list and returns the workout's id — because a half-written workout is
 * never a thing the user asked for, and `uq_swe_order` would reject a partial reorder anyway.
 */
interface StrengthRepository {

    fun observeAll(): Flow<List<StrengthWorkout>>

    fun observe(id: Long): Flow<StrengthWorkout?>

    suspend fun getById(id: Long): StrengthWorkout?

    /** The materialised row of a built-in template, if it has been seeded yet (P14.4). */
    suspend fun getByTemplateId(templateId: String): StrengthWorkout?

    /**
     * Inserts or updates the header and rewrites its rows from [StrengthWorkout.exercises], in the
     * order given. Returns the workout's id — new when [StrengthWorkout.id] was `0`.
     */
    suspend fun upsertWorkout(workout: StrengthWorkout): Outcome<Long>

    /** Deletes the workout; its exercise rows go with it (`CASCADE`), its set logs do not. */
    suspend fun deleteWorkout(id: Long): Outcome<Unit>

    /**
     * P19: the Workouts list's "Use in suggestions" checkbox. The default rewrites the row through
     * [upsertWorkout]; the Room implementation updates the one column.
     */
    suspend fun setUseInSuggestions(id: Long, use: Boolean): Outcome<Unit> {
        val workout = getById(id) ?: return Outcome.Ok(Unit)
        return when (val written = upsertWorkout(workout.copy(useInSuggestions = use))) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Err -> written
        }
    }

    suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit>

    /**
     * Inserts [logs] **and applies the progression** (P16.1): for every distinct exercise among
     * them that carries a [com.myhealth.domain.model.Feedback], `ProgressionEngine.next` runs
     * exactly once — a feedback is chosen per exercise, not per set — and the resulting state is
     * stored. Returns the new state per exercise id, which is what the "Next time: …" snackbar
     * prints. Rows without a feedback are logged and change nothing.
     */
    suspend fun saveSetLogs(logs: List<StrengthSetLog>): Outcome<Map<String, ExerciseProgress>>

    fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLog>>

    suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLog>

    /**
     * The most recently logged rows of [exerciseId], most recent day first (P16.2's Progression
     * card, "up to the last 10 logged sessions"): [limit] bounds the **rows** returned, not the
     * number of distinct days, so a caller after "N sessions" should ask for enough rows to cover
     * them and group by [StrengthSetLog.day] itself.
     */
    suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLog>

    suspend fun deleteSetLog(id: Long): Outcome<Unit>

    // ---- per-exercise load progression (`exercise_progress`, P16.1) ---------------------------

    fun observeProgress(exerciseId: String): Flow<ExerciseProgress?>

    suspend fun getProgress(exerciseId: String): ExerciseProgress?

    /** Every stored state, for the screens that prescribe a whole workout at once. */
    suspend fun getAllProgress(): List<ExerciseProgress>

    suspend fun upsertProgress(progress: ExerciseProgress): Outcome<Unit>

    /**
     * What to do for [exercise] today: the stored state, or `ProgressionEngine.initial`'s estimate
     * from [bodyWeightKg] when there is none. Reads only — an estimate is never written until a
     * feedback confirms it.
     */
    suspend fun prescriptionFor(exercise: Exercise, bodyWeightKg: Double): ExercisePrescription
}
