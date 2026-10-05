package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.repository.StrengthWorkoutSeeder
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The nine built-in workouts and the seeder that materialises them (PLAN §3.12.3, `sw01`…`sw05`),
 * plus P17.1's three mobility routines (`mob03`, `mob04`).
 */
class StrengthTemplatesTest {

    private val lowerBodyGroups = MuscleGroup.entries.filter { it.isLowerBody }.toSet()

    @Test
    fun sw01_nine_builtin_templates() {
        assertThat(StrengthTemplates.ALL.map { it.templateId })
            .containsExactly(
                "UPPER_A", "UPPER_B", "LOWER_A", "LOWER_B", "FULL_A", "CORE_A",
                "MOBILITY_LOWER_A", "MOBILITY_UPPER_A", "MOBILITY_FULL_A",
            )
            .inOrder()
        assertThat(StrengthTemplates.ALL.all { it.isBuiltIn }).isTrue()
        assertThat(StrengthTemplates.ALL.all { it.id == 0L }).isTrue()
        assertThat(StrengthTemplates.ALL.map { it.kind }).containsExactly(
            StrengthWorkoutKind.UPPER,
            StrengthWorkoutKind.UPPER,
            StrengthWorkoutKind.LOWER,
            StrengthWorkoutKind.LOWER,
            StrengthWorkoutKind.FULL,
            StrengthWorkoutKind.CORE,
            StrengthWorkoutKind.MOBILITY_LOWER,
            StrengthWorkoutKind.MOBILITY_UPPER,
            StrengthWorkoutKind.MOBILITY_FULL,
        ).inOrder()
        StrengthTemplates.ALL.forEach { workout ->
            assertThat(workout.exercises.size).isAtLeast(5)
            assertThat(workout.exercises.size).isAtMost(8)
            assertThat(workout.exercises.map { it.orderIndex })
                .isEqualTo(workout.exercises.indices.toList())
            workout.exercises.forEach { row ->
                assertThat(ExerciseCatalog.byId(row.exerciseId)).isNotNull()
            }
        }
        assertThat(StrengthTemplates.byId("LOWER_B")).isEqualTo(StrengthTemplates.LOWER_B)
        assertThat(StrengthTemplates.byId("NOPE")).isNull()
        assertThat(StrengthTemplates.ofKind(StrengthWorkoutKind.UPPER))
            .containsExactly(StrengthTemplates.UPPER_A, StrengthTemplates.UPPER_B)
        assertThat(StrengthTemplates.ofKind(StrengthWorkoutKind.MOBILITY_LOWER))
            .containsExactly(StrengthTemplates.MOBILITY_LOWER_A)
    }

    @Test
    fun sw02_upper_template_has_no_lower_primary() {
        listOf(StrengthTemplates.UPPER_A, StrengthTemplates.UPPER_B).forEach { workout ->
            val offenders = workout.exercises
                .map { requireNotNull(ExerciseCatalog.byId(it.exerciseId)) }
                .filter { (it.primary intersect lowerBodyGroups).isNotEmpty() }
                .map { it.id }
            assertThat(offenders).isEmpty()
        }
    }

    @Test
    fun sw03_lower_template_has_no_upper_primary() {
        listOf(StrengthTemplates.LOWER_A, StrengthTemplates.LOWER_B).forEach { workout ->
            val offenders = workout.exercises
                .map { requireNotNull(ExerciseCatalog.byId(it.exerciseId)) }
                .filterNot { lowerBodyGroups.containsAll(it.primary) }
                .map { it.id }
            assertThat(offenders).isEmpty()
        }
    }

    @Test
    fun sw04_estimated_minutes_upper_a_is_44() {
        val upperA = StrengthTemplates.UPPER_A
        assertThat(upperA.exercises).hasSize(6)
        assertThat(upperA.exercises.map { it.sets }.toSet()).containsExactly(3)
        assertThat(upperA.exercises.map { it.reps }.toSet()).containsExactly(10)
        assertThat(upperA.exercises.map { it.restSec }.toSet()).containsExactly(90)
        // 6 × 3 × (10 × 3 s + 90 s) + 480 s = 2640 s → 44 min.
        assertThat(upperA.estimatedMinutes).isEqualTo(44)
        // The 480 s overhead alone rounds up to a 8-minute workout with no exercises at all.
        assertThat(upperA.copy(exercises = emptyList()).estimatedMinutes).isEqualTo(8)
        // A hold counts its own seconds, not reps × 3.
        assertThat(StrengthTemplates.CORE_A.estimatedMinutes).isEqualTo(
            EXPECTED_CORE_A_MINUTES,
        )
    }

    @Test
    fun sw05_seeder_is_idempotent() = runTest {
        val repo = InMemoryStrengthRepository()
        val seeder = StrengthWorkoutSeeder(repo, FIXED_CLOCK)

        assertThat(seeder.seed()).isEqualTo(9)
        assertThat(repo.stored.value).hasSize(9)

        assertThat(seeder.seed()).isEqualTo(0)
        assertThat(repo.stored.value).hasSize(9)
        assertThat(repo.stored.value.values.map { it.templateId })
            .containsExactly(
                "UPPER_A", "UPPER_B", "LOWER_A", "LOWER_B", "FULL_A", "CORE_A",
                "MOBILITY_LOWER_A", "MOBILITY_UPPER_A", "MOBILITY_FULL_A",
            )

        val upperA = requireNotNull(repo.getByTemplateId("UPPER_A"))
        assertThat(upperA.id).isGreaterThan(0L)
        assertThat(upperA.isBuiltIn).isTrue()
        assertThat(upperA.createdAtMillis).isEqualTo(FIXED_MILLIS)
        assertThat(upperA.exercises).hasSize(6)

        // A user edit to a seeded built-in survives the next seed.
        repo.upsertWorkout(upperA.copy(name = "Upper A (mine)"))
        assertThat(seeder.seed()).isEqualTo(0)
        assertThat(requireNotNull(repo.getByTemplateId("UPPER_A")).name).isEqualTo("Upper A (mine)")
        assertThat(repo.stored.value).hasSize(9)
    }

    @Test
    fun mob03_three_templates_18_to_22_minutes() {
        val mobility = listOf(
            StrengthTemplates.MOBILITY_LOWER_A,
            StrengthTemplates.MOBILITY_UPPER_A,
            StrengthTemplates.MOBILITY_FULL_A,
        )
        assertThat(StrengthTemplates.ALL.filter { it.kind.isMobility }).isEqualTo(mobility)

        mobility.forEach { workout ->
            // 6-8 timed rows, 30-60 s each, 0-15 s of rest (§P17).
            assertThat(workout.exercises.size).isAtLeast(6)
            assertThat(workout.exercises.size).isAtMost(8)
            workout.exercises.forEach { row ->
                val exercise = requireNotNull(ExerciseCatalog.byId(row.exerciseId))
                assertThat(exercise.isMobility).isTrue()
                assertThat(exercise.isTimed).isTrue()
                assertThat(row.reps).isNull()
                assertThat(requireNotNull(row.seconds)).isAtLeast(30)
                assertThat(requireNotNull(row.seconds)).isAtMost(60)
                assertThat(requireNotNull(row.restSec)).isAtLeast(0)
                assertThat(requireNotNull(row.restSec)).isAtMost(15)
            }
            // …and the whole routine is the "about 20 minutes" §P17 asks for.
            assertThat(workout.estimatedMinutes).isAtLeast(18)
            assertThat(workout.estimatedMinutes).isAtMost(22)
        }
        // The three land on exactly 21 min: 780/765/780 s of work + the flat 480 s overhead.
        assertThat(mobility.map { it.estimatedMinutes }).containsExactly(21, 21, 21).inOrder()
        // A mobility routine never crosses into the other half of the body's lifts.
        assertThat(StrengthTemplates.MOBILITY_UPPER_A.exercises.map { it.exerciseId })
            .doesNotContain("MOB_COUCH_STRETCH")
    }

    @Test
    fun mob04_seeder_idempotent_nine() = runTest {
        val repo = InMemoryStrengthRepository()
        val seeder = StrengthWorkoutSeeder(repo, FIXED_CLOCK)

        assertThat(seeder.seed()).isEqualTo(9)
        assertThat(seeder.seed()).isEqualTo(0)
        assertThat(seeder.seed()).isEqualTo(0)
        assertThat(repo.stored.value).hasSize(9)
        assertThat(repo.stored.value.values.count { it.kind.isMobility }).isEqualTo(3)

        // `accept` materialises a mobility routine exactly like a strength one (P14.5 / P17.1).
        val lower = requireNotNull(seeder.workoutFor("MOBILITY_LOWER_A"))
        assertThat(lower.id).isGreaterThan(0L)
        assertThat(lower.kind).isEqualTo(StrengthWorkoutKind.MOBILITY_LOWER)
        assertThat(lower.name).isEqualTo("Mobility lower A")
        assertThat(lower.exercises.all { it.seconds != null }).isTrue()
        // …and asking for it twice reuses the same row rather than seeding a tenth.
        assertThat(requireNotNull(seeder.workoutFor("MOBILITY_LOWER_A")).id).isEqualTo(lower.id)
        assertThat(repo.stored.value).hasSize(9)
    }

    /** In-memory [StrengthRepository] — only what the seeder touches is modelled. */
    private class InMemoryStrengthRepository : StrengthRepository {
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

        override suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit> =
            Outcome.Ok(Unit)

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

    private companion object {
        const val FIXED_MILLIS = 1_757_000_000_000L
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC)

        /**
         * `CORE_A`, all rests 60 s: 3 × (60 + 45 + 30 + 30 + 30 + 30 + 36) s of work +
         * 21 × 60 s of rest = 2043 s, + 480 s overhead = 2523 s → ceil(42.05) = 43 min.
         */
        const val EXPECTED_CORE_A_MINUTES = 43
    }
}
