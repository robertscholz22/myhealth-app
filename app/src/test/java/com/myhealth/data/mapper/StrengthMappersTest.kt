package com.myhealth.data.mapper

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.relation.StrengthWorkoutWithExercises
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutExercise
import com.myhealth.domain.model.StrengthWorkoutKind
import org.junit.Test

/** `strength_*` ⇄ domain (PLAN §2.2.7): `sw06`, `sw07`. */
class StrengthMappersTest {

    private fun workout() = StrengthWorkout(
        id = 7L,
        name = "Upper A",
        kind = StrengthWorkoutKind.UPPER,
        templateId = "UPPER_A",
        isBuiltIn = true,
        notes = "bench first",
        exercises = listOf(
            StrengthWorkoutExercise(
                id = 1L,
                workoutId = 7L,
                orderIndex = 0,
                exerciseId = "BARBELL_BENCH_PRESS",
                sets = 3,
                reps = 10,
                loadKg = 60.0,
                restSec = 90,
            ),
            StrengthWorkoutExercise(
                id = 2L,
                workoutId = 7L,
                orderIndex = 1,
                exerciseId = "PLANK",
                sets = 3,
                seconds = 45,
                isBodyweight = true,
                note = "brace",
            ),
        ),
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
    )

    @Test
    fun sw06_workout_round_trips() {
        val workout = workout()

        // Room makes no promise about a @Relation's order, so the join is rebuilt shuffled.
        val rebuilt = StrengthWorkoutWithExercises(
            workout = workout.toEntity(),
            exercises = workout.exercises.reversed().map { it.toEntity() },
        ).toDomain()

        assertThat(rebuilt).isEqualTo(workout)
        assertThat(rebuilt.exercises.map { it.orderIndex }).containsExactly(0, 1).inOrder()
        assertThat(rebuilt.exercises.first().exerciseId).isEqualTo("BARBELL_BENCH_PRESS")
        assertThat(rebuilt.exercises.last().seconds).isEqualTo(45)
        assertThat(rebuilt.exercises.last().reps).isNull()
    }

    @Test
    fun sw07_set_log_links_to_planned_session() {
        val log = StrengthSetLog(
            id = 3L,
            day = 20_707L,
            plannedSessionId = 42L,
            activityId = null,
            exerciseId = "BARBELL_BENCH_PRESS",
            setIndex = 2,
            reps = 8,
            loadKg = 65.0,
            rpe = 8,
            completedAtMillis = 1_789_000_000_000L,
        )

        val entity = log.toEntity()

        assertThat(entity.plannedSessionId).isEqualTo(42L)
        assertThat(entity.activityId).isNull()
        assertThat(entity.toDomain()).isEqualTo(log)
        // A set logged against an activity instead of a plan survives just as well.
        val onActivity = log.copy(plannedSessionId = null, activityId = 9L, seconds = 45, reps = null)
        assertThat(onActivity.toEntity().toDomain()).isEqualTo(onActivity)
    }

    @Test
    fun a_workout_without_rows_maps_to_an_empty_list() {
        val empty = StrengthWorkoutWithExercises(
            workout = StrengthWorkoutEntity(
                id = 1L,
                name = "Custom",
                kind = StrengthWorkoutKind.CUSTOM,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        ).toDomain()

        assertThat(empty.exercises).isEmpty()
        assertThat(empty.templateId).isNull()
        assertThat(empty.isBuiltIn).isFalse()
    }
}
