package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.CyclePhase
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [SuggestionEngine] against the cycle-aware rules of PLAN §5 P11.2 (`sug21`–`sug25`).
 *
 * Every fixture states "today is cycle day N" by logging one period start N − 1 days ago and
 * letting the real [com.myhealth.domain.engine.cycle.CycleEngine] derive the phases — so these
 * tests break if either half of P11 drifts. Split out of [SuggestionEngineTest] only to keep both
 * files inside the ~400-line rule R10.
 */
class SuggestionEngineCycleTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private val hard = setOf(Intensity.HIGH, Intensity.MAX)

    private fun List<SuggestedSession>.on(day: Long): List<SuggestedSession> = filter { it.day == day }

    private val busyHistory = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0)

    /**
     * A week with a real reason to train hard: a 5k race 30 days out puts the athlete in `PEAK`,
     * where intervals and tempo runs are the preferred work. Without a goal the engine has no
     * reason to schedule anything above `MODERATE`, and a cap on hard work would prove nothing.
     */
    private fun input(horizonDays: Int = 7, cycle: Map<Long, CycleStatus> = emptyMap()) =
        SuggestFixtures.input(
            horizonDays = horizonDays,
            goals = listOf(SuggestFixtures.raceGoal(day(30))),
            recentLoad = busyHistory,
            cycleStatusByDay = cycle,
        )

    private fun List<SuggestedSession>.ruleIds(): List<String> = flatMap { it.rationale }.map { it.ruleId }

    private fun List<SuggestedSession>.hard(): List<SuggestedSession> = filter { it.intensity in hard }

    @Test
    fun sug21_menstrual_first_two_days_cap_moderate() {
        // Today is cycle day 1: days 1-2 are capped at MODERATE, days 3-5 may take hard work.
        val result = engine.generate(input(cycle = SuggestFixtures.cycleStatuses(offset = 0)))
        val without = engine.generate(input())

        assertThat(result.sessions.on(day(0)).hard()).isEmpty()
        assertThat(result.sessions.on(day(1)).hard()).isEmpty()
        // The cap is what kept those two days easy, not an empty week: hard work still happens,
        // and the same week without cycle data does put some of it inside the capped window.
        assertThat(result.sessions.hard()).isNotEmpty()
        assertThat(without.sessions.hard().map { it.day }).containsAnyOf(day(0), day(1))
        assertThat(result.sessions.hard().none { it.day == day(0) || it.day == day(1) }).isTrue()
        // Every menstrual day explains itself.
        result.sessions.on(day(0)).forEach {
            assertThat(it.rationale.map { entry -> entry.ruleId })
                .contains(CycleRules.RULE_MENSTRUAL_EARLY)
        }
        // LOW confidence (one logged start) keeps the rule and says where the numbers come from.
        val menstrual = result.sessions.filter { it.day <= day(4) }.flatMap { it.rationale }
            .filter { it.ruleId == CycleRules.RULE_MENSTRUAL_EARLY }
        assertThat(menstrual).isNotEmpty()
        menstrual.forEach { assertThat(it.text).contains("log your period to improve this") }
    }

    @Test
    fun sug22_follicular_prefers_strength_and_intervals() {
        // Today is cycle day 6: the whole 7-day horizon is follicular.
        val statuses = SuggestFixtures.cycleStatuses(offset = -5)
        assertThat(statuses.values.map { it.phase }.toSet()).containsExactly(CyclePhase.FOLLICULAR)

        val withCycle = engine.generate(input(cycle = statuses))
        val without = engine.generate(input())

        fun List<SuggestedSession>.favoured(): Int = count {
            it.sessionType.name.startsWith("STRENGTH") ||
                (it.sportType.group == SportGroup.RUN && it.intensity in hard)
        }
        assertThat(withCycle.sessions.favoured()).isAtLeast(without.sessions.favoured())
        assertThat(withCycle.sessions.favoured()).isAtLeast(1)
        assertThat(withCycle.sessions.ruleIds()).contains(CycleRules.RULE_FOLLICULAR)

        // The nudge itself: exactly +0.10 for the work the phase suits, nothing for the rest.
        val grid = SuggestFixtures.grid(input())
        val ctx = Scorer.contextOf(input(cycle = statuses), Periodization.compute(input()), 300.0)
        val strength = SuggestFixtures.candidate(SessionType.STRENGTH_FULL, day(0))
        val easy = SuggestFixtures.candidate(SessionType.EASY_RUN, day(0))
        assertThat(Scorer.score(strength, grid, ctx).cycleBonus)
            .isWithin(1e-9).of(CycleRules.PHASE_SCORE_BONUS)
        assertThat(Scorer.score(easy, grid, ctx).cycleBonus).isWithin(1e-9).of(0.0)
    }

    @Test
    fun sug23_ovulation_caps_max_to_high() {
        // Today is cycle day 13: days +0..+2 are the ovulation window of a 28-day cycle.
        val statuses = SuggestFixtures.cycleStatuses(offset = -12)
        assertThat(statuses.getValue(day(0)).phase).isEqualTo(CyclePhase.OVULATION)
        assertThat(statuses.getValue(day(2)).phase).isEqualTo(CyclePhase.OVULATION)
        assertThat(statuses.getValue(day(3)).phase).isEqualTo(CyclePhase.LUTEAL)

        // The cap itself: MAX is refused inside the window, HIGH is not.
        val ovulation = statuses.getValue(day(0))
        assertThat(CycleRules.violatesOvulationCap(ovulation, Intensity.MAX)).isTrue()
        assertThat(CycleRules.violatesOvulationCap(ovulation, Intensity.HIGH)).isFalse()
        assertThat(CycleRules.violatesOvulationCap(statuses.getValue(day(3)), Intensity.MAX)).isFalse()

        val result = engine.generate(input(cycle = statuses))
        listOf(0L, 1L, 2L).forEach { offset ->
            assertThat(result.sessions.on(day(offset)).map { it.intensity })
                .doesNotContain(Intensity.MAX)
        }
        val inWindow = result.sessions.filter { it.day <= day(2) }
        assertThat(inWindow).isNotEmpty()
        val warmUp = inWindow.flatMap { it.rationale }.filter { it.ruleId == CycleRules.RULE_OVULATION }
        assertThat(warmUp).isNotEmpty()
        warmUp.forEach { assertThat(it.text).contains("warm-up") }
    }

    @Test
    fun sug24_late_luteal_limits_high_and_reduces_target() {
        // Today is cycle day 24: a 5-day horizon sits entirely inside the late-luteal window.
        val statuses = SuggestFixtures.cycleStatuses(offset = -23, horizonDays = 5)
        assertThat(statuses.values.all { it.isLateLuteal }).isTrue()

        val withCycle = engine.generate(input(horizonDays = 5, cycle = statuses))
        val without = engine.generate(input(horizonDays = 5))

        assertThat(withCycle.weeklyTarget)
            .isWithin(1e-9).of(without.weeklyTarget * CycleRules.LATE_LUTEAL_TARGET_FACTOR)
        assertThat(withCycle.sessions.hard().size).isAtMost(CycleRules.LATE_LUTEAL_MAX_HARD)
        assertThat(withCycle.sessions).isNotEmpty()
        val lines = withCycle.sessions.flatMap { it.rationale }
            .filter { it.ruleId == CycleRules.RULE_LATE_LUTEAL }
        assertThat(lines).isNotEmpty()
        lines.forEach { assertThat(it.text).contains("sleep and hydration") }
    }

    @Test
    fun sug25_no_cycle_data_unchanged() {
        val baseline = engine.generate(SuggestFixtures.input(recentLoad = busyHistory))
        val explicitlyEmpty = engine.generate(
            SuggestFixtures.input(recentLoad = busyHistory, cycleStatusByDay = emptyMap()),
        )


        assertThat(explicitlyEmpty.sessions).isEqualTo(baseline.sessions)
        assertThat(explicitlyEmpty.weeklyTarget).isEqualTo(baseline.weeklyTarget)
        assertThat(explicitlyEmpty.inputsHash).isEqualTo(baseline.inputsHash)
        assertThat(baseline.sessions.ruleIds().none { it.startsWith("CYCLE_") }).isTrue()

        // …and cycle data really is part of the hash, so a new logged period regenerates.
        val tracked = engine.generate(
            SuggestFixtures.input(
                recentLoad = busyHistory,
                cycleStatusByDay = SuggestFixtures.cycleStatuses(offset = 0),
            ),
        )
        assertThat(tracked.inputsHash).isNotEqualTo(baseline.inputsHash)
    }
}
