package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.PeriodSessionKind
import com.myhealth.domain.model.SessionType
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * 0.10.3: the "During my period, suggest" setting. Today is cycle day 1 of a default 5-day period,
 * so horizon days 0–4 are menstrual and 5–6 are not.
 */
class SuggestionEnginePeriodKindsTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))
    private val cycle: Map<Long, CycleStatus> = SuggestFixtures.cycleStatuses(offset = 0)
    private val periodDays = (0L..4L).map { day(it) }.toSet()

    private fun input(kinds: Set<PeriodSessionKind>, cycleStatuses: Map<Long, CycleStatus> = cycle) =
        SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(30))),
            recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0),
            cycleStatusByDay = cycleStatuses,
        ).copy(periodSessionKinds = kinds)

    @Test
    fun every_session_type_belongs_to_a_kind_except_match_and_rest() {
        SessionType.entries.forEach { type ->
            val kind = PeriodSessionKind.of(type)
            if (type == SessionType.SOCCER_MATCH || type == SessionType.REST) {
                assertThat(kind).isNull()
            } else {
                assertThat(kind).isNotNull()
            }
        }
    }

    @Test
    fun the_fixture_has_menstrual_days_0_to_4_only() {
        assertThat(cycle.filterValues { it.isMenstrual }.keys).isEqualTo(periodDays)
    }

    @Test
    fun all_kinds_on_is_the_old_behaviour_with_the_same_inputs_hash() {
        val default = engine.generate(SuggestFixtures.input(
            goals = listOf(SuggestFixtures.raceGoal(day(30))),
            recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0),
            cycleStatusByDay = cycle,
        ))
        val all = engine.generate(input(PeriodSessionKind.ALL))
        assertThat(all.inputsHash).isEqualTo(default.inputsHash)
        assertThat(all.sessions.map { it.day to it.sessionType })
            .isEqualTo(default.sessions.map { it.day to it.sessionType })
    }

    @Test
    fun only_mobility_leaves_period_days_with_mobility_alone() {
        val result = engine.generate(input(setOf(PeriodSessionKind.MOBILITY)))
        val onPeriod = result.sessions.filter { it.day in periodDays }
        assertThat(onPeriod.map { it.sessionType }.toSet()).containsExactly(SessionType.MOBILITY)
        // The days after the period still get real training.
        assertThat(result.sessions.filter { it.day !in periodDays && it.sessionType != SessionType.MOBILITY })
            .isNotEmpty()
    }

    @Test
    fun recovery_and_mobility_allow_no_strength_or_endurance_on_period_days() {
        val kinds = setOf(PeriodSessionKind.RECOVERY, PeriodSessionKind.MOBILITY)
        val result = engine.generate(input(kinds))
        result.sessions.filter { it.day in periodDays }.forEach {
            assertThat(PeriodSessionKind.of(it.sessionType)).isIn(kinds)
        }
    }

    @Test
    fun no_strength_keeps_endurance_but_drops_strength_on_period_days() {
        val kinds = PeriodSessionKind.ALL - PeriodSessionKind.STRENGTH
        val result = engine.generate(input(kinds))
        val onPeriod = result.sessions.filter { it.day in periodDays }
        assertThat(onPeriod.none { PeriodSessionKind.of(it.sessionType) == PeriodSessionKind.STRENGTH }).isTrue()
        assertThat(onPeriod.any { PeriodSessionKind.of(it.sessionType) == PeriodSessionKind.ENDURANCE }).isTrue()
    }

    @Test
    fun nothing_ticked_leaves_period_days_empty_and_changes_the_hash() {
        val result = engine.generate(input(emptySet()))
        assertThat(result.sessions.filter { it.day in periodDays }).isEmpty()
        assertThat(result.inputsHash).isNotEqualTo(engine.generate(input(PeriodSessionKind.ALL)).inputsHash)
    }

    @Test
    fun without_cycle_data_the_setting_changes_nothing() {
        val off = engine.generate(input(emptySet(), cycleStatuses = emptyMap()))
        val on = engine.generate(input(PeriodSessionKind.ALL, cycleStatuses = emptyMap()))
        assertThat(off.sessions.map { it.day to it.sessionType }).isEqualTo(on.sessions.map { it.day to it.sessionType })
    }
}
