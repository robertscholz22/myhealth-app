package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.testutil.Fixtures
import org.junit.Test
import kotlinx.datetime.LocalDate

/**
 * [SuggestionEngine] against the cycling rules of PLAN §3.5.8 (`sug27`, `sug29`–`sug33`; `sug28`,
 * the drift guard for everybody who does *not* ride, lives in [SuggestionEngineTest]).
 *
 * Split out of [SuggestionEngineTest] for rule R10, the way [SuggestionEngineCycleTest] is.
 *
 * The seasonal cases run on **Monday 7 December 2026** — the fixture "today" of 14 September is
 * outside the November–March window on purpose, so the two halves of the indoor rule are testable
 * without touching the other twenty-six cases.
 */
class SuggestionEngineBikeTest {

    private val engine = SuggestionEngine(Fixtures.fixedClock("2026-09-14T06:00:00Z"))

    private val hard = setOf(Intensity.HIGH, Intensity.MAX)

    private val busyHistory = SuggestFixtures.loadHistory(ctl = 80.0, dailyTrimp = 80.0)

    /** Real rides only — the 0.3.0 active-recovery spin is a rest-day filler, not a planned ride. */
    private fun List<SuggestedSession>.rides(): List<SuggestedSession> =
        filter { it.sportType.group == SportGroup.CYCLE && it.rationale.none { r -> r.ruleId == Rationale.RULE_ACTIVE_RECOVERY } }

    private fun List<SuggestedSession>.ruleIds(): List<String> = flatMap { it.rationale }.map { it.ruleId }

    /** A rider's week: a `CYCLE` cap of 3, an FTP goal, and enough history to train off. */
    private fun bikeInput(
        goals: List<com.myhealth.domain.model.Goal> = listOf(SuggestFixtures.bikeGoal()),
        cycleCap: Int = 3,
        trainer: Boolean = false,
        lockedPlanned: List<com.myhealth.domain.model.PlannedSession> = emptyList(),
        recovery: com.myhealth.domain.model.RecoveryState? = null,
    ) = SuggestFixtures.input(
        goals = goals,
        lockedPlanned = lockedPlanned,
        recentLoad = busyHistory,
        recovery = recovery,
        profile = SuggestFixtures.profile(
            preferredSportsJson = SuggestFixtures.bikeSportsJson(cycleCap = cycleCap),
            indoorTrainerAvailable = trainer,
        ),
    )

    /** The same week, in December: `loadHistory` has to move with "today". */
    private fun decemberInput(trainer: Boolean) = SuggestFixtures.input(
        goals = listOf(SuggestFixtures.bikeGoal()),
        recentLoad = SuggestFixtures.loadHistory(todayDay = DECEMBER_DAY, ctl = 80.0, dailyTrimp = 80.0),
        profile = SuggestFixtures.profile(
            preferredSportsJson = SuggestFixtures.bikeSportsJson(),
            indoorTrainerAvailable = trainer,
        ),
    ).copy(today = DECEMBER)

    @Test
    fun sug27_bike_goal_and_cycle_cap_yields_a_ride() {
        val result = engine.generate(bikeInput())

        assertThat(result.sessions.rides()).isNotEmpty()
        result.sessions.rides().forEach {
            assertThat(it.sessionType).isIn(SessionCatalog.BIKE_TYPES)
            // September is not the indoor season, and there is no trainer anyway.
            assertThat(it.sportType).isEqualTo(SportType.CYCLING)
            assertThat(it.rationale.map { line -> line.ruleId }).contains(Rationale.RULE_BIKE_FTP_GOAL)
        }
        // The gate really is the gate: the same week without the cap and without the goal has none.
        val without = engine.generate(
            SuggestFixtures.input(recentLoad = busyHistory),
        )
        assertThat(without.sessions.map { it.sessionType }).containsNoneIn(SessionCatalog.BIKE_TYPES)

        // Either half of the gate is enough on its own: the goal alone opens the catalog for a
        // profile that never recorded a cycling preference…
        val goalOnly = engine.generate(
            SuggestFixtures.input(
                goals = listOf(SuggestFixtures.bikeGoal()),
                recentLoad = busyHistory,
                profile = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2,"STRENGTH":2}"""),
            ),
        )
        assertThat(goalOnly.sessions.rides()).isNotEmpty()
        // …and the cap alone does it without any goal at all.
        val capOnly = engine.generate(bikeInput(goals = emptyList()))
        assertThat(capOnly.sessions.rides()).isNotEmpty()
        assertThat(capOnly.sessions.ruleIds()).doesNotContain(Rationale.RULE_BIKE_FTP_GOAL)

        // An explicit cap of 0 still wins: C10 is a hard constraint, so a bike goal opens the
        // catalog but "no rides this week" is honoured (the settings field is how you change it).
        val cappedOut = engine.generate(bikeInput(cycleCap = 0))
        assertThat(cappedOut.sessions.rides()).isEmpty()
    }

    @Test
    fun sug29_indoor_season_with_trainer_rides_are_indoor() {
        val result = engine.generate(decemberInput(trainer = true))

        assertThat(result.sessions.rides()).isNotEmpty()
        result.sessions.rides().forEach {
            assertThat(it.sportType).isEqualTo(SportType.CYCLING_INDOOR)
            assertThat(it.rationale.map { line -> line.ruleId })
                .contains(Rationale.RULE_BIKE_INDOOR_SEASON)
        }
        // `TRAINER_SESSION` needs the equipment, and now it has it.
        assertThat(SessionCatalog.suggestableFor(bikeEnabled = true).map { it.sessionType })
            .contains(SessionType.TRAINER_SESSION)
    }

    @Test
    fun sug30_indoor_season_without_trainer_rides_stay_outdoor() {
        val result = engine.generate(decemberInput(trainer = false))

        assertThat(result.sessions.rides()).isNotEmpty()
        result.sessions.rides().forEach {
            // `RECOVERY_SPIN` is an indoor row by definition; every *outdoor* row stays outdoors.
            if (it.sessionType != SessionType.RECOVERY_SPIN) {
                assertThat(it.sportType).isEqualTo(SportType.CYCLING)
            }
            assertThat(it.sessionType).isNotEqualTo(SessionType.TRAINER_SESSION)
        }
        assertThat(result.sessions.ruleIds()).doesNotContain(Rationale.RULE_BIKE_INDOOR_SEASON)
    }

    @Test
    fun sug31_bike_event_in_30_days_is_peak_with_bike_intervals_preferred() {
        val event = SuggestFixtures.bikeGoal(
            type = GoalType.BIKE_EVENT,
            targetValue = null,
            targetDay = day(30),
            targetDistanceMeters = 40_000.0,
        )
        // A rested athlete: `recoveryFit` is what decides between the two PEAK rows, and a FRESH
        // band is the case where the hard one is the right call (0.8 vs 0.73).
        val result = engine.generate(
            bikeInput(goals = listOf(event), recovery = SuggestFixtures.recovery(RecoveryBand.FRESH)),
        )

        // A cycling event periodizes exactly like a race…
        assertThat(result.phase).isEqualTo(TrainingPhase.PEAK)
        // …off the second phase table, whose PEAK row is bike intervals + endurance rides.
        assertThat(Periodization.bikePreferredTypes(TrainingPhase.PEAK))
            .containsExactly(SessionType.BIKE_INTERVALS, SessionType.ENDURANCE_RIDE)
        assertThat(result.sessions.map { it.sessionType }).contains(SessionType.BIKE_INTERVALS)
        assertThat(result.sessions.rides().flatMap { it.rationale }.map { it.ruleId })
            .contains(Rationale.RULE_BIKE_EVENT_PREP)
    }

    @Test
    fun sug32_hash_changes_when_trainer_flag_flips() {
        val off = bikeInput(trainer = false)
        val on = bikeInput(trainer = true)

        assertThat(engine.generate(on).inputsHash).isNotEqualTo(engine.generate(off).inputsHash)
        // The same flag twice is the same hash (determinism, §3.5.6 step 9)…
        assertThat(engine.generate(on).inputsHash).isEqualTo(engine.generate(on).inputsHash)
        // …and the FTP override is hashed too.
        val ftp = off.copy(profile = off.profile.copy(ftpWattsManual = 280))
        assertThat(engine.generate(ftp).inputsHash).isNotEqualTo(engine.generate(off).inputsHash)
        // A profile with neither field set serializes exactly as it did before P12 (see `sug28`).
        assertThat(SuggestionInputsHash.canonical(off)).doesNotContain("bike=")
        assertThat(SuggestionInputsHash.canonical(on)).contains("bike=|true")
    }

    @Test
    fun sug33_bike_intervals_count_toward_two_hard_per_week() {
        // Two hard sessions are already on the calendar, one of them a bike interval session:
        // C5's allowance is spent and the engine may not add a third.
        val locked = listOf(
            SuggestFixtures.locked(day(0), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            SuggestFixtures.locked(
                day = day(3),
                sessionType = SessionType.BIKE_INTERVALS,
                sportType = SportType.CYCLING,
                intensity = Intensity.HIGH,
                minutes = 60,
                estimatedTrimp = 144.0,
            ),
        )
        val result = engine.generate(bikeInput(lockedPlanned = locked))
        assertThat(result.sessions.filter { it.intensity in hard }).isEmpty()

        // With only the bike session locked there is exactly one slot left, never two.
        val oneLocked = engine.generate(bikeInput(lockedPlanned = listOf(locked[1])))
        assertThat(oneLocked.sessions.count { it.intensity in hard }).isAtMost(1)
    }

    private companion object {
        val DECEMBER: LocalDate = LocalDate(2026, 12, 7)
        val DECEMBER_DAY: Long = DECEMBER.toEpochDays()
    }
}
