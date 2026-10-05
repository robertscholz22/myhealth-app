package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.domain.model.WorkoutTargetKind
import com.myhealth.testutil.Fixtures
import org.junit.Test

/**
 * The suggester's half of PLAN §3.11 (`sug34`, `sug36`): a placed interval session carries the
 * structure and the pace, and an athlete with neither a VDOT nor an FTP still gets a session — a
 * zone-only one.
 *
 * `sug35` ("the structure survives accept") is a repository behaviour and lives in
 * `RoomSuggestionRepositoryTest.sug35_structure_survives_accept`, where the existing P6.5 fakes
 * already provide a `suggested_session` → `planned_session` round trip.
 */
class SuggestionEngineIntervalTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    /** The §3.5.7 fixture that reliably places interval work: a 5 k race 30 days out, busy legs. */
    private fun input(vdot: Double? = null, ftpWatts: Int? = null) = SuggestFixtures.input(
        goals = listOf(SuggestFixtures.raceGoal(day(30))),
        recentLoad = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0),
    ).copy(vdot = vdot, ftpWatts = ftpWatts)

    private fun intervalRunOf(sessions: List<SuggestedSession>): SuggestedSession =
        sessions.first { it.sessionType == SessionType.INTERVAL_RUN }

    @Test
    fun sug34_interval_run_carries_a_structure_and_a_pace() {
        val result = engine.generate(input(vdot = 50.0))

        val session = intervalRunOf(result.sessions)
        assertThat(session.structureJson).isNotNull()
        assertThat(session.targetPaceSecPerKm).isNotNull()

        val structure = WorkoutStructureCodec.decode(session.structureJson)!!
        // A 5 k goal in the peak phase is prescribed as 400s at repetition pace (§3.11 rule 3).
        assertThat(structure.templateId).isEqualTo("RUN_400_R")
        assertThat(IntervalBuilder.summary(structure)).isEqualTo("8 × 400 m @ 3:39")
        // Z5's Daniels anchor is the interval pace: 234 s/km at VDOT 50 (§3.10.2).
        assertThat(session.targetPaceSecPerKm).isEqualTo(234)
        assertThat(session.rationale.map { it.ruleId })
            .containsAtLeast(Rationale.RULE_INTERVAL_STRUCTURE, Rationale.RULE_PACE_TARGET)

        // Rule 1: nothing else gained a structure.
        result.sessions
            .filterNot { it.sessionType in IntervalBuilder.STRUCTURED_TYPES }
            .forEach { assertThat(it.structureJson).isNull() }
    }

    @Test
    fun sug36_no_vdot_no_ftp_output_is_zone_only() {
        val result = engine.generate(input())

        val session = intervalRunOf(result.sessions)
        assertThat(session.targetPaceSecPerKm).isNull()
        val structure = WorkoutStructureCodec.decode(session.structureJson)!!
        val steps = structure.steps + structure.steps.flatMap { it.children }
        steps.forEach { step ->
            assertThat(step.target).isNotEqualTo(WorkoutTargetKind.PACE)
            assertThat(step.target).isNotEqualTo(WorkoutTargetKind.POWER)
            assertThat(step.paceLowSecPerKm).isNull()
            assertThat(step.paceHighSecPerKm).isNull()
            assertThat(step.powerLowW).isNull()
            assertThat(step.powerHighW).isNull()
        }
        // …and with nothing to name, the three §3.11 rationale lines stay out of the card.
        result.sessions.forEach { suggested ->
            assertThat(suggested.rationale.map { it.ruleId }).containsNoneOf(
                Rationale.RULE_INTERVAL_STRUCTURE,
                Rationale.RULE_INTERVAL_SHORTENED_TAPER,
                Rationale.RULE_PACE_TARGET,
            )
        }
    }
}
