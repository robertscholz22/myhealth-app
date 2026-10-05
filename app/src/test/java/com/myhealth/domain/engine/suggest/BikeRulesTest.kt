package com.myhealth.domain.engine.suggest

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.suggest.SuggestFixtures.day
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import org.junit.Test
import java.time.LocalDate
import java.time.Month

/** The three rules of [BikeRules] (PLAN §3.5.8, P12.3), each on its own. */
class BikeRulesTest {

    private val trainer = BikeContext(enabled = true, trainerAvailable = true)
    private val noTrainer = BikeContext(enabled = true, trainerAvailable = false)

    private fun entry(type: SessionType): CatalogEntry =
        requireNotNull(SessionCatalog.entryFor(type)) { "no catalog row for $type" }

    private fun epochDay(iso: String): Long = LocalDate.parse(iso).toEpochDay()

    @Test
    fun indoor_season_is_november_to_march_inclusive() {
        val insideByMonth = Month.entries.associateWith { month ->
            BikeRules.isIndoorSeason(LocalDate.of(2026, month, 15).toEpochDay())
        }
        assertThat(insideByMonth.filterValues { it }.keys).containsExactly(
            Month.NOVEMBER,
            Month.DECEMBER,
            Month.JANUARY,
            Month.FEBRUARY,
            Month.MARCH,
        )
        // The boundaries themselves, evaluated per day.
        assertThat(BikeRules.isIndoorSeason(epochDay("2026-10-31"))).isFalse()
        assertThat(BikeRules.isIndoorSeason(epochDay("2026-11-01"))).isTrue()
        assertThat(BikeRules.isIndoorSeason(epochDay("2027-03-31"))).isTrue()
        assertThat(BikeRules.isIndoorSeason(epochDay("2027-04-01"))).isFalse()
    }

    @Test
    fun bike_is_enabled_by_a_cycle_cap_or_an_active_bike_goal() {
        assertThat(BikeRules.isBikeEnabled("{}", emptyList())).isFalse()
        assertThat(BikeRules.isBikeEnabled(SuggestFixtures.bikeSportsJson(cycleCap = 0), emptyList()))
            .isFalse()
        assertThat(BikeRules.isBikeEnabled(SuggestFixtures.bikeSportsJson(cycleCap = 1), emptyList()))
            .isTrue()

        BikeRules.BIKE_GOAL_TYPES.forEach { type ->
            assertThat(BikeRules.isBikeEnabled("{}", listOf(SuggestFixtures.bikeGoal(type = type))))
                .isTrue()
        }
        // A non-cycling goal does not enable it, and neither does an archived bike goal.
        assertThat(BikeRules.isBikeEnabled("{}", listOf(SuggestFixtures.raceGoal(day(30))))).isFalse()
        val done = SuggestFixtures.goal(type = GoalType.BIKE_FTP, status = GoalStatus.ACHIEVED)
        assertThat(BikeRules.isBikeEnabled("{}", listOf(done))).isFalse()
    }

    @Test
    fun the_primary_bike_goal_is_the_lowest_priority_active_one() {
        val secondary = SuggestFixtures.bikeGoal(type = GoalType.BIKE_VOLUME, priority = 3, id = 9L)
        val primary = SuggestFixtures.bikeGoal(type = GoalType.BIKE_FTP, priority = 2, id = 8L)
        val goals = listOf(SuggestFixtures.raceGoal(day(30)), secondary, primary)

        assertThat(BikeRules.primaryBikeGoal(goals)?.id).isEqualTo(8L)
        assertThat(BikeRules.primaryBikeGoal(listOf(SuggestFixtures.raceGoal(day(30))))).isNull()
    }

    @Test
    fun the_trainer_moves_cycle_rows_indoors_only_in_season() {
        val december = epochDay("2026-12-07")
        val june = epochDay("2026-06-07")
        val ride = entry(SessionType.ENDURANCE_RIDE)

        assertThat(BikeRules.entryFor(ride, december, trainer)?.sportType)
            .isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(BikeRules.entryFor(ride, june, trainer)?.sportType).isEqualTo(SportType.CYCLING)
        assertThat(BikeRules.entryFor(ride, december, noTrainer)?.sportType)
            .isEqualTo(SportType.CYCLING)
        // Everything else about the row survives the move.
        val moved = requireNotNull(BikeRules.entryFor(ride, december, trainer))
        assertThat(moved.copy(sportType = SportType.CYCLING)).isEqualTo(ride)

        // `CROSS_TRAINING` is a CYCLING row too, so it moves with them.
        assertThat(BikeRules.entryFor(entry(SessionType.CROSS_TRAINING), december, trainer)?.sportType)
            .isEqualTo(SportType.CYCLING_INDOOR)
        // A run never moves.
        assertThat(BikeRules.entryFor(entry(SessionType.EASY_RUN), december, trainer)?.sportType)
            .isEqualTo(SportType.RUN_OUTDOOR)
    }

    @Test
    fun the_trainer_session_row_needs_the_equipment_and_is_offered_year_round() {
        val row = entry(SessionType.TRAINER_SESSION)
        listOf(epochDay("2026-12-07"), epochDay("2026-06-07")).forEach { day ->
            assertThat(BikeRules.entryFor(row, day, noTrainer)).isNull()
            assertThat(BikeRules.entryFor(row, day, trainer)?.sportType)
                .isEqualTo(SportType.CYCLING_INDOOR)
        }
    }

    @Test
    fun spacing_counts_bike_intervals_against_bike_intervals_and_any_hard_work() {
        val grid = SuggestionGrid.seed(
            SuggestFixtures.input(
                lockedPlanned = listOf(
                    SuggestFixtures.locked(
                        day = day(3),
                        sessionType = SessionType.BIKE_INTERVALS,
                        sportType = SportType.CYCLING,
                        intensity = com.myhealth.domain.model.Intensity.HIGH,
                        minutes = 60,
                        estimatedTrimp = 144.0,
                    ),
                ),
            ),
        )
        fun violates(type: SessionType, offset: Long): Boolean =
            BikeRules.violatesSpacing(SuggestFixtures.candidate(type, day(offset)), day(offset), grid)

        assertThat(violates(SessionType.BIKE_INTERVALS, 5)).isTrue()
        assertThat(violates(SessionType.BIKE_INTERVALS, 6)).isFalse()
        assertThat(violates(SessionType.TEMPO_RUN, 4)).isTrue()
        assertThat(violates(SessionType.TEMPO_RUN, 5)).isFalse()
        assertThat(violates(SessionType.EASY_RUN, 4)).isFalse()
        assertThat(BikeRules.BIKE_INTERVAL_SPACING_DAYS).isEqualTo(3L)
        assertThat(BikeRules.BIKE_HARD_SPACING_DAYS).isEqualTo(2L)
    }
}
