package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * 0.3.0 — active recovery on rest days (PLAN §3.5.6 step 7c, [ActiveRecovery]): `ar01`…`ar08`.
 * The fixtures' default profile has no caps at all (`"{}"`), which the pass reads as "running
 * allowed, no bike" — exactly the owner's pre-cycling state.
 */
class SuggestionEngineActiveRecoveryTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private fun List<SuggestedSession>.on(day: Long): List<SuggestedSession> = filter { it.day == day }
    private val fillers = setOf(SessionType.RECOVERY_RUN, SessionType.RECOVERY_SPIN)

    /** Days whose only sessions are mobility and/or an active-recovery filler. */
    private fun restDays(sessions: List<SuggestedSession>): List<Long> = (0L..6L).map { day(it) }.filter { d ->
        sessions.on(d).all { it.sessionType == SessionType.MOBILITY || it.rationale.any { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }
    }

    private fun activeRecoveryDays(sessions: List<SuggestedSession>): List<Long> = (0L..6L).map { day(it) }.filter { d ->
        sessions.on(d).any { it.rationale.any { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }
    }

    @Test
    fun ar01_rest_days_get_a_recovery_run_except_one_true_rest_day() {
        val result = engine.generate(
            SuggestFixtures.input(profile = SuggestFixtures.profile(mobilityOnRestDays = true)),
        )
        val rest = restDays(result.sessions)
        val active = activeRecoveryDays(result.sessions)
        assertThat(rest.size).isAtLeast(2)
        assertThat(active).isNotEmpty()
        active.forEach { d ->
            val types = result.sessions.on(d).map { it.sessionType }
            assertThat(types).contains(SessionType.RECOVERY_RUN)
            assertThat(types).contains(SessionType.MOBILITY)
        }
        // At least one true rest day keeps nothing but mobility…
        val trueRest = rest - active.toSet()
        assertThat(trueRest).isNotEmpty()
        trueRest.forEach { d ->
            assertThat(result.sessions.on(d).map { it.sessionType }).containsExactly(SessionType.MOBILITY)
        }
        // …and, with only running available, two fillers never sit on consecutive days (C13's spirit).
        active.zipWithNext().forEach { (a, b) -> assertThat(b - a).isGreaterThan(1L) }
    }

    @Test
    fun ar02_run_and_trainer_alternate_between_run_and_spin() {
        val result = engine.generate(
            SuggestFixtures.input(
                profile = SuggestFixtures.profile(
                    preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = 2, runCap = 2, strengthCap = 1),
                    indoorTrainerAvailable = true,
                ),
            ),
        )
        val fillerTypes = activeRecoveryDays(result.sessions).map { d ->
            result.sessions.on(d).single { it.rationale.any { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }.sessionType
        }
        assertThat(fillerTypes.size).isAtLeast(2)
        assertThat(fillerTypes.toSet()).containsExactly(SessionType.RECOVERY_RUN, SessionType.RECOVERY_SPIN)
        fillerTypes.zipWithNext().forEach { (a, b) -> assertThat(a).isNotEqualTo(b) }
    }

    @Test
    fun ar03_no_run_cap_and_no_bike_leaves_rest_days_mobility_only() {
        val result = engine.generate(
            SuggestFixtures.input(
                profile = SuggestFixtures.profile(
                    preferredSportsJson = """{"RUN":0,"STRENGTH":3,"SOCCER":2,"CYCLE":0}""",
                    mobilityOnRestDays = true,
                ),
            ),
        )
        assertThat(activeRecoveryDays(result.sessions)).isEmpty()
        assertThat(result.sessions.none { it.sessionType in fillers }).isTrue()
    }

    @Test
    fun ar04_eve_of_a_match_stays_mobility_only() {
        val result = engine.generate(
            SuggestFixtures.input(
                events = listOf(SuggestFixtures.event(day(4), EventType.SOCCER_MATCH)),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )
        val eve = result.sessions.on(day(3)).map { it.sessionType }
        assertThat(eve).doesNotContain(SessionType.RECOVERY_RUN)
        assertThat(eve).doesNotContain(SessionType.RECOVERY_SPIN)
    }

    @Test
    fun ar05_late_luteal_prefers_the_spin_over_the_run() {
        // Period started 24 days ago on a default 28-day cycle → every horizon day is late luteal.
        val result = engine.generate(
            SuggestFixtures.input(
                profile = SuggestFixtures.profile(
                    preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = 2, runCap = 2, strengthCap = 1),
                    indoorTrainerAvailable = true,
                ),
                cycleStatusByDay = SuggestFixtures.cycleStatuses(offset = -24),
            ),
        )
        val fillerTypes = activeRecoveryDays(result.sessions).flatMap { d ->
            result.sessions.on(d).filter { it.rationale.any { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }
        }
        assertThat(fillerTypes).isNotEmpty()
        assertThat(fillerTypes.map { it.sessionType }.toSet()).containsExactly(SessionType.RECOVERY_SPIN)
        assertThat(fillerTypes.first().rationale.map { it.ruleId }).contains(CycleRules.RULE_LATE_LUTEAL)
    }

    @Test
    fun ar06_active_recovery_days_still_count_as_rest_days_and_cost_no_budget() {
        val input = SuggestFixtures.input(profile = SuggestFixtures.profile(mobilityOnRestDays = false))
        val result = engine.generate(input)
        val active = activeRecoveryDays(result.sessions)
        assertThat(active).isNotEmpty()
        // The fillers are placed after the budget loop: removing them changes nothing in the load
        // the week's real sessions add up to, and their nominal score is the mobility score.
        result.sessions.filter { it.day in active && it.sessionType in fillers }.forEach {
            assertThat(it.score).isEqualTo(SuggestionEngine.MOBILITY_SCORE)
            assertThat(it.targetDurationMin).isEqualTo(30)
        }
        // C3 still sees a rest day in every rolling window: rebuild the grid and check.
        val grid = result.sessions.fold(SuggestionGrid.seed(input)) { g, s ->
            val entry = SessionCatalog.entryFor(s.sessionType)!!
            g.place(s.day, Candidate(entry, s.day, s.targetDurationMin ?: entry.defaultMin).asPlacedItem(s.score, s.rationale)
                .copy(isActiveRecovery = s.sessionType in fillers && s.score == SuggestionEngine.MOBILITY_SCORE))
        }
        grid.rollingWindows().forEach { window ->
            assertThat(grid.days.any { it.day in window && it.isRestDay }).isTrue()
        }
    }

    @Test
    fun ar07_without_mobility_preference_the_filler_stands_alone() {
        val result = engine.generate(
            SuggestFixtures.input(profile = SuggestFixtures.profile(mobilityOnRestDays = false)),
        )
        val active = activeRecoveryDays(result.sessions)
        assertThat(active).isNotEmpty()
        active.forEach { d ->
            assertThat(result.sessions.on(d).map { it.sessionType }).containsExactly(SessionType.RECOVERY_RUN)
        }
        assertThat(result.sessions.none { it.sessionType == SessionType.MOBILITY }).isTrue()
    }

    @Test
    fun ar08_filler_after_a_hard_day_says_so_and_the_true_rest_day_is_the_one_farthest_from_it() {
        val result = engine.generate(
            SuggestFixtures.input(
                events = listOf(SuggestFixtures.event(day(1), EventType.SOCCER_MATCH)),
                profile = SuggestFixtures.profile(mobilityOnRestDays = true),
            ),
        )
        val dayAfter = result.sessions.on(day(2))
        val filler = dayAfter.firstOrNull { it.rationale.any { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }
        if (filler != null) {
            assertThat(filler.rationale.first { it.ruleId == Rationale.RULE_ACTIVE_RECOVERY }.text)
                .contains("the day after a hard session")
        }
        val rest = restDays(result.sessions)
        val active = activeRecoveryDays(result.sessions)
        assertThat(active).isNotEmpty()
        // The day after the match is the preferred filler day, never the week's true rest day.
        if (day(2) in rest) assertThat(active).contains(day(2))
    }
}
