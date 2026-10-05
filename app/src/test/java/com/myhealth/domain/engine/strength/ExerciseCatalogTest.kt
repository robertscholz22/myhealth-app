package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import org.junit.Test

/** The catalog's coverage and lookup rules (PLAN §3.12.1, `ex01`…`ex10`). */
class ExerciseCatalogTest {

    @Test
    fun ex01_catalog_size_and_unique_ids() {
        assertThat(ExerciseCatalog.ALL.size).isAtLeast(40)
        val ids = ExerciseCatalog.ALL.map { it.id }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids.none { it.isBlank() }).isTrue()
        // Every id resolves back to its own entry.
        ExerciseCatalog.ALL.forEach { assertThat(ExerciseCatalog.byId(it.id)).isEqualTo(it) }
        assertThat(ExerciseCatalog.byId("NOT_AN_EXERCISE")).isNull()
    }

    @Test
    fun ex02_every_exercise_has_a_primary() {
        val without = ExerciseCatalog.ALL.filter { it.primary.isEmpty() }.map { it.id }
        assertThat(without).isEmpty()
    }

    @Test
    fun ex03_primary_and_secondary_disjoint() {
        val overlapping = ExerciseCatalog.ALL
            .filter { (it.primary intersect it.secondary).isNotEmpty() }
            .map { it.id }
        assertThat(overlapping).isEmpty()
    }

    @Test
    fun ex04_every_muscle_group_is_someone_s_primary() {
        val covered = ExerciseCatalog.ALL.flatMap { it.primary }.toSet()
        assertThat(covered).containsExactlyElementsIn(MuscleGroup.entries)
        MuscleGroup.entries.forEach { assertThat(ExerciseCatalog.byPrimary(it)).isNotEmpty() }
    }

    @Test
    fun ex05_bench_press_muscles() {
        val bench = requireNotNull(ExerciseCatalog.byId("BARBELL_BENCH_PRESS"))
        assertThat(bench.name).isEqualTo("Barbell bench press")
        assertThat(bench.primary).containsExactly(MuscleGroup.CHEST)
        assertThat(bench.secondary)
            .containsExactly(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS_FRONT)
        assertThat(bench.equipment).isEqualTo(Equipment.BARBELL)
        assertThat(bench.pattern).isEqualTo(MovementPattern.HORIZONTAL_PUSH)
    }

    @Test
    fun ex06_back_squat_muscles() {
        val squat = requireNotNull(ExerciseCatalog.byId("BARBELL_BACK_SQUAT"))
        assertThat(squat.primary).containsExactly(MuscleGroup.QUADS, MuscleGroup.GLUTES)
        assertThat(squat.secondary).containsExactly(
            MuscleGroup.HAMSTRINGS,
            MuscleGroup.LOWER_BACK,
            MuscleGroup.ABS,
        )
        assertThat(squat.pattern).isEqualTo(MovementPattern.SQUAT)
        assertThat(squat.unilateral).isFalse()
    }

    @Test
    fun ex07_unilateral_flags() {
        val unilateral = listOf(
            "WALKING_LUNGE",
            "REVERSE_LUNGE",
            "BULGARIAN_SPLIT_SQUAT",
            "STEP_UP",
            "SINGLE_LEG_RDL",
            "SIDE_PLANK",
        )
        unilateral.forEach { id ->
            assertThat(requireNotNull(ExerciseCatalog.byId(id)).unilateral).isTrue()
        }
        // A two-legged lift is not flagged.
        listOf("BARBELL_BACK_SQUAT", "CONVENTIONAL_DEADLIFT", "BARBELL_BENCH_PRESS").forEach { id ->
            assertThat(requireNotNull(ExerciseCatalog.byId(id)).unilateral).isFalse()
        }
    }

    @Test
    fun ex08_timed_exercises_use_seconds() {
        listOf("PLANK", "SIDE_PLANK", "HOLLOW_HOLD").forEach { id ->
            assertThat(requireNotNull(ExerciseCatalog.byId(id)).isTimed).isTrue()
        }
        val timedRows = StrengthTemplates.ALL
            .flatMap { it.exercises }
            .filter { requireNotNull(ExerciseCatalog.byId(it.exerciseId)).isTimed }
        assertThat(timedRows).isNotEmpty()
        timedRows.forEach { row ->
            assertThat(row.seconds).isNotNull()
            assertThat(row.reps).isNull()
        }
        // …and a counted exercise is never prescribed as a hold.
        StrengthTemplates.ALL.flatMap { it.exercises }
            .filterNot { requireNotNull(ExerciseCatalog.byId(it.exerciseId)).isTimed }
            .forEach { row ->
                assertThat(row.reps).isNotNull()
                assertThat(row.seconds).isNull()
            }
    }

    @Test
    fun ex09_search_matches_name_and_muscle() {
        val squat = requireNotNull(ExerciseCatalog.byId("BARBELL_BACK_SQUAT"))
        assertThat(ExerciseCatalog.search("squat")).contains(squat)
        assertThat(ExerciseCatalog.search("quads")).contains(squat)
        assertThat(ExerciseCatalog.search("QUADS")).contains(squat)
        assertThat(ExerciseCatalog.search("lower back")).contains(squat)
        assertThat(ExerciseCatalog.search("  Squat ").first().name.lowercase()).contains("squat")
        assertThat(ExerciseCatalog.search("")).isEqualTo(ExerciseCatalog.ALL)
        assertThat(ExerciseCatalog.search("zzzz")).isEmpty()
        assertThat(ExerciseCatalog.search("squat")).containsNoDuplicates()
    }

    @Test
    fun ex10_equipment_filter() {
        val bodyweight = ExerciseCatalog.filter(equipment = Equipment.BODYWEIGHT)
        assertThat(bodyweight.size).isAtLeast(12)
        assertThat(bodyweight.all { it.isBodyweightOnly }).isTrue()

        val bodyweightSquats =
            ExerciseCatalog.filter(equipment = Equipment.BODYWEIGHT, pattern = MovementPattern.SQUAT)
        assertThat(bodyweightSquats.map { it.id }).contains("WALL_SIT")
        assertThat(bodyweightSquats.size).isAtMost(bodyweight.size)

        val calves = ExerciseCatalog.filter(muscle = MuscleGroup.CALVES)
        assertThat(calves.map { it.id }).contains("CALF_RAISE")
        assertThat(calves.all { MuscleGroup.CALVES in it.primary + it.secondary }).isTrue()

        assertThat(ExerciseCatalog.filter()).isEqualTo(ExerciseCatalog.ALL)
    }
}
