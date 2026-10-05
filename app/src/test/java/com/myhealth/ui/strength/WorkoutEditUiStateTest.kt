package com.myhealth.ui.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.strength.StrengthTemplates
import com.myhealth.domain.model.StrengthWorkoutKind
import org.junit.Test

/** PLAN §4.2 "Workout edit" / P14.7's `swui01`…`swui06` and §P17's `mobui02`, over the pure
 * [WorkoutEditDraft] and [StrengthWorkoutKind.label]. */
class WorkoutEditUiStateTest {

    @Test
    fun swui01_move_exercise_reorders_the_rows() {
        val draft = WorkoutEditDraft(
            exercises = listOf(
                WorkoutExerciseDraft("A"),
                WorkoutExerciseDraft("B"),
                WorkoutExerciseDraft("C"),
            ),
        )
        val moved = draft.moveExercise(from = 0, to = 2)
        assertThat(moved.exercises.map { it.exerciseId }).containsExactly("B", "C", "A").inOrder()
    }

    @Test
    fun swui02_add_exercise_appends_with_catalog_defaults() {
        val draft = WorkoutEditDraft(name = "Test", exercises = listOf(WorkoutExerciseDraft("BARBELL_BENCH_PRESS")))

        val withCounted = draft.addExercise("BARBELL_BACK_SQUAT")
        assertThat(withCounted.exercises).hasSize(2)
        val counted = withCounted.exercises.last()
        assertThat(counted.exerciseId).isEqualTo("BARBELL_BACK_SQUAT")
        assertThat(counted.reps).isNotNull()
        assertThat(counted.seconds).isNull()

        val withTimed = draft.addExercise("PLANK")
        val timed = withTimed.exercises.last()
        assertThat(timed.reps).isNull()
        assertThat(timed.seconds).isNotNull()
        assertThat(timed.isBodyweight).isTrue()

        // An id the catalog does not have leaves the draft unchanged.
        assertThat(draft.addExercise("NOT_A_REAL_ID")).isEqualTo(draft)
    }

    @Test
    fun swui03_remove_exercise_drops_only_that_row() {
        val draft = WorkoutEditDraft(
            exercises = listOf(WorkoutExerciseDraft("A"), WorkoutExerciseDraft("B"), WorkoutExerciseDraft("C")),
        )
        val removed = draft.removeExercise(1)
        assertThat(removed.exercises.map { it.exerciseId }).containsExactly("A", "C").inOrder()
    }

    @Test
    fun swui04_estimated_minutes_upper_a_is_44() {
        val draft = workoutEditDraftOf(StrengthTemplates.UPPER_A)
        assertThat(draft.estimatedMinutes).isEqualTo(44)
    }

    @Test
    fun swui05_reps_xor_seconds_validation() {
        val bothSet = WorkoutEditDraft(
            name = "Test",
            exercises = listOf(WorkoutExerciseDraft("A", reps = 10, seconds = 30)),
        )
        assertThat(validateWorkoutDraft(bothSet).rowErrors).containsKey(0)

        val neitherSet = WorkoutEditDraft(
            name = "Test",
            exercises = listOf(WorkoutExerciseDraft("A", reps = null, seconds = null)),
        )
        assertThat(validateWorkoutDraft(neitherSet).rowErrors).containsKey(0)

        val exactlyOne = WorkoutEditDraft(
            name = "Test",
            exercises = listOf(WorkoutExerciseDraft("A", reps = 10, seconds = null)),
        )
        assertThat(validateWorkoutDraft(exactlyOne).rowErrors).isEmpty()
        assertThat(validateWorkoutDraft(exactlyOne).isValid).isTrue()
    }

    @Test
    fun swui06_blank_name_is_rejected() {
        val blank = WorkoutEditDraft(name = "   ", exercises = listOf(WorkoutExerciseDraft("A", reps = 10)))
        val validation = validateWorkoutDraft(blank)
        assertThat(validation.nameError).isTrue()
        assertThat(validation.isValid).isFalse()

        val named = blank.copy(name = "Legs")
        assertThat(validateWorkoutDraft(named).nameError).isFalse()
    }

    @Test
    fun mobui02_mobility_kind_labels() {
        assertThat(StrengthWorkoutKind.MOBILITY_LOWER.label()).isEqualTo("Mobility · lower")
        assertThat(StrengthWorkoutKind.MOBILITY_UPPER.label()).isEqualTo("Mobility · upper")
        assertThat(StrengthWorkoutKind.MOBILITY_FULL.label()).isEqualTo("Mobility · full")

        // The six P14 kinds are untouched.
        assertThat(StrengthWorkoutKind.UPPER.label()).isEqualTo("Upper")
        assertThat(StrengthWorkoutKind.CUSTOM.label()).isEqualTo("Custom")
    }
}
