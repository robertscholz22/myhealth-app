package com.myhealth.ui.common.body

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.BodySide
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import org.junit.Test

/** PLAN §3.12.2 / P14.7's `bf01`…`bf05`, over the pure geometry of [MusclePaths]. */
class MusclePathsTest {

    @Test
    fun bf01_every_muscle_group_has_at_least_one_path() {
        MuscleGroup.entries.forEach { group ->
            assertThat(MusclePaths.pathsFor(group)).isNotEmpty()
        }
    }

    @Test
    fun bf02_every_point_is_inside_the_100x220_box() {
        val allPolygons = MusclePaths.FRONT.values.flatten() +
            MusclePaths.BACK.values.flatten() +
            MusclePaths.FRONT_OUTLINE +
            MusclePaths.BACK_OUTLINE
        allPolygons.flatten().forEach { point ->
            assertThat(point.x >= 0f && point.x <= MusclePaths.WIDTH).isTrue()
            assertThat(point.y >= 0f && point.y <= MusclePaths.HEIGHT).isTrue()
        }
    }

    @Test
    fun bf03_every_group_appears_on_the_side_its_enum_declares() {
        MuscleGroup.entries.forEach { group ->
            when (group.side) {
                BodySide.FRONT -> {
                    assertThat(MusclePaths.FRONT).containsKey(group)
                    assertThat(MusclePaths.BACK).doesNotContainKey(group)
                }
                BodySide.BACK -> {
                    assertThat(MusclePaths.BACK).containsKey(group)
                    assertThat(MusclePaths.FRONT).doesNotContainKey(group)
                }
                BodySide.BOTH -> {
                    assertThat(MusclePaths.FRONT).containsKey(group)
                    assertThat(MusclePaths.BACK).containsKey(group)
                }
            }
        }
    }

    @Test
    fun bf04_highlight_for_exercise_maps_primary_one_and_secondary_035() {
        val exercise = Exercise(
            id = "TEST_BENCH",
            name = "Test bench",
            primary = setOf(MuscleGroup.CHEST),
            secondary = setOf(MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS_FRONT),
            equipment = Equipment.BARBELL,
            pattern = MovementPattern.HORIZONTAL_PUSH,
            cue = "Test",
        )
        val highlight = highlightFor(exercise)
        assertThat(highlight[MuscleGroup.CHEST]).isEqualTo(1.0f)
        assertThat(highlight[MuscleGroup.TRICEPS]).isEqualTo(0.35f)
        assertThat(highlight[MuscleGroup.SHOULDERS_FRONT]).isEqualTo(0.35f)
        assertThat(highlight.values.all { it in 0f..1f }).isTrue()
    }

    @Test
    fun bf05_highlight_for_workout_is_the_max_across_its_exercises() {
        // BARBELL_BENCH_PRESS: primary CHEST, secondary {TRICEPS, SHOULDERS_FRONT} (ex05).
        // BARBELL_BACK_SQUAT: primary {QUADS, GLUTES}, secondary {HAMSTRINGS, LOWER_BACK, ABS} (ex06).
        val workout = StrengthWorkout(
            id = 1L,
            name = "Test",
            kind = com.myhealth.domain.model.StrengthWorkoutKind.CUSTOM,
            exercises = listOf(
                StrengthWorkoutExercise(
                    id = 1L, workoutId = 1L, orderIndex = 0,
                    exerciseId = "BARBELL_BENCH_PRESS", sets = 3, reps = 10,
                ),
                StrengthWorkoutExercise(
                    id = 2L, workoutId = 1L, orderIndex = 1,
                    exerciseId = "BARBELL_BACK_SQUAT", sets = 3, reps = 10,
                ),
            ),
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
        )
        val highlight = highlightFor(workout)
        assertThat(highlight[MuscleGroup.CHEST]).isEqualTo(1.0f)
        assertThat(highlight[MuscleGroup.QUADS]).isEqualTo(1.0f)
        assertThat(highlight[MuscleGroup.GLUTES]).isEqualTo(1.0f)
        assertThat(highlight[MuscleGroup.TRICEPS]).isEqualTo(0.35f)
        assertThat(highlight.values.all { it in 0f..1f }).isTrue()
        // An exercise naming an unknown id must not blow up and contributes nothing.
        val withUnknown = highlightFor(
            workout.copy(
                exercises = workout.exercises + StrengthWorkoutExercise(
                    id = 3L, workoutId = 1L, orderIndex = 2, exerciseId = "NOT_A_REAL_ID", sets = 3, reps = 10,
                ),
            ),
        )
        assertThat(withUnknown).isEqualTo(highlight)
    }
}
