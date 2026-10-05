package com.myhealth.data.repository

import com.myhealth.data.db.dao.StrengthDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.systemClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.engine.strength.ProgressionEngine
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [StrengthRepository] over the three tables of §2.2.7 (P14.1).
 *
 * [upsertWorkout] writes the header, then **replaces** the child rows: the editor always hands
 * over the full ordered list, so deleting and re-inserting is what keeps `uq_swe_order` satisfied
 * at every instant (a diff would have to move rows through positions that are still taken). The
 * `orderIndex` of each row is taken from its position in the list, not from the row itself, so a
 * reorder in the UI cannot leave a hole — the compaction P14.4 asks for falls out of that, on a
 * delete as much as on a move.
 *
 * [upsertWorkout] is also where §2.2.7's one rule SQLite cannot express is enforced: a row
 * prescribes **exactly one** of `reps` / `seconds`, and at least one set. A violation is a
 * [AppError.Validation], not an exception — the editor shows it on the offending field.
 *
 * P16.1 adds `exercise_progress`: [saveSetLogs] is the only writer of the progression (one
 * `ProgressionEngine.next` per exercise and save), and [prescriptionFor] is the read side, which
 * falls back to the engine's body-weight estimate when an exercise has never been logged.
 */
class RoomStrengthRepository(
    private val dao: StrengthDao,
    /**
     * P16.1: the body weight the **initial** estimate is built on — the latest measurement, or
     * [ProgressionDefaults.FALLBACK_BODY_WEIGHT_KG] (75 kg) when the database holds none. Wired to
     * `BodyRepository.latestWeight` in `AppGraph`; a plain lambda rather than the repository
     * itself, so this class keeps one dependency and the tests keep none.
     */
    private val bodyWeightKg: suspend () -> Double = { ProgressionDefaults.FALLBACK_BODY_WEIGHT_KG },
    private val clock: PlatformClock = systemClock(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StrengthRepository {

    override fun observeAll(): Flow<List<StrengthWorkout>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: Long): Flow<StrengthWorkout?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: Long): StrengthWorkout? =
        withContext(ioDispatcher) { dao.getById(id)?.toDomain() }

    override suspend fun getByTemplateId(templateId: String): StrengthWorkout? =
        withContext(ioDispatcher) { dao.getByTemplateId(templateId)?.toDomain() }

    override suspend fun upsertWorkout(workout: StrengthWorkout): Outcome<Long> =
        withContext(ioDispatcher) {
            workout.validationError()?.let { return@withContext Outcome.Err(it) }
            runCatchingApp {
                val id = dao.upsertWorkout(workout.toEntity())
                    .let { if (it > 0L) it else workout.id }
                dao.deleteExercisesOf(id)
                if (workout.exercises.isNotEmpty()) {
                    dao.upsertExercises(
                        workout.exercises.mapIndexed { index, exercise ->
                            exercise.copy(id = 0L, orderIndex = index).toEntity(workoutId = id)
                        },
                    )
                }
                id
            }
        }

    override suspend fun deleteWorkout(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.deleteWorkout(id) }
    }

    override suspend fun setUseInSuggestions(id: Long, use: Boolean): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp { dao.setUseInSuggestions(id, use, clock.millis()) }
        }

    override suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                if (logs.isNotEmpty()) dao.insertSetLogs(logs.map { it.copy(id = 0L).toEntity() })
                Unit
            }
        }

    /**
     * P16.1: the set rows go in, then the progression is applied **once per exercise** — the
     * feedback is chosen per exercise in the sheet and copied onto each of its set rows, so the
     * first row that carries one decides. An exercise with no state yet is seeded from the body
     * weight first, so the very first logged session already advances from a real starting point.
     */
    override suspend fun saveSetLogs(logs: List<StrengthSetLog>): Outcome<Map<String, ExerciseProgress>> =
        withContext(ioDispatcher) {
            runCatchingApp {
                if (logs.isNotEmpty()) dao.insertSetLogs(logs.map { it.copy(id = 0L).toEntity() })
                val today = today()
                val weight = bodyWeightKg()
                val advanced = mutableMapOf<String, ExerciseProgress>()
                logs.mapNotNull { row -> row.feedback?.let { row.exerciseId to it } }
                    .distinctBy { it.first }
                    .forEach { (exerciseId, feedback) ->
                        val exercise = ExerciseCatalog.byId(exerciseId) ?: return@forEach
                        val current = dao.getProgress(exerciseId)?.toDomain()
                            ?: ProgressionEngine.initial(exercise, weight, today)
                        val next = ProgressionEngine.next(current, feedback, exercise, today)
                        dao.upsertProgress(next.toEntity())
                        advanced[exerciseId] = next
                    }
                advanced.toMap()
            }
        }

    override fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLog>> =
        dao.observeSetLogsByDay(day).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLog> =
        withContext(ioDispatcher) {
            dao.getSetLogsOfPlannedSession(plannedSessionId).map { it.toDomain() }
        }

    override suspend fun deleteSetLog(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.deleteSetLog(id) }
    }

    override suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLog> =
        withContext(ioDispatcher) { dao.getRecentSetLogs(exerciseId, limit).map { it.toDomain() } }

    override fun observeProgress(exerciseId: String): Flow<ExerciseProgress?> =
        dao.observeProgress(exerciseId).map { it?.toDomain() }

    override suspend fun getProgress(exerciseId: String): ExerciseProgress? =
        withContext(ioDispatcher) { dao.getProgress(exerciseId)?.toDomain() }

    override suspend fun getAllProgress(): List<ExerciseProgress> =
        withContext(ioDispatcher) { dao.getAllProgress().map { it.toDomain() } }

    override suspend fun upsertProgress(progress: ExerciseProgress): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { dao.upsertProgress(progress.toEntity()) } }

    override suspend fun prescriptionFor(
        exercise: Exercise,
        bodyWeightKg: Double,
    ): ExercisePrescription = withContext(ioDispatcher) {
        ProgressionEngine.prescription(exercise, dao.getProgress(exercise.id)?.toDomain(), bodyWeightKg)
    }

    /** Epoch day in the device's zone — what `exercise_progress.updatedDay` records. */
    private fun today(): Long = clock.todayEpochDay()

    /** The first broken row's error, or `null` when the whole workout is well formed. */
    private fun StrengthWorkout.validationError(): AppError.Validation? {
        if (name.isBlank()) return AppError.Validation("name", "A workout needs a name.")
        exercises.forEachIndexed { index, row ->
            val position = index + 1
            if (row.sets < 1) {
                return AppError.Validation("sets", "Exercise $position needs at least one set.")
            }
            val counted = row.reps != null
            val held = row.seconds != null
            if (counted == held) {
                return AppError.Validation(
                    "reps",
                    "Exercise $position needs either a rep count or a hold in seconds, not both.",
                )
            }
        }
        return null
    }
}
