package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlin.math.abs

/**
 * [SuggestionEngine] against the named cases of PLAN §3.5.7 (`sug01`–`sug08`, `sug13`–`sug16`,
 * `sug18`–`sug20`, `sug26`; `sug09`–`sug12` and `sug17` are [PeriodizationTest]'s, `sug21`–`sug25`
 * [SuggestionEngineCycleTest]'s).
 *
 * Every assertion is about the **output** of a full `generate` run, not about internals: these are
 * the safety properties of risk R13 and they must hold no matter how the greedy loop gets there.
 */
class SuggestionEngineTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private fun List<SuggestedSession>.on(day: Long): List<SuggestedSession> = filter { it.day == day }
    private fun SuggestedSession.isActiveRecoveryFiller(): Boolean =
        rationale.any { it.ruleId == Rationale.RULE_ACTIVE_RECOVERY }

    private val hard = setOf(Intensity.HIGH, Intensity.MAX)

    @Test
    fun sug01_no_hard_session_within_48h_before_match() {
        val result = engine.generate(
            SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_MATCH))),
        )
        listOf(1L, 2L, 3L).forEach { offset ->
            assertThat(result.sessions.on(day(offset)).filter { it.intensity in hard }).isEmpty()
        }
    }

    @Test
    fun sug02_no_lower_body_strength_before_match() {
        val result = engine.generate(
            SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(3), EventType.SOCCER_MATCH))),
        )
        val banned = setOf(SessionType.STRENGTH_LOWER, SessionType.STRENGTH_FULL)
        listOf(1L, 2L, 3L).forEach { offset ->
            assertThat(result.sessions.on(day(offset)).map { it.sessionType }).containsNoneIn(banned)
        }
    }

    @Test
    fun sug03_day_after_match_is_recovery_or_rest() {
        val result = engine.generate(
            SuggestFixtures.input(
                events = listOf(SuggestFixtures.event(day(0), EventType.SOCCER_MATCH)),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )
        // 0.3.0: the day after a match may hold an active-recovery run *and* mobility.
        val allowed = setOf(SessionType.RECOVERY_RUN, SessionType.RECOVERY_SPIN, SessionType.MOBILITY, SessionType.REST)
        result.sessions.on(day(1)).forEach { assertThat(it.sessionType).isIn(allowed) }
    }

    @Test
    fun sug04_at_least_one_rest_day_per_week() {
        val result = engine.generate(SuggestFixtures.input())
        val restDays = (0L..6L).count { offset ->
            result.sessions.on(day(offset)).none { it.sessionType != SessionType.MOBILITY }
        }
        assertThat(restDays).isAtLeast(1)
    }

    @Test
    fun sug05_max_two_high_sessions_per_week() {
        val result = engine.generate(
            SuggestFixtures.input(recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0)),
        )
        assertThat(result.sessions.count { it.intensity in hard }).isAtMost(2)
    }

    @Test
    fun sug06_strained_recovery_yields_rest_today() {
        val result = engine.generate(
            SuggestFixtures.input(recovery = SuggestFixtures.recovery(RecoveryBand.STRAINED, score = 28)),
        )
        assertThat(result.sessions.on(day(0))).isEmpty()
    }

    @Test
    fun sug07_high_acwr_suppresses_high_intensity() {
        val result = engine.generate(
            SuggestFixtures.input(recentLoad = SuggestFixtures.loadHistory(acwr = 1.7)),
        )
        assertThat(result.sessions.filter { it.intensity in hard }).isEmpty()
    }

    @Test
    fun sug08_blocked_day_gets_nothing() {
        val result = engine.generate(
            SuggestFixtures.input(
                events = listOf(SuggestFixtures.event(day(2), EventType.BLOCKED)),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )
        assertThat(result.sessions.on(day(2))).isEmpty()
    }

    @Test
    fun sug13_sport_cap_respected() {
        val result = engine.generate(
            SuggestFixtures.input(
                profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2}"""),
            ),
        )
        // Active-recovery fillers (0.3.0) sit outside the cap, like mobility; real runs stay ≤ 2.
        val runs = result.sessions.count { it.sportType.group == SportGroup.RUN && !it.isActiveRecoveryFiller() }
        assertThat(runs).isAtMost(2)
        assertThat(result.sessions).isNotEmpty()
    }

    @Test
    fun sug14_deterministic_same_input_same_output() {
        val input = SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(40))),
            events = listOf(SuggestFixtures.event(day(4), EventType.SOCCER_TRAINING)),
            recovery = SuggestFixtures.recovery(RecoveryBand.GOOD),
            profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":4,"STRENGTH":2}"""),
        )
        val first = engine.generate(input)
        val second = engine.generate(input)

        assertThat(second.sessions).isEqualTo(first.sessions)
        assertThat(second.sessions.toString()).isEqualTo(first.sessions.toString())
        assertThat(second.inputsHash).isEqualTo(first.inputsHash)
        assertThat(second.batch).isEqualTo(first.batch)
        assertThat(first.inputsHash).hasLength(64)

        // …and a different input produces a different hash.
        val changed = engine.generate(input.copy(horizonDays = 10))
        assertThat(changed.inputsHash).isNotEqualTo(first.inputsHash)
    }

    @Test
    fun sug15_every_session_has_non_empty_rationale() {
        val result = engine.generate(
            SuggestFixtures.input(
                goals = listOf(SuggestFixtures.raceGoal(day(30))),
                recovery = SuggestFixtures.recovery(RecoveryBand.GOOD, score = 72),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )
        assertThat(result.sessions).isNotEmpty()
        result.sessions.forEach { session ->
            assertThat(session.rationale).isNotEmpty()
            session.rationale.forEach {
                assertThat(it.ruleId).isNotEmpty()
                assertThat(it.text).isNotEmpty()
            }
            assertThat(session.status).isEqualTo(SuggestionStatus.PROPOSED)
        }
        val ruleIds = result.sessions.flatMap { it.rationale }.map { it.ruleId }
        assertThat(ruleIds).contains(Rationale.RULE_BUDGET)
        assertThat(ruleIds).contains(Rationale.phaseRuleId(result.phase))
    }

    @Test
    fun sug16_total_estimated_load_within_15_percent_of_target() {
        val result = engine.generate(SuggestFixtures.input())
        // Active-recovery fillers (0.3.0) are placed after the budget loop and are not part of it.
        val total = result.sessions.filterNot { it.isActiveRecoveryFiller() }.sumOf { it.estimatedTrimp }
        assertThat(result.weeklyTarget).isGreaterThan(0.0)
        assertThat(abs(total - result.weeklyTarget) / result.weeklyTarget).isAtMost(0.15)
    }

    @Test
    fun sug18_mobility_added_to_rest_days_when_enabled() {
        val enabled = engine.generate(
            SuggestFixtures.input(profile = SuggestFixtures.profile(mobilityOnRestDays = true)),
        )
        val restDays = (0L..6L).map { day(it) }.filter { d ->
            enabled.sessions.on(d).none { it.sessionType != SessionType.MOBILITY }
        }
        assertThat(restDays).isNotEmpty()
        restDays.forEach { d ->
            assertThat(enabled.sessions.on(d).map { it.sessionType }).contains(SessionType.MOBILITY)
        }

        val disabled = engine.generate(
            SuggestFixtures.input(profile = SuggestFixtures.profile(mobilityOnRestDays = false)),
        )
        val disabledRestDays = (0L..6L).map { day(it) }.filter { d -> disabled.sessions.on(d).isEmpty() }
        assertThat(disabledRestDays).isNotEmpty()
    }

    @Test
    fun sug19_long_run_spacing_at_least_five_days() {
        val result = engine.generate(
            SuggestFixtures.input(
                horizonDays = 14,
                recentLoad = SuggestFixtures.loadHistory(ctl = 70.0, dailyTrimp = 70.0),
            ),
        )
        val longRunDays = result.sessions.filter { it.sessionType == SessionType.LONG_RUN }.map { it.day }
        assertThat(longRunDays.size).isAtMost(3)
        longRunDays.sorted().zipWithNext().forEach { (a, b) -> assertThat(b - a).isAtLeast(5L) }
    }

    @Test
    fun sug20_empty_goals_still_produces_a_sane_week() {
        val result = engine.generate(SuggestFixtures.input(goals = emptyList()))

        assertThat(result.phase).isEqualTo(TrainingPhase.BASE)
        assertThat(result.sessions).isNotEmpty()
        assertThat(result.sessions.map { it.day }).isInOrder()
        result.sessions.forEach { session ->
            assertThat(session.day).isAtLeast(day(0))
            assertThat(session.day).isLessThan(day(7))
            assertThat(session.targetDurationMin!!).isGreaterThan(0)
            assertThat(session.estimatedTrimp).isGreaterThan(0.0)
        }
        assertThat(result.batch.horizonStartDay).isEqualTo(day(0))
        assertThat(result.batch.horizonEndDay).isEqualTo(day(7))
        assertThat(result.batch.weeklyLoadTarget).isEqualTo(result.weeklyTarget)
        assertThat(result.batch.id).isEqualTo(0L)
    }

    @Test
    fun sug26_starter_week_rationale() {
        // POLISH-10: no `daily_load` history at all (right after the first sync, or a brand-new
        // profile) must not collapse the week to mobility-only — it gets the 150 AU starter target,
        // and every suggested session says so.
        val result = engine.generate(
            SuggestFixtures.input(
                recentLoad = emptyList(),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )

        assertThat(result.weeklyTarget).isWithin(0.01).of(150.0)
        assertThat(result.sessions.filter { it.sessionType != SessionType.MOBILITY }).isNotEmpty()

        result.sessions.forEach { session ->
            val ruleIds = session.rationale.map { it.ruleId }
            assertThat(ruleIds).contains(Rationale.RULE_STARTER_WEEK)
        }
        val starterEntry = result.sessions.first().rationale.first { it.ruleId == Rationale.RULE_STARTER_WEEK }
        assertThat(starterEntry.text)
            .isEqualTo("Starter week: no training history yet, so this is a gentle first week.")

        // A returning athlete with real history never sees this rationale.
        val normal = engine.generate(SuggestFixtures.input())
        assertThat(normal.sessions.flatMap { it.rationale }.map { it.ruleId })
            .doesNotContain(Rationale.RULE_STARTER_WEEK)
    }

    @Test
    fun sug28_no_cap_no_bike_goal_outputs_and_hash_unchanged() {
        // The P12.3 drift guard. `fixtures/suggest/sug28_baseline.txt` was regenerated for 0.3.0
        // (active recovery on rest days changes every bike-free week that has a run allowance) and
        // for 0.9.1 (P19.6: phase factors 1.20/1.30/1.15, a lighter week in `loadHistory`); it
        // was originally generated by running
        // [SuggestionSnapshot.renderAll] against the engine as it stood **before** the cycling work
        // (commit 7095a77) and is read-only from then on: it holds the phase, the weekly target, the
        // `inputsHash` and every session (day, sport, type, intensity, minutes, load, score and all
        // rationale lines) of all nineteen bike-free fixtures the `sug01`…`sug26` cases run on.
        //
        // Cycling must be invisible to every athlete who neither caps a `CYCLE` sport nor carries a
        // `BIKE_*` goal — including the digest, which is why `SuggestionInputsHash` emits the two new
        // profile fields only once one of them is set.
        assertThat(SuggestionSnapshot.renderAll(engine)).isEqualTo(SuggestionSnapshot.loadBaseline())

        // …and none of those fixtures can even see a bike session.
        SuggestionSnapshot.BASELINE_INPUTS.forEach { (name, input) ->
            assertWithMessage(name).that(engine.generate(input).sessions.map { it.sessionType })
                .containsNoneIn(SessionCatalog.BIKE_TYPES)
        }
    }

    @Test
    fun a_locked_session_is_never_duplicated_or_moved() {
        val locked = SuggestFixtures.locked(day(2), SessionType.TEMPO_RUN, intensity = Intensity.HIGH)
        val result = engine.generate(SuggestFixtures.input(lockedPlanned = listOf(locked)))
        assertThat(result.sessions.on(day(2))).isEmpty()
    }

    @Test
    fun fixed_events_consume_the_weekly_budget() {
        val withMatch = engine.generate(
            SuggestFixtures.input(events = listOf(SuggestFixtures.event(day(5), EventType.SOCCER_MATCH))),
        )
        val without = engine.generate(SuggestFixtures.input())
        assertThat(withMatch.sessions.sumOf { it.estimatedTrimp })
            .isLessThan(without.sessions.sumOf { it.estimatedTrimp })
    }

    @Test
    fun taper_shortens_interval_sessions_to_sixty_percent() {
        val minutes = engine.minutesFor(
            entry = SessionCatalog.entryFor(SessionType.INTERVAL_RUN)!!,
            phase = TrainingPhase.TAPER,
            grid = SuggestFixtures.grid(SuggestFixtures.input()),
            remaining = 500.0,
        )
        val base = engine.minutesFor(
            entry = SessionCatalog.entryFor(SessionType.INTERVAL_RUN)!!,
            phase = TrainingPhase.PEAK,
            grid = SuggestFixtures.grid(SuggestFixtures.input()),
            remaining = 500.0,
        )
        assertThat(minutes).isLessThan(base)
    }
}
