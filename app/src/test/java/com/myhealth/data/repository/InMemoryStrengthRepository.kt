package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.SuggestedSessionEntity
import com.myhealth.data.db.entity.SuggestionBatchEntity
import com.myhealth.domain.engine.strength.ProgressionEngine
import com.myhealth.domain.engine.suggest.IntervalBuilder
import com.myhealth.domain.engine.suggest.IntervalContext
import com.myhealth.domain.engine.suggest.SuggestFixtures
import com.myhealth.domain.engine.suggest.SuggestionEngine
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** In-memory [StrengthRepository] — only what [StrengthWorkoutSeeder] touches is modelled. */
internal class InMemoryStrengthRepository : StrengthRepository {
    val stored = MutableStateFlow<Map<Long, StrengthWorkout>>(emptyMap())
    private var nextId = 1L

    val progress: MutableMap<String, ExerciseProgress> = mutableMapOf()

    override fun observeAll(): Flow<List<StrengthWorkout>> =
        stored.map { all -> all.values.sortedBy { it.name } }

    override fun observe(id: Long): Flow<StrengthWorkout?> = stored.map { it[id] }

    override suspend fun getById(id: Long): StrengthWorkout? = stored.value[id]

    override suspend fun getByTemplateId(templateId: String): StrengthWorkout? =
        stored.value.values.firstOrNull { it.templateId == templateId }

    override suspend fun upsertWorkout(workout: StrengthWorkout): Outcome<Long> {
        val id = if (workout.id == 0L) nextId++ else workout.id
        stored.value = stored.value + (id to workout.copy(id = id))
        return Outcome.Ok(id)
    }

    override suspend fun deleteWorkout(id: Long): Outcome<Unit> {
        stored.value = stored.value - id
        return Outcome.Ok(Unit)
    }

    override suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit> = Outcome.Ok(Unit)

    override fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLog>> =
        MutableStateFlow(emptyList())

    override suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLog> =
        emptyList()

    override suspend fun deleteSetLog(id: Long): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLog> =
        emptyList()

    override suspend fun saveSetLogs(
        logs: List<StrengthSetLog>,
    ): Outcome<Map<String, ExerciseProgress>> = Outcome.Ok(emptyMap())

    override fun observeProgress(exerciseId: String): Flow<ExerciseProgress?> =
        MutableStateFlow(progress[exerciseId])

    override suspend fun getProgress(exerciseId: String): ExerciseProgress? = progress[exerciseId]

    override suspend fun getAllProgress(): List<ExerciseProgress> = progress.values.toList()

    override suspend fun upsertProgress(progress: ExerciseProgress): Outcome<Unit> {
        this.progress[progress.exerciseId] = progress
        return Outcome.Ok(Unit)
    }

    override suspend fun prescriptionFor(
        exercise: Exercise,
        bodyWeightKg: Double,
    ): ExercisePrescription =
        ProgressionEngine.prescription(exercise, progress[exercise.id], bodyWeightKg)
}
