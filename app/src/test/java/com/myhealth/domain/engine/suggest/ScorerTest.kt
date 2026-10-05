package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.candidate
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.TrainingPhase
import org.junit.Test

/** The five scoring terms, the recovery matrix and the phase-preference table of PLAN §3.5.5. */
class ScorerTest {

    private val emptyGrid = SuggestFixtures.grid(SuggestFixtures.input())

    private fun ctx(
        phase: TrainingPhase = TrainingPhase.BUILD,
        primary: SportGroup? = SportGroup.RUN,
        secondary: Set<SportGroup> = emptySet(),
        remaining: Double = 100.0,
        band: RecoveryBand? = null,
        caps: Map<SportGroup, Int> = emptyMap(),
    ) = ScoringContext(
        phase = phase,
        preferredTypes = Scorer.preferredTypes(phase),
        primaryGoalGroup = primary,
        secondaryGoalGroups = secondary,
        remainingBudget = remaining,
        recoveryBand = band,
        weeklyCaps = caps,
    )

    @Test
    fun recovery_matrix_matches_the_plan_table() {
        val expected = mapOf(
            RecoveryBand.FRESH to listOf(0.4, 0.7, 0.9, 1.0, 1.0),
            RecoveryBand.GOOD to listOf(0.5, 0.8, 1.0, 0.9, 0.8),
            RecoveryBand.MODERATE to listOf(0.7, 1.0, 0.8, 0.5, 0.3),
            RecoveryBand.FATIGUED to listOf(1.0, 0.8, 0.3, 0.0, 0.0),
            RecoveryBand.STRAINED to listOf(1.0, 0.3, 0.0, 0.0, 0.0),
        )
        expected.forEach { (band, row) ->
            Intensity.entries.forEachIndexed { index, intensity ->
                assertThat(Scorer.recoveryFit(band, intensity)).isWithin(1e-9).of(row[index])
            }
        }
        // The "unknown band" row of the plan.
        assertThat(Intensity.entries.map { Scorer.recoveryFit(null, it) })
            .isEqualTo(listOf(0.6, 0.9, 0.9, 0.7, 0.5))
    }

    @Test
    fun goal_fit_rewards_the_primary_sport_and_the_phase_preference() {
        val build = ctx(phase = TrainingPhase.BUILD, primary = SportGroup.RUN, secondary = setOf(SportGroup.SOCCER))

        // TEMPO_RUN is a BUILD-preferred run: the full 1.0.
        assertThat(Scorer.goalFit(candidate(SessionType.TEMPO_RUN, day(1)), build)).isWithin(1e-9).of(1.0)
        // A run that is not preferred in this phase: 0.6.
        assertThat(Scorer.goalFit(candidate(SessionType.RECOVERY_RUN, day(1)), build)).isWithin(1e-9).of(0.6)
        // A secondary goal's sport: 0.3.
        assertThat(Scorer.goalFit(candidate(SessionType.SOCCER_TRAINING, day(1)), build)).isWithin(1e-9).of(0.3)
        // Anything else: 0.1.
        assertThat(Scorer.goalFit(candidate(SessionType.CROSS_TRAINING, day(1)), build)).isWithin(1e-9).of(0.1)

        // With no sport-specific goal the phase preference is all that is left (documented choice).
        val sportless = ctx(primary = null)
        assertThat(Scorer.goalFit(candidate(SessionType.TEMPO_RUN, day(1)), sportless)).isWithin(1e-9).of(1.0)
        assertThat(Scorer.goalFit(candidate(SessionType.CROSS_TRAINING, day(1)), sportless)).isWithin(1e-9).of(0.6)
    }

    @Test
    fun load_fit_peaks_when_the_session_fills_the_budget_and_is_zero_past_the_overshoot() {
        assertThat(Scorer.loadFit(estTrimp = 100.0, remainingBudget = 100.0)).isWithin(1e-9).of(1.0)
        assertThat(Scorer.loadFit(estTrimp = 50.0, remainingBudget = 100.0)).isWithin(1e-9).of(0.5)
        assertThat(Scorer.loadFit(estTrimp = 140.0, remainingBudget = 100.0)).isWithin(1e-9).of(0.6)
        // 1.4 x remaining is the cliff of §3.5.5.
        assertThat(Scorer.loadFit(estTrimp = 141.0, remainingBudget = 100.0)).isWithin(1e-9).of(0.0)
        // A spent budget cannot be divided by zero.
        assertThat(Scorer.loadFit(estTrimp = 10.0, remainingBudget = 0.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun spacing_fit_falls_linearly_to_zero_at_half_the_ideal_gap() {
        val locked = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(0), SessionType.TEMPO_RUN, intensity = Intensity.HIGH),
            ),
        )
        val grid = SuggestFixtures.grid(locked)

        // Ideal for HIGH is 72 h = 3 days; half of it (1.5 d) scores 0, 3 d scores 1.
        assertThat(Scorer.spacingFit(candidate(SessionType.INTERVAL_RUN, day(3)), day(3), grid))
            .isWithin(1e-9).of(1.0)
        assertThat(Scorer.spacingFit(candidate(SessionType.INTERVAL_RUN, day(2)), day(2), grid))
            .isWithin(1e-9).of(1.0 / 3.0)
        assertThat(Scorer.spacingFit(candidate(SessionType.INTERVAL_RUN, day(1)), day(1), grid))
            .isWithin(1e-9).of(0.0)
        // Nothing of that intensity in the grid at all: perfect spacing.
        assertThat(Scorer.spacingFit(candidate(SessionType.EASY_RUN, day(1)), day(1), emptyGrid))
            .isWithin(1e-9).of(1.0)
    }

    @Test
    fun pref_fit_reaches_zero_when_the_sport_cap_is_used_up() {
        val input = SuggestFixtures.input(
            lockedPlanned = listOf(
                SuggestFixtures.locked(day(0)),
                SuggestFixtures.locked(day(2), id = 42L),
            ),
        )
        val grid = SuggestFixtures.grid(input)
        val cap2 = ctx(caps = mapOf(SportGroup.RUN to 2))
        val cap4 = ctx(caps = mapOf(SportGroup.RUN to 4))

        assertThat(Scorer.prefFit(candidate(SessionType.EASY_RUN, day(4)), day(4), grid, cap2))
            .isWithin(1e-9).of(0.0)
        assertThat(Scorer.prefFit(candidate(SessionType.EASY_RUN, day(4)), day(4), grid, cap4))
            .isWithin(1e-9).of(0.5)
        // No cap configured for the sport at all.
        assertThat(Scorer.prefFit(candidate(SessionType.EASY_RUN, day(4)), day(4), grid, ctx()))
            .isWithin(1e-9).of(1.0)
    }

    @Test
    fun the_weights_sum_to_one_and_total_is_their_weighted_sum() {
        assertThat(Scorer.W_GOAL + Scorer.W_LOAD + Scorer.W_RECOVERY + Scorer.W_SPACING + Scorer.W_PREF)
            .isWithin(1e-9).of(1.0)
        val breakdown = ScoreBreakdown(goalFit = 1.0, loadFit = 0.5, recoveryFit = 0.8, spacingFit = 0.0, prefFit = 1.0)
        assertThat(breakdown.total).isWithin(1e-9)
            .of(0.35 * 1.0 + 0.25 * 0.5 + 0.20 * 0.8 + 0.10 * 0.0 + 0.10 * 1.0)
        assertThat(ScoreBreakdown(1.0, 1.0, 1.0, 1.0, 1.0).total).isWithin(1e-9).of(1.0)
    }

    @Test
    fun phase_preferences_and_taper_duration_match_the_plan_table() {
        assertThat(Scorer.preferredTypes(TrainingPhase.BASE))
            .containsExactly(SessionType.EASY_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_FULL)
        assertThat(Scorer.preferredTypes(TrainingPhase.BUILD))
            .containsExactly(SessionType.TEMPO_RUN, SessionType.LONG_RUN, SessionType.STRENGTH_LOWER)
        assertThat(Scorer.preferredTypes(TrainingPhase.PEAK))
            .containsExactly(SessionType.INTERVAL_RUN, SessionType.TEMPO_RUN, SessionType.LONG_RUN)
        assertThat(Scorer.preferredTypes(TrainingPhase.RACE_WEEK))
            .containsExactly(SessionType.RECOVERY_RUN, SessionType.EASY_RUN, SessionType.MOBILITY)
        assertThat(Scorer.preferredTypes(TrainingPhase.IN_SEASON))
            .containsExactly(SessionType.EASY_RUN, SessionType.STRENGTH_UPPER, SessionType.MOBILITY)
        assertThat(Scorer.preferredTypes(TrainingPhase.RECOVERY_WEEK))
            .containsExactly(SessionType.RECOVERY_RUN, SessionType.EASY_RUN, SessionType.MOBILITY)

        assertThat(Scorer.durationFactor(TrainingPhase.TAPER, SessionType.INTERVAL_RUN))
            .isWithin(1e-9).of(0.6)
        assertThat(Scorer.durationFactor(TrainingPhase.TAPER, SessionType.EASY_RUN)).isWithin(1e-9).of(1.0)
        assertThat(Scorer.durationFactor(TrainingPhase.PEAK, SessionType.INTERVAL_RUN)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun goal_sport_groups_are_derived_from_the_goal_type() {
        assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = GoalType.RACE_TIME)))
            .isEqualTo(SportGroup.RUN)
        assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = GoalType.SOCCER_AVAILABILITY)))
            .isEqualTo(SportGroup.SOCCER)
        assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = GoalType.STRENGTH_LIFT)))
            .isEqualTo(SportGroup.STRENGTH)
        assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = GoalType.BODY_WEIGHT))).isNull()
        assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = GoalType.CONSISTENCY))).isNull()
        // P12.3: all three cycling goals are the same sport as far as scoring is concerned.
        listOf(GoalType.BIKE_FTP, GoalType.BIKE_VOLUME, GoalType.BIKE_EVENT).forEach { type ->
            assertThat(Scorer.goalSportGroup(SuggestFixtures.goal(type = type)))
                .isEqualTo(SportGroup.CYCLE)
        }
    }

    @Test
    fun a_cycle_goal_switches_the_phase_table_to_the_bike_one() {
        // P12.3's second phase table, used only when the primary goal's sport is CYCLE.
        assertThat(Scorer.preferredTypesFor(TrainingPhase.BASE, SportGroup.CYCLE))
            .containsExactly(SessionType.ENDURANCE_RIDE, SessionType.TRAINER_SESSION, SessionType.STRENGTH_FULL)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.BUILD, SportGroup.CYCLE))
            .containsExactly(SessionType.BIKE_INTERVALS, SessionType.ENDURANCE_RIDE, SessionType.STRENGTH_LOWER)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.PEAK, SportGroup.CYCLE))
            .containsExactly(SessionType.BIKE_INTERVALS, SessionType.ENDURANCE_RIDE)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.TAPER, SportGroup.CYCLE))
            .containsExactly(SessionType.RECOVERY_SPIN, SessionType.BIKE_INTERVALS)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.RACE_WEEK, SportGroup.CYCLE))
            .containsExactly(SessionType.RECOVERY_SPIN, SessionType.MOBILITY)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.IN_SEASON, SportGroup.CYCLE))
            .containsExactly(SessionType.ENDURANCE_RIDE, SessionType.STRENGTH_UPPER, SessionType.MOBILITY)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.RECOVERY_WEEK, SportGroup.CYCLE))
            .containsExactly(SessionType.RECOVERY_SPIN, SessionType.ENDURANCE_RIDE, SessionType.MOBILITY)
        assertThat(Scorer.preferredTypesFor(TrainingPhase.OFF_SEASON, SportGroup.CYCLE))
            .containsExactly(SessionType.TRAINER_SESSION, SessionType.STRENGTH_FULL, SessionType.MOBILITY)

        // Every other primary sport keeps the §3.5.5 table, exactly as before.
        listOf(SportGroup.RUN, SportGroup.STRENGTH, SportGroup.SOCCER, null).forEach { group ->
            TrainingPhase.entries.forEach { phase ->
                assertThat(Scorer.preferredTypesFor(phase, group))
                    .isEqualTo(Scorer.preferredTypes(phase))
            }
        }

        // The taper shortens bike intervals the way it shortens running intervals.
        assertThat(Scorer.durationFactor(TrainingPhase.TAPER, SessionType.BIKE_INTERVALS))
            .isWithin(1e-9).of(0.6)
        assertThat(Scorer.durationFactor(TrainingPhase.PEAK, SessionType.BIKE_INTERVALS))
            .isWithin(1e-9).of(1.0)
        assertThat(Scorer.durationFactor(TrainingPhase.TAPER, SessionType.ENDURANCE_RIDE))
            .isWithin(1e-9).of(1.0)
    }
}
