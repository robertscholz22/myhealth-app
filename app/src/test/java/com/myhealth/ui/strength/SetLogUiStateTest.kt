package com.myhealth.ui.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Feedback
import org.junit.Test

/** PLAN §P16 "load progression" / "Where it shows", P16.2's `slui01`…`slui03`. */
class SetLogUiStateTest {

    @Test
    fun slui01_feedback_per_exercise_defaults_hard() {
        val rows = listOf(
            SetLogRow("BARBELL_BENCH_PRESS", "Barbell bench press", 0, reps = 8, seconds = null, loadKg = 50.0),
            SetLogRow("BARBELL_BENCH_PRESS", "Barbell bench press", 1, reps = 8, seconds = null, loadKg = 50.0),
            SetLogRow("BARBELL_BACK_SQUAT", "Barbell back squat", 0, reps = 5, seconds = null, loadKg = 40.0),
        )

        val defaults = defaultFeedbackByExercise(rows)

        assertThat(defaults).containsExactly(
            "BARBELL_BENCH_PRESS", Feedback.HARD,
            "BARBELL_BACK_SQUAT", Feedback.HARD,
        )
    }

    @Test
    fun slui02_prescription_label_formats() {
        val counted = ExercisePrescription(
            exerciseId = "BARBELL_BENCH_PRESS",
            loadKg = 50.0,
            reps = 8,
            isEstimated = false,
        )
        assertThat(prescriptionLabel(3, counted)).isEqualTo("3 × 8 @ 50 kg")

        val timed = ExercisePrescription(exerciseId = "PLANK", seconds = 40, isEstimated = false)
        assertThat(prescriptionLabel(3, timed)).isEqualTo("3 × 40 s")

        val estimated = ExercisePrescription(
            exerciseId = "BARBELL_BACK_SQUAT",
            loadKg = 40.0,
            reps = 5,
            isEstimated = true,
        )
        assertThat(prescriptionLabel(3, estimated)).isEqualTo("~3 × 5 @ 40 kg")

        val perHand = ExercisePrescription(
            exerciseId = "DUMBBELL_ROW",
            loadKg = 12.5,
            reps = 8,
            isEstimated = false,
            perHand = true,
        )
        assertThat(prescriptionLabel(3, perHand)).isEqualTo("3 × 8 @ 2 × 12.5 kg")

        val bodyweight = ExercisePrescription(exerciseId = "PUSH_UP", reps = 12, isEstimated = false)
        assertThat(prescriptionLabel(3, bodyweight)).isEqualTo("3 × 12")
    }

    @Test
    fun slui03_next_time_snackbar_text() {
        val rows = listOf(
            SetLogRow("BARBELL_BENCH_PRESS", "Bench press", 0, reps = 9, seconds = null, loadKg = 50.0),
            SetLogRow("BARBELL_BENCH_PRESS", "Bench press", 1, reps = 9, seconds = null, loadKg = 50.0),
            SetLogRow("BARBELL_BENCH_PRESS", "Bench press", 2, reps = 9, seconds = null, loadKg = 50.0),
        )
        val newStates = mapOf(
            "BARBELL_BENCH_PRESS" to ExerciseProgress(
                exerciseId = "BARBELL_BENCH_PRESS",
                loadKg = 50.0,
                reps = 9,
                isEstimated = false,
                lastFeedback = Feedback.TOO_EASY,
            ),
        )

        assertThat(nextTimeDetails(rows, newStates)).isEqualTo("Bench press 3 × 9 @ 50 kg")
    }

    @Test
    fun slui03b_next_time_snackbar_text_joins_multiple_exercises() {
        val rows = listOf(
            SetLogRow("BARBELL_BENCH_PRESS", "Bench press", 0, reps = 9, seconds = null, loadKg = 50.0),
            SetLogRow("BARBELL_BACK_SQUAT", "Squat", 0, reps = 6, seconds = null, loadKg = 42.5),
        )
        val newStates = linkedMapOf(
            "BARBELL_BENCH_PRESS" to ExerciseProgress(exerciseId = "BARBELL_BENCH_PRESS", loadKg = 50.0, reps = 9, isEstimated = false),
            "BARBELL_BACK_SQUAT" to ExerciseProgress(exerciseId = "BARBELL_BACK_SQUAT", loadKg = 42.5, reps = 6, isEstimated = false),
        )

        val text = nextTimeDetails(rows, newStates)
        assertThat(text).contains("Bench press 1 × 9 @ 50 kg")
        assertThat(text).contains("Squat 1 × 6 @ 42.5 kg")
        assertThat(text).contains(";")
    }
}
