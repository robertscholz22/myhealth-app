package com.myhealth.domain.engine.strength

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Feedback
import org.junit.Test

/**
 * The load progression of PLAN §P16 (`pg01`…`pg12`), with every value pinned.
 *
 * The two roundings this file fixes for good:
 * - the **initial** estimate is floored to the equipment increment (`0.40 × 78 = 31.2 → 30.0`,
 *   and `0.40 × 80 = 32.0 → 30.0` — §P16's own "32.5? no" note);
 * - every **later** load moves by `max(percentage, one increment)` and is then rounded half-up to
 *   the increment (`40 + max(2.0, 2.5) = 42.5`).
 */
class ProgressionEngineTest {

    private val squat = exercise("BARBELL_BACK_SQUAT")
    private val bench = exercise("BARBELL_BENCH_PRESS")
    private val pushUp = exercise("PUSH_UP")
    private val plank = exercise("PLANK")

    @Test
    fun pg01_initial_squat_80kg_body_gives_40kg_5_reps() {
        val state = ProgressionEngine.initial(squat, bodyWeightKg = 80.0, day = DAY)

        // 0.50 × 80 = 40.0, already a multiple of the 2.5 kg barbell increment.
        assertThat(state.loadKg).isWithin(EPS).of(40.0)
        assertThat(state.reps).isEqualTo(5)
        assertThat(state.seconds).isNull()
        assertThat(state.isEstimated).isTrue()
        assertThat(state.lastFeedback).isNull()
        assertThat(state.updatedDay).isEqualTo(DAY)

        val prescription = ProgressionEngine.prescription(squat, state = null, bodyWeightKg = 80.0)
        assertThat(prescription.loadKg).isWithin(EPS).of(40.0)
        assertThat(prescription.reps).isEqualTo(5)
        assertThat(prescription.isEstimated).isTrue()
        assertThat(prescription.perHand).isFalse()
    }

    @Test
    fun pg02_initial_rounds_down_to_barbell_increment() {
        // 0.40 × 78 = 31.2 → floor to 2.5 → 30.0 (not 31.2, not 32.5).
        assertThat(ProgressionEngine.initial(bench, bodyWeightKg = 78.0).loadKg)
            .isWithin(EPS).of(30.0)
        // 0.40 × 80 = 32.0 → floor to 2.5 → 30.0.
        assertThat(ProgressionEngine.initial(bench, bodyWeightKg = 80.0).loadKg)
            .isWithin(EPS).of(30.0)
        assertThat(ProgressionEngine.initial(bench, bodyWeightKg = 78.0).reps).isEqualTo(5)
    }

    @Test
    fun pg03_too_easy_adds_two_reps() {
        val state = loaded(squat.id, loadKg = 40.0, reps = 5)

        val next = ProgressionEngine.next(state, Feedback.TOO_EASY, squat, day = DAY + 1)

        assertThat(next.reps).isEqualTo(7)
        assertThat(next.loadKg).isWithin(EPS).of(40.0)
        assertThat(next.lastFeedback).isEqualTo(Feedback.TOO_EASY)
        assertThat(next.updatedDay).isEqualTo(DAY + 1)
    }

    @Test
    fun pg04_too_easy_at_top_adds_5pct_and_resets_reps() {
        // 8 is the top of the barbell compound range 5–8: 8 + 2 = 10 exceeds it.
        val state = loaded(squat.id, loadKg = 40.0, reps = 8)

        val next = ProgressionEngine.next(state, Feedback.TOO_EASY, squat)

        // 5 % of 40 is 2.0, below the 2.5 kg increment, so the step is one increment: 42.5.
        assertThat(next.loadKg).isWithin(EPS).of(42.5)
        assertThat(next.reps).isEqualTo(5)
    }

    @Test
    fun pg05_easy_adds_one_rep_then_2_5pct() {
        val below = ProgressionEngine.next(loaded(squat.id, 100.0, reps = 5), Feedback.EASY, squat)
        assertThat(below.reps).isEqualTo(6)
        assertThat(below.loadKg).isWithin(EPS).of(100.0)

        // At the top of 5–8 the load moves by 2.5 % — half of what TOO_EASY would add.
        val atTop = ProgressionEngine.next(loaded(squat.id, 100.0, reps = 8), Feedback.EASY, squat)
        assertThat(atTop.loadKg).isWithin(EPS).of(102.5)
        assertThat(atTop.reps).isEqualTo(5)
        assertThat(ProgressionEngine.next(loaded(squat.id, 100.0, reps = 8), Feedback.TOO_EASY, squat).loadKg)
            .isWithin(EPS).of(105.0)
    }

    @Test
    fun pg06_hard_unchanged() {
        val state = loaded(squat.id, loadKg = 42.5, reps = 6)

        val next = ProgressionEngine.next(state, Feedback.HARD, squat, day = DAY + 3)

        assertThat(next.loadKg).isWithin(EPS).of(42.5)
        assertThat(next.reps).isEqualTo(6)
        assertThat(next.lastFeedback).isEqualTo(Feedback.HARD)
        assertThat(next.updatedDay).isEqualTo(DAY + 3)
    }

    @Test
    fun pg07_too_hard_minus_5pct_min_one_increment() {
        // 5 % of 40 is 2.0 → at least one increment → 37.5, reps untouched.
        val next = ProgressionEngine.next(loaded(squat.id, 40.0, reps = 6), Feedback.TOO_HARD, squat)
        assertThat(next.loadKg).isWithin(EPS).of(37.5)
        assertThat(next.reps).isEqualTo(6)

        // 5 % of 100 is 5.0, more than one increment, so the percentage wins.
        assertThat(ProgressionEngine.next(loaded(squat.id, 100.0, reps = 6), Feedback.TOO_HARD, squat).loadKg)
            .isWithin(EPS).of(95.0)

        // Never below one increment — an empty bar stays an empty bar.
        assertThat(ProgressionEngine.next(loaded(squat.id, 2.5, reps = 6), Feedback.TOO_HARD, squat).loadKg)
            .isWithin(EPS).of(2.5)
    }

    @Test
    fun pg08_bodyweight_progresses_reps_only() {
        // Push-ups are 8–12 (a compound pattern without a barbell) and carry no load at all.
        val state = loaded(pushUp.id, loadKg = null, reps = 10)

        val next = ProgressionEngine.next(state, Feedback.TOO_EASY, pushUp)
        assertThat(next.reps).isEqualTo(12)
        assertThat(next.loadKg).isNull()

        // At the top there is nothing to add load to, so it stays at the top.
        val atTop = ProgressionEngine.next(loaded(pushUp.id, null, reps = 12), Feedback.TOO_EASY, pushUp)
        assertThat(atTop.reps).isEqualTo(12)
        assertThat(atTop.loadKg).isNull()

        // TOO_HARD takes two reps off, never below the bottom of the range.
        assertThat(ProgressionEngine.next(loaded(pushUp.id, null, reps = 10), Feedback.TOO_HARD, pushUp).reps)
            .isEqualTo(8)
        assertThat(ProgressionEngine.next(loaded(pushUp.id, null, reps = 8), Feedback.TOO_HARD, pushUp).reps)
            .isEqualTo(8)
    }

    @Test
    fun pg09_timed_progresses_seconds() {
        val initial = ProgressionEngine.initial(plank, bodyWeightKg = 80.0)
        assertThat(initial.seconds).isEqualTo(30)
        assertThat(initial.reps).isNull()
        assertThat(initial.loadKg).isNull()

        val held = ExerciseProgress(exerciseId = plank.id, seconds = 40, isEstimated = false)
        assertThat(ProgressionEngine.next(held, Feedback.TOO_EASY, plank).seconds).isEqualTo(50)
        assertThat(ProgressionEngine.next(held, Feedback.EASY, plank).seconds).isEqualTo(45)
        assertThat(ProgressionEngine.next(held, Feedback.TOO_HARD, plank).seconds).isEqualTo(30)
        // The hold range is 30–60 s: the top holds and the bottom floors.
        assertThat(ProgressionEngine.next(held.copy(seconds = 60), Feedback.TOO_EASY, plank).seconds)
            .isEqualTo(60)
        assertThat(ProgressionEngine.next(held.copy(seconds = 30), Feedback.TOO_HARD, plank).seconds)
            .isEqualTo(30)
    }

    @Test
    fun pg10_feedback_clears_estimated() {
        val estimated = ProgressionEngine.initial(squat, bodyWeightKg = 80.0, day = DAY)
        assertThat(estimated.isEstimated).isTrue()

        // Even HARD, which changes no number, confirms the numbers.
        val confirmed = ProgressionEngine.next(estimated, Feedback.HARD, squat, day = DAY + 2)
        assertThat(confirmed.isEstimated).isFalse()
        assertThat(confirmed.loadKg).isWithin(EPS).of(40.0)
        assertThat(ProgressionEngine.prescription(squat, confirmed, bodyWeightKg = 80.0).isEstimated)
            .isFalse()
    }

    @Test
    fun pg11_increment_per_equipment() {
        assertThat(ProgressionDefaults.incrementKg(Equipment.BARBELL)).isWithin(EPS).of(2.5)
        assertThat(ProgressionDefaults.incrementKg(Equipment.MACHINE)).isWithin(EPS).of(2.5)
        assertThat(ProgressionDefaults.incrementKg(Equipment.CABLE)).isWithin(EPS).of(2.5)
        assertThat(ProgressionDefaults.incrementKg(Equipment.DUMBBELL)).isWithin(EPS).of(1.0)
        assertThat(ProgressionDefaults.incrementKg(Equipment.KETTLEBELL)).isWithin(EPS).of(1.0)
        assertThat(ProgressionDefaults.incrementKg(Equipment.BAND)).isWithin(EPS).of(1.0)
        assertThat(ProgressionDefaults.incrementKg(Equipment.MEDICINE_BALL)).isWithin(EPS).of(1.0)

        // A dumbbell press therefore floors to a whole kilo: 0.15 × 78 = 11.7 → 11.0.
        val incline = exercise("INCLINE_DUMBBELL_PRESS")
        assertThat(ProgressionEngine.initial(incline, bodyWeightKg = 78.0).loadKg).isWithin(EPS).of(11.0)
        // … and a step on it is 1 kg, not 2.5: 5 % of 11 is 0.55 → one increment → 12.0.
        val atTop = loaded(incline.id, loadKg = 11.0, reps = 12)
        assertThat(ProgressionEngine.next(atTop, Feedback.TOO_EASY, incline).loadKg).isWithin(EPS).of(12.0)
    }

    @Test
    fun pg12_dumbbell_is_per_hand() {
        val dumbbellRow = exercise("DUMBBELL_ROW")
        val barbellRow = exercise("BARBELL_ROW")

        // 0.15 × 80 = 12.0 — the weight of ONE dumbbell, not the pair.
        val perHand = ProgressionEngine.prescription(dumbbellRow, state = null, bodyWeightKg = 80.0)
        assertThat(perHand.loadKg).isWithin(EPS).of(12.0)
        assertThat(perHand.perHand).isTrue()
        assertThat(perHand.reps).isEqualTo(8)

        // The barbell row is a total: 0.35 × 80 = 28.0 → floor to 2.5 → 27.5.
        val total = ProgressionEngine.prescription(barbellRow, state = null, bodyWeightKg = 80.0)
        assertThat(total.loadKg).isWithin(EPS).of(27.5)
        assertThat(total.perHand).isFalse()
        assertThat(total.reps).isEqualTo(5)

        // A bodyweight pull has no load, so "per hand" is meaningless and stays false.
        assertThat(ProgressionEngine.prescription(pushUp, null, 80.0).perHand).isFalse()
    }

    private fun exercise(id: String) = checkNotNull(ExerciseCatalog.byId(id)) { "no $id in the catalog" }

    private fun loaded(exerciseId: String, loadKg: Double?, reps: Int) = ExerciseProgress(
        exerciseId = exerciseId,
        loadKg = loadKg,
        reps = reps,
        isEstimated = false,
        updatedDay = DAY,
    )

    private companion object {
        const val EPS = 1e-9
        const val DAY = 20_712L
    }
}
