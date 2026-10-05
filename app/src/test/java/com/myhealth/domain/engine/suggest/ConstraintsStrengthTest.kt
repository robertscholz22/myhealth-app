package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.MuscleLoadBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * `c17`…`c19` of PLAN §3.12.5 — constraint `C15` and the `muscleBonus` that is its mirror image.
 *
 * Every fixture here supplies `SuggestionInput.muscleLoad`; the same cases with a `null` muscle load
 * are the whole point of `sug39`/`sug40`, and the last test in this file pins that directly.
 */
class ConstraintsStrengthTest {

    private fun violations(candidate: Candidate, input: SuggestionInput): List<ConstraintId> =
        Constraints.violations(candidate, candidate.day, SuggestFixtures.grid(input), ConstraintContext.of(input))

    private fun candidate(sessionType: SessionType, day: Long): Candidate =
        SuggestFixtures.candidate(sessionType, day)

    /** Legs untouched: nothing in the window, so only (b) and (c) can ever fire. */
    private val freshLegs = SuggestFixtures.muscleLoad(ctl = 60.0)

    @Test
    fun c17_lower_strength_blocked_36h_after_a_hard_leg_day() {
        // A 210 AU run yesterday — a hard leg day by §3.12.5 (b)'s 150 AU threshold.
        val hardRun = SuggestFixtures.activity(
            day = day(-1),
            trimp = 210.0,
            sportType = SportType.RUN_OUTDOOR,
        )
        val input = SuggestFixtures.input(recentActivities = listOf(hardRun))
            .copy(muscleLoad = freshLegs)

        // The legs themselves read FRESH here, so this is (b) and nothing else.
        assertThat(input.muscleLoad!!.lowerBody).isEqualTo(MuscleLoadBand.FRESH)
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(0)), input))
            .contains(ConstraintId.C15)
        assertThat(violations(candidate(SessionType.STRENGTH_FULL, day(0)), input))
            .contains(ConstraintId.C15)

        // 36 h is read in whole days: the day after tomorrow is already outside the window.
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(2)), input))
            .doesNotContain(ConstraintId.C15)

        // …and without the hard run there is nothing to respect.
        val quiet = SuggestFixtures.input().copy(muscleLoad = freshLegs)
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(0)), quiet))
            .doesNotContain(ConstraintId.C15)
    }

    @Test
    fun c18_upper_strength_allowed_when_legs_are_loaded() {
        // A 200 AU run today leaves the legs fatigued (25–50 AU against a ref of 14) and the trunk
        // fresh (10 AU) — §3.12.5's canonical "no leg day, upper body is fine" situation.
        val state = SuggestFixtures.muscleLoad(
            sessions = listOf(SuggestFixtures.muscleSession(day(0), trimp = 200.0)),
        )
        val input = SuggestFixtures.input().copy(muscleLoad = state)
        assertThat(state.lowerBody).isEqualTo(MuscleLoadBand.FATIGUED)
        assertThat(state.upperBody).isEqualTo(MuscleLoadBand.FRESH)

        val upper = candidate(SessionType.STRENGTH_UPPER, day(1))
        assertThat(violations(upper, input)).doesNotContain(ConstraintId.C15)
        // …while the leg day on the same day is discarded by (a).
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(1)), input))
            .contains(ConstraintId.C15)

        // The bonus is the scoring half of the same rule: +0.10, unweighted.
        val grid = SuggestFixtures.grid(input)
        val ctx = Scorer.contextOf(input, Periodization.compute(input), remainingBudget = 200.0)
        assertThat(Scorer.muscleBonus(upper, grid, ctx)).isWithin(1e-9).of(StrengthRules.SCORE_BONUS)
        assertThat(Scorer.score(upper, grid, ctx).muscleBonus)
            .isWithin(1e-9).of(StrengthRules.SCORE_BONUS)
        // An easy run is not a strength session and gets nothing.
        assertThat(Scorer.muscleBonus(candidate(SessionType.EASY_RUN, day(1)), grid, ctx))
            .isWithin(1e-9).of(0.0)
    }

    @Test
    fun c19_lower_strength_blocked_48h_before_a_hard_run() {
        // A locked tempo run two days out: (c) extends C2 from matches and races to hard runs.
        val input = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(2), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            ),
        ).copy(muscleLoad = freshLegs)

        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(0)), input))
            .contains(ConstraintId.C15)
        assertThat(violations(candidate(SessionType.STRENGTH_FULL, day(1)), input))
            .contains(ConstraintId.C15)
        // Three days clear of it, in either direction, is fine again.
        assertThat(violations(candidate(SessionType.STRENGTH_LOWER, day(5)), input))
            .doesNotContain(ConstraintId.C15)
        // Upper body is never touched by C15.
        assertThat(violations(candidate(SessionType.STRENGTH_UPPER, day(0)), input))
            .doesNotContain(ConstraintId.C15)
        // And the leg day scores no bonus while a hard run sits in front of it.
        val grid = SuggestFixtures.grid(input)
        val ctx = Scorer.contextOf(input, Periodization.compute(input), remainingBudget = 200.0)
        assertThat(Scorer.muscleBonus(candidate(SessionType.STRENGTH_LOWER, day(0)), grid, ctx))
            .isWithin(1e-9).of(0.0)
        assertThat(Scorer.muscleBonus(candidate(SessionType.STRENGTH_LOWER, day(5)), grid, ctx))
            .isWithin(1e-9).of(StrengthRules.SCORE_BONUS)
    }

    @Test
    fun c15_is_inert_without_muscle_load() {
        val hardRun = SuggestFixtures.activity(
            day = day(-1),
            trimp = 210.0,
            sportType = SportType.RUN_OUTDOOR,
        )
        val input = SuggestFixtures.input(recentActivities = listOf(hardRun))

        assertThat(input.muscleLoad).isNull()
        SuggestFixtures.grid(input)
        listOf(SessionType.STRENGTH_LOWER, SessionType.STRENGTH_FULL, SessionType.STRENGTH_UPPER)
            .forEach { type ->
                assertThat(violations(candidate(type, day(0)), input)).doesNotContain(ConstraintId.C15)
            }
        val ctx = Scorer.contextOf(input, Periodization.compute(input), remainingBudget = 200.0)
        assertThat(Scorer.muscleBonus(candidate(SessionType.STRENGTH_UPPER, day(1)), SuggestFixtures.grid(input), ctx))
            .isWithin(1e-9).of(0.0)
    }
}
