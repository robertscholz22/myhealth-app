package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.load.TrimpDefaults
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * [Periodization] against PLAN §3.5.2 — the phase table, the factors, the 25 % ramp cap and the
 * ACWR / recovery multipliers. Named cases `sug09`–`sug12`, `sug17` and `per01`–`per03` come from
 * §3.5.7 / P6.2; `per04`–`per07` cover the remaining branches of the same section, including the
 * POLISH-10 starter-week target (`per04`). `bug21_*` pin the BUG-21 fix (averaged levels, the
 * accepted down week, acute overload against the heaviest recent level).
 */
class PeriodizationTest {

    private val ctl = 40.0
    private val weeklyBase = ctl * 7 // 280 AU

    @Test
    fun sug09_taper_reduces_weekly_target_to_60_percent() {
        val target = Periodization.weeklyTarget(
            phase = TrainingPhase.TAPER,
            ctl = ctl,
            lastWeekActual = 400.0,
            acwr = 1.0,
            band = null,
        )
        assertThat(target).isWithin(0.01).of(weeklyBase * 0.60)
        assertThat(target).isWithin(0.01).of(168.0)
    }

    @Test
    fun sug10_race_week_phase_detected_at_seven_days() {
        assertThat(Periodization.phase(daysToRace = 7L, matchWithin21Days = false))
            .isEqualTo(TrainingPhase.RACE_WEEK)
        assertThat(Periodization.phase(daysToRace = 8L, matchWithin21Days = false))
            .isEqualTo(TrainingPhase.TAPER)
    }

    @Test
    fun sug11_a_down_week_only_when_the_load_history_calls_for_one() {
        // P19.6 replaced "every 4th week since plan start" with the load-history check.
        assertThat(Periodization.phase(daysToRace = null, matchWithin21Days = false, downWeek = true))
            .isEqualTo(TrainingPhase.RECOVERY_WEEK)
        assertThat(Periodization.phase(daysToRace = null, matchWithin21Days = false, downWeek = false))
            .isEqualTo(TrainingPhase.BASE)
        // …and it never overrides a taper or race week.
        assertThat(Periodization.phase(daysToRace = 9L, matchWithin21Days = false, downWeek = true))
            .isEqualTo(TrainingPhase.TAPER)
        assertThat(Periodization.phase(daysToRace = 4L, matchWithin21Days = false, downWeek = true))
            .isEqualTo(TrainingPhase.RACE_WEEK)
    }

    @Test
    fun sug12_weekly_target_never_ramps_more_than_25_percent() {
        val target = Periodization.weeklyTarget(
            phase = TrainingPhase.BUILD,
            ctl = 100.0, // 700 AU * 1.30 = 910 AU without the cap
            lastWeekActual = 600.0,
            acwr = null,
            band = null,
        )
        assertThat(target).isAtMost(750.0)
        assertThat(target).isWithin(0.01).of(750.0)
        // BUG-21: after a light week the cap still allows the usual week (700), not 1.25 × 400.
        assertThat(Periodization.weeklyTarget(TrainingPhase.BUILD, 100.0, 400.0, null, null))
            .isWithin(0.01).of(700.0)
    }

    @Test
    fun sug17_in_season_phase_when_match_within_21_days() {
        val input = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(20), EventType.SOCCER_MATCH)),
        )
        assertThat(Periodization.compute(input).phase).isEqualTo(TrainingPhase.IN_SEASON)

        val tooFar = SuggestFixtures.input(
            events = listOf(SuggestFixtures.event(day(22), EventType.SOCCER_MATCH)),
        )
        assertThat(Periodization.compute(tooFar).phase).isEqualTo(TrainingPhase.BASE)
    }

    @Test
    fun per01_no_goal_no_match_is_base() {
        val result = Periodization.compute(SuggestFixtures.input())
        assertThat(result.phase).isEqualTo(TrainingPhase.BASE)
        assertThat(result.daysToRace).isNull()
        // ctl 40 -> 280 AU * 1.20 = 336, under the ramp cap of max(7*40*1.25, 150) = 350.
        assertThat(result.weeklyTarget).isWithin(0.01).of(336.0)
    }

    @Test
    fun per02_phase_boundaries_exact_days() {
        val expected = mapOf(
            0L to TrainingPhase.RACE_WEEK,
            7L to TrainingPhase.RACE_WEEK,
            8L to TrainingPhase.TAPER,
            10L to TrainingPhase.TAPER,
            11L to TrainingPhase.PEAK,
            35L to TrainingPhase.PEAK,
            36L to TrainingPhase.BUILD,
            77L to TrainingPhase.BUILD,
            78L to TrainingPhase.BASE,
        )
        expected.forEach { (days, phase) ->
            assertThat(Periodization.phase(days, matchWithin21Days = false))
                .isEqualTo(phase)
        }
    }

    @Test
    fun per03_strained_recovery_scales_target_to_60_percent() {
        val fresh = Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.0, RecoveryBand.GOOD)
        val strained = Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.0, RecoveryBand.STRAINED)
        assertThat(strained).isWithin(0.01).of(fresh * 0.60)

        val fatigued = Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.0, RecoveryBand.FATIGUED)
        assertThat(fatigued).isWithin(0.01).of(fresh * 0.85)
    }

    @Test
    fun per04_starter_target_when_no_history() {
        // POLISH-10: CTL < 5 and nothing logged last week (a brand-new athlete, or right after the
        // first sync) must not collapse the target to ~0 — it gets the 150 AU starter target.
        assertThat(Periodization.isStarterWeek(ctl = 0.0, lastWeekActual = 0.0)).isTrue()
        assertThat(Periodization.isStarterWeek(ctl = 4.9, lastWeekActual = 0.0)).isTrue()
        // ctl >= 5 (even barely) is "has some history" and skips the starter branch.
        assertThat(Periodization.isStarterWeek(ctl = 5.0, lastWeekActual = 0.0)).isFalse()
        // Any load logged last week means there is history, starter or not.
        assertThat(Periodization.isStarterWeek(ctl = 2.0, lastWeekActual = 10.0)).isFalse()

        val target = Periodization.weeklyTarget(
            phase = TrainingPhase.BASE,
            ctl = 0.0,
            lastWeekActual = 0.0,
            acwr = null,
            band = null,
        )
        assertThat(target).isWithin(0.01).of(Periodization.STARTER_TARGET_AU)
        assertThat(target).isWithin(0.01).of(150.0)

        // The starter target still yields to a strained/fatigued recovery signal (R13: never raise).
        val strained = Periodization.weeklyTarget(TrainingPhase.BASE, 0.0, 0.0, null, RecoveryBand.STRAINED)
        assertThat(strained).isWithin(0.01).of(150.0 * 0.60)
    }

    @Test
    fun per07_high_acwr_scales_target_to_75_percent() {
        val calm = Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.4, null)
        val spiking = Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.6, null)
        assertThat(spiking).isWithin(0.01).of(calm * 0.75)
        // 1.5 exactly is not "> 1.5" and must not be penalised.
        assertThat(Periodization.weeklyTarget(TrainingPhase.BASE, ctl, 400.0, 1.5, null))
            .isWithin(0.01).of(calm)
    }

    @Test
    fun per05_ramp_cap_has_a_150_au_floor_for_a_returning_athlete() {
        // No load at all last week: the cap must not be 0, it is the usual week (BUG-21) …
        val target = Periodization.weeklyTarget(TrainingPhase.BASE, ctl = 60.0, lastWeekActual = 0.0, acwr = null, band = null)
        assertThat(target).isWithin(0.01).of(420.0)
        // … and never below the 150 AU floor: usual week 140, BASE would be 168.
        val low = Periodization.weeklyTarget(TrainingPhase.BASE, ctl = 20.0, lastWeekActual = 0.0, acwr = null, band = null)
        assertThat(low).isWithin(0.01).of(150.0)
    }

    @Test
    fun per06_every_phase_factor_matches_the_plan_table() {
        // P19.6: raised from 1.05 / 1.10 / 1.05 so that training at the target builds CTL.
        assertThat(Periodization.factorFor(TrainingPhase.BASE)).isWithin(1e-9).of(1.20)
        assertThat(Periodization.factorFor(TrainingPhase.BUILD)).isWithin(1e-9).of(1.30)
        assertThat(Periodization.factorFor(TrainingPhase.PEAK)).isWithin(1e-9).of(1.15)
        assertThat(Periodization.factorFor(TrainingPhase.TAPER)).isWithin(1e-9).of(0.60)
        assertThat(Periodization.factorFor(TrainingPhase.RACE_WEEK)).isWithin(1e-9).of(0.45)
        assertThat(Periodization.factorFor(TrainingPhase.IN_SEASON)).isWithin(1e-9).of(1.00)
        assertThat(Periodization.factorFor(TrainingPhase.OFF_SEASON)).isWithin(1e-9).of(0.80)
        assertThat(Periodization.factorFor(TrainingPhase.RECOVERY_WEEK)).isWithin(1e-9).of(0.65)
    }

    @Test
    fun a_race_goal_drives_the_phase_and_a_past_race_is_ignored() {
        val upcoming = SuggestFixtures.input(goals = listOf(SuggestFixtures.raceGoal(day(20))))
        assertThat(Periodization.compute(upcoming).phase).isEqualTo(TrainingPhase.PEAK)
        assertThat(Periodization.compute(upcoming).daysToRace).isEqualTo(20L)

        val past = SuggestFixtures.input(goals = listOf(SuggestFixtures.raceGoal(day(-3))))
        assertThat(Periodization.compute(past).phase).isEqualTo(TrainingPhase.BASE)
        assertThat(Periodization.compute(past).daysToRace).isNull()
    }

    @Test
    fun dw01_steady_training_with_a_recent_lighter_week_is_a_normal_week() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 0.6, 1.0))
        assertThat(Periodization.downWeekReason(history, SuggestFixtures.TODAY_DAY, ctl)).isNull()
        assertThat(Periodization.compute(SuggestFixtures.input()).downWeekReason).isNull()
        assertThat(Periodization.compute(SuggestFixtures.input()).phase).isEqualTo(TrainingPhase.BASE)
    }

    @Test
    fun dw02_five_weeks_without_a_lighter_week_is_a_long_build() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.0, 0.9, 1.1, 0.85, 1.0))
        assertThat(Periodization.downWeekReason(history, SuggestFixtures.TODAY_DAY, ctl))
            .isEqualTo(DownWeekReason.LONG_BUILD)
        val result = Periodization.compute(SuggestFixtures.input(recentLoad = history))
        assertThat(result.phase).isEqualTo(TrainingPhase.RECOVERY_WEEK)
        assertThat(result.downWeekReason).isEqualTo(DownWeekReason.LONG_BUILD)
        // 40 * 7 * 0.65
        assertThat(result.weeklyTarget).isWithin(0.01).of(182.0)
    }

    @Test
    fun dw03_any_lighter_week_in_the_last_five_resets_the_count() {
        // Below 0.8 of the usual week: a holiday, a sick week or a down week the user took anyway.
        listOf(0, 2, 4).forEach { light ->
            val weeks = MutableList(5) { 1.0 }.also { it[light] = 0.79 }
            assertThat(Periodization.downWeekReason(SuggestFixtures.weeklyHistory(weeks), SuggestFixtures.TODAY_DAY, ctl))
                .isNull()
        }
        // 0.8 exactly is not lighter.
        val weeks = listOf(1.0, 1.0, 0.8, 1.0, 1.0)
        assertThat(Periodization.downWeekReason(SuggestFixtures.weeklyHistory(weeks), SuggestFixtures.TODAY_DAY, ctl))
            .isEqualTo(DownWeekReason.LONG_BUILD)
    }

    @Test
    fun dw04_a_short_history_never_counts_as_a_long_build() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0))
        assertThat(Periodization.downWeekReason(history, SuggestFixtures.TODAY_DAY, ctl)).isNull()
        // Five weeks of rows, but the oldest week starts after the first activity: still too short.
        val partial = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 1.0)).drop(1)
        assertThat(Periodization.downWeekReason(partial, SuggestFixtures.TODAY_DAY, ctl)).isNull()
    }

    @Test
    fun dw05_last_week_above_one_and_a_half_times_the_usual_week_is_acute_overload() {
        val over = SuggestFixtures.weeklyHistory(listOf(1.55, 0.6))
        assertThat(Periodization.downWeekReason(over, SuggestFixtures.TODAY_DAY, ctl))
            .isEqualTo(DownWeekReason.ACUTE_OVERLOAD)
        val atLimit = SuggestFixtures.weeklyHistory(listOf(1.5, 0.6))
        assertThat(Periodization.downWeekReason(atLimit, SuggestFixtures.TODAY_DAY, ctl)).isNull()
    }

    @Test
    fun dw06_three_fatigued_or_strained_days_last_week_call_for_a_down_week() {
        val three = listOf(RecoveryBand.FATIGUED, RecoveryBand.GOOD, RecoveryBand.STRAINED, null, RecoveryBand.FATIGUED)
        assertThat(
            Periodization.downWeekReason(
                SuggestFixtures.weeklyHistory(listOf(1.0, 0.6), bandsLastWeek = three), SuggestFixtures.TODAY_DAY, ctl,
            ),
        ).isEqualTo(DownWeekReason.FATIGUE)
        // Two bad days are the daily recovery multiplier's business, not a down week.
        val two = listOf(RecoveryBand.FATIGUED, RecoveryBand.GOOD, RecoveryBand.STRAINED, RecoveryBand.MODERATE)
        assertThat(
            Periodization.downWeekReason(
                SuggestFixtures.weeklyHistory(listOf(1.0, 0.6), bandsLastWeek = two), SuggestFixtures.TODAY_DAY, ctl,
            ),
        ).isNull()
    }

    @Test
    fun dw07_three_weeks_averaging_above_1_4_is_a_big_build() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.45, 1.4, 1.45, 0.6))
        assertThat(Periodization.downWeekReason(history, SuggestFixtures.TODAY_DAY, ctl))
            .isEqualTo(DownWeekReason.BIG_BUILD)
        // Training at the BUILD target (≈ 1.3) is the plan, not a reason to back off.
        val onPlan = SuggestFixtures.weeklyHistory(listOf(1.3, 1.3, 1.3, 0.6))
        assertThat(Periodization.downWeekReason(onPlan, SuggestFixtures.TODAY_DAY, ctl)).isNull()
    }

    @Test
    fun dw08_no_down_week_for_a_starter_and_none_in_taper() {
        val flat = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 1.0), ctl = 4.0)
        assertThat(Periodization.downWeekReason(flat, SuggestFixtures.TODAY_DAY, ctl = 4.0)).isNull()

        val longBuild = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 1.0))
        val taper = Periodization.compute(
            SuggestFixtures.input(recentLoad = longBuild, goals = listOf(SuggestFixtures.raceGoal(day(9)))),
        )
        assertThat(taper.phase).isEqualTo(TrainingPhase.TAPER)
        assertThat(taper.downWeekReason).isNull()
    }

    @Test
    fun dw09_the_reason_reaches_every_session_of_the_week() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 1.0))
        val result = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))
            .generate(SuggestFixtures.input(recentLoad = history))
        assertThat(result.phase).isEqualTo(TrainingPhase.RECOVERY_WEEK)
        // Active-recovery fillers carry their own two lines; every budgeted session names the reason.
        val budgeted = result.sessions.filter { s -> s.rationale.any { it.ruleId == Rationale.RULE_BUDGET } }
        assertThat(budgeted).isNotEmpty()
        budgeted.forEach { session ->
            assertThat(session.rationale.map { it.text })
                .contains("Recovery week: 5 weeks of build-up without a lighter week.")
        }
    }

    /**
     * BUG-21: rows from a real CTL EWMA over a weekly pattern, so CTL saw-tooths as in real
     * training — up on a session day, down ~7 % on every rest day. Week `wb` (1 = last week) starts at
     * `TODAY_DAY − 7·wb`; [pattern] maps a week to its sessions by day-in-week. 18 weeks of warm-up
     * history are computed, the last 42 days returned (what the repository passes).
     */
    private fun ewmaHistory(pattern: (weeksBack: Int) -> Map<Int, Double>): List<DailyLoad> {
        var ctl = 0.0
        return (18 * 7 downTo 1).map { back ->
            val weeksBack = (back - 1) / 7 + 1
            val trimp = pattern(weeksBack)[7 * weeksBack - back] ?: 0.0
            ctl += TrimpDefaults.LAMBDA_CTL * (trimp - ctl)
            SuggestFixtures.load(day = SuggestFixtures.TODAY_DAY - back, trimp = trimp, atl = ctl, ctl = ctl)
        }.takeLast(42)
    }

    private val soccerAndRun = mapOf(0 to 180.0, 2 to 100.0)

    @Test
    fun bug21_a_soccer_only_week_is_lighter_whatever_the_weekday() {
        val steady = ewmaHistory { soccerAndRun }
        val ctlNow = steady.last().ctl
        assertThat(Periodization.downWeekReason(steady, SuggestFixtures.TODAY_DAY, ctlNow))
            .isEqualTo(DownWeekReason.LONG_BUILD)
        // Three weeks ago only the soccer session: 180 AU against a usual ~250. Against that week's
        // Sunday CTL (the old reference, 28.5 × 7 × 0.8 = 160) it passed as build-up; against its
        // average level (35.7 × 7 × 0.8 = 200) it is the lighter week that resets the count.
        val soccerOnly = ewmaHistory { weeksBack -> if (weeksBack == 3) mapOf(0 to 180.0) else soccerAndRun }
        assertThat(Periodization.downWeekReason(soccerOnly, SuggestFixtures.TODAY_DAY, soccerOnly.last().ctl)).isNull()
        val byDay = soccerOnly.associateBy { it.day }
        val start = SuggestFixtures.TODAY_DAY - 21
        assertThat(Periodization.weekLevel(byDay, start)!!).isWithin(1.0).of(35.7 * 7)
        assertThat(byDay.getValue(start + 6).ctl).isWithin(0.1).of(28.5)
    }

    @Test
    fun bug21_an_accepted_down_week_runs_its_seven_days_then_counts_as_the_lighter_week() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.0, 1.0, 1.0, 1.0, 1.0))
        val today = SuggestFixtures.TODAY_DAY
        fun reason(lastDown: Long?) = Periodization.downWeekReason(history, today, ctl, lastDown)
        assertThat(reason(null)).isEqualTo(DownWeekReason.LONG_BUILD)
        // Still running: regenerating on day 0…6 keeps the down week.
        assertThat(reason(today)).isEqualTo(DownWeekReason.CONTINUED)
        assertThat(reason(today - 6)).isEqualTo(DownWeekReason.CONTINUED)
        // Over: it is the lighter week even though the load did not drop (fixed team training).
        assertThat(reason(today - 7)).isNull()
        assertThat(reason(today - 41)).isNull()
        // Outside the five weeks looked at, the long build is back.
        assertThat(reason(today - 42)).isEqualTo(DownWeekReason.LONG_BUILD)
        // A start in the future (a batch for a later horizon) is not a running down week.
        assertThat(reason(today + 3)).isEqualTo(DownWeekReason.LONG_BUILD)

        val result = Periodization.compute(SuggestFixtures.input(recentLoad = history).copy(lastDownWeekStartDay = today - 2))
        assertThat(result.phase).isEqualTo(TrainingPhase.RECOVERY_WEEK)
        assertThat(result.downWeekReason).isEqualTo(DownWeekReason.CONTINUED)
        assertThat(Periodization.compute(SuggestFixtures.input(recentLoad = history).copy(lastDownWeekStartDay = today - 9)).phase)
            .isEqualTo(TrainingPhase.BASE)
    }

    @Test
    fun bug21_a_big_build_is_not_asked_for_again_after_a_down_week() {
        val history = SuggestFixtures.weeklyHistory(listOf(1.45, 1.4, 1.45, 0.6))
        val today = SuggestFixtures.TODAY_DAY
        assertThat(Periodization.downWeekReason(history, today, ctl, today - 14)).isNull()
        assertThat(Periodization.downWeekReason(history, today, ctl, today - 27)).isNull()
        // The three weeks looked at no longer touch the down week.
        assertThat(Periodization.downWeekReason(history, today, ctl, today - 28)).isEqualTo(DownWeekReason.BIG_BUILD)
    }

    @Test
    fun bug21_reactive_reasons_still_apply_after_a_down_week() {
        val today = SuggestFixtures.TODAY_DAY
        val over = SuggestFixtures.weeklyHistory(listOf(1.55, 0.6))
        assertThat(Periodization.downWeekReason(over, today, ctl, today - 14)).isEqualTo(DownWeekReason.ACUTE_OVERLOAD)
        val three = listOf(RecoveryBand.FATIGUED, RecoveryBand.GOOD, RecoveryBand.STRAINED, null, RecoveryBand.FATIGUED)
        val tired = SuggestFixtures.weeklyHistory(listOf(1.0, 0.6), bandsLastWeek = three)
        assertThat(Periodization.downWeekReason(tired, today, ctl, today - 14)).isEqualTo(DownWeekReason.FATIGUE)
    }

    @Test
    fun bug21_a_normal_week_after_a_break_is_not_an_acute_overload() {
        // Six weeks back to four: normal; then two weeks off; last week normal again.
        val back = ewmaHistory { weeksBack -> if (weeksBack in 2..3) emptyMap() else soccerAndRun }
        val ctlNow = back.last().ctl
        // Against today's CTL (the old reference) 280 AU looked like 1.5 × the usual week …
        assertThat(280.0).isGreaterThan(ctlNow * 7 * Periodization.DOWN_ACUTE_RATIO)
        // … but it is no more than the athlete carried four weeks ago.
        assertThat(Periodization.downWeekReason(back, SuggestFixtures.TODAY_DAY, ctlNow)).isNull()
        // A real spike still is one: a 700 AU week on top of steady training.
        val spike = ewmaHistory { weeksBack -> if (weeksBack == 1) mapOf(0 to 300.0, 2 to 200.0, 5 to 200.0) else soccerAndRun }
        assertThat(Periodization.downWeekReason(spike, SuggestFixtures.TODAY_DAY, spike.last().ctl))
            .isEqualTo(DownWeekReason.ACUTE_OVERLOAD)
    }

    @Test
    fun bug21_the_weekly_target_uses_the_averaged_level_not_the_last_day() {
        // Yesterday was the sixth rest day after the soccer session: the saw-tooth's low point.
        val history = ewmaHistory { weeksBack -> if (weeksBack == 3) mapOf(0 to 180.0) else soccerAndRun }
        val usual = Periodization.usualWeek(history)!!
        assertThat(usual).isWithin(1e-9).of(history.takeLast(7).map { it.ctl }.average() * 7)
        assertThat(history.last().ctl * 7).isLessThan(usual - 40.0)

        val result = Periodization.compute(SuggestFixtures.input(recentLoad = history))
        assertThat(result.phase).isEqualTo(TrainingPhase.BASE)
        // Last week (280 AU) × 1.25 = 350 is above usual × 1.20, so the phase factor decides.
        assertThat(result.weeklyTarget).isWithin(0.01).of(usual * 1.20)
    }

    @Test
    fun bug21_a_regenerated_down_week_keeps_its_first_day() {
        assertThat(Periodization.downWeekStart(emptyList())).isNull()
        assertThat(Periodization.downWeekStart(listOf(100L))).isEqualTo(100L)
        // Accepted on day 100, regenerated on 103 and 105: one down week from day 100.
        assertThat(Periodization.downWeekStart(listOf(105L, 100L, 103L))).isEqualTo(100L)
        // A new down week a week later is a new one.
        assertThat(Periodization.downWeekStart(listOf(107L, 100L))).isEqualTo(107L)
        assertThat(Periodization.downWeekStart(listOf(120L, 100L, 103L))).isEqualTo(120L)
    }

    @Test
    fun last_week_actual_sums_the_seven_days_before_today() {
        val history = SuggestFixtures.loadHistory(dailyTrimp = 30.0)
        assertThat(Periodization.lastWeekActual(history, SuggestFixtures.TODAY_DAY))
            .isWithin(0.01).of(210.0)
    }
}
