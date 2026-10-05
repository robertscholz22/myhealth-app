package com.myhealth.domain.engine.nutrition

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.nutrition.NutritionFixtures.TODAY
import com.myhealth.domain.engine.nutrition.NutritionFixtures.activity
import com.myhealth.domain.engine.nutrition.NutritionFixtures.planned
import com.myhealth.domain.model.SportType
import com.myhealth.testutil.Fixtures
import org.junit.Test

/** The MET table and the ACSM running formula of PLAN §3.1.2. */
class MetTableTest {

    @Test
    fun the_acsm_run_formula_holds_at_8_10_and_14_kmh() {
        // MET = 0.952 * v[km/h] + 1.
        assertThat(MetTable.runMet(8.0)).isWithin(1e-9).of(8.616)
        assertThat(MetTable.runMet(10.0)).isWithin(1e-9).of(10.52)
        assertThat(MetTable.runMet(14.0)).isWithin(1e-9).of(14.328)
    }

    @Test
    fun the_run_met_is_clamped_and_falls_back_when_the_speed_is_unknown() {
        assertThat(MetTable.runMet(1.0)).isEqualTo(6.0)
        assertThat(MetTable.runMet(40.0)).isEqualTo(20.0)
        assertThat(MetTable.met(SportType.RUN_TREADMILL, speedKmh = null)).isEqualTo(9.8)
        assertThat(MetTable.met(SportType.RUN_TRAIL, speedKmh = 0.0)).isEqualTo(9.8)
    }

    @Test
    fun the_table_matches_the_plan_for_every_sport() {
        assertThat(MetTable.met(SportType.SOCCER_MATCH)).isEqualTo(10.0)
        assertThat(MetTable.met(SportType.SOCCER_TRAINING)).isEqualTo(7.0)
        assertThat(MetTable.met(SportType.WALK)).isEqualTo(3.5)
        assertThat(MetTable.met(SportType.HIKE)).isEqualTo(6.0)
        assertThat(MetTable.met(SportType.CYCLING)).isEqualTo(8.0)
        assertThat(MetTable.met(SportType.CYCLING_INDOOR)).isEqualTo(7.0)
        assertThat(MetTable.met(SportType.STRENGTH)).isEqualTo(5.0)
        assertThat(MetTable.met(SportType.HIIT)).isEqualTo(8.0)
        assertThat(MetTable.met(SportType.MOBILITY)).isEqualTo(2.5)
        assertThat(MetTable.met(SportType.SWIM)).isEqualTo(8.0)
        assertThat(MetTable.met(SportType.ROWING)).isEqualTo(7.0)
        assertThat(MetTable.met(SportType.OTHER)).isEqualTo(6.0)
        assertThat(MetTable.met(SportType.UNKNOWN)).isEqualTo(6.0)
    }

    @Test
    fun met_kcal_removes_the_resting_component() {
        // (10.52 - 1) * 80 kg * 1 h.
        assertThat(MetTable.metKcal(10.52, 80.0, 1.0)).isWithin(1e-6).of(761.6)
        assertThat(MetTable.metKcal(1.0, 80.0, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun a_recorded_active_energy_wins_over_the_met_estimate() {
        val recorded = activity(sportType = SportType.RUN_OUTDOOR, durationMin = 60, activeEnergyKcal = 500.0)
        val estimated = activity(sportType = SportType.RUN_OUTDOOR, durationMin = 60, avgSpeedMps = 10.0 / 3.6)

        assertThat(TrainingEnergyCalculator.completedKcal(recorded, 80.0)).isEqualTo(500.0)
        assertThat(TrainingEnergyCalculator.completedKcal(estimated, 80.0)).isWithin(0.5).of(761.6)
    }

    @Test
    fun nut22_power_kcal_250w_1h_900kcal_when_no_recorded_energy() {
        // 250 W x 3600 s = 900 kJ, and a cyclist's ~24 % gross efficiency makes that ~900 kcal.
        val ride = activity(sportType = SportType.CYCLING, durationMin = 60, avgPowerW = 250)

        assertThat(TrainingEnergyCalculator.completedKcal(ride, 80.0)).isWithin(1e-9).of(900.0)

        // A recorded active energy still wins over the power estimate.
        val recorded = activity(
            sportType = SportType.CYCLING,
            durationMin = 60,
            avgPowerW = 250,
            activeEnergyKcal = 700.0,
        )
        assertThat(TrainingEnergyCalculator.completedKcal(recorded, 80.0)).isEqualTo(700.0)

        // Without power the MET table still applies: (8 - 1) x 80 kg x 1 h.
        val noPower = activity(sportType = SportType.CYCLING, durationMin = 60)
        assertThat(TrainingEnergyCalculator.completedKcal(noPower, 80.0)).isWithin(1e-9).of(560.0)
    }

    @Test
    fun an_executed_plan_is_counted_once() {
        val completed = activity(sportType = SportType.RUN_OUTDOOR, durationMin = 60, activeEnergyKcal = 500.0)
        val overlapping = planned(
            sportType = SportType.RUN_OUTDOOR,
            targetDurationMin = 60,
            startMinuteOfDay = 18 * 60 + 10,
        )
        val separate = planned(
            id = 2L,
            sportType = SportType.RUN_OUTDOOR,
            targetDurationMin = 60,
            startMinuteOfDay = 6 * 60,
            targetPaceSecPerKm = 360,
        )

        val overlap = TrainingEnergyCalculator.forDay(
            date = TODAY,
            completed = listOf(completed),
            planned = listOf(overlapping),
            weightKg = 80.0,
            zone = Fixtures.TZ,
        )
        val both = TrainingEnergyCalculator.forDay(
            date = TODAY,
            completed = listOf(completed),
            planned = listOf(separate),
            weightKg = 80.0,
            zone = Fixtures.TZ,
        )

        assertThat(overlap.kcal).isEqualTo(500.0)
        assertThat(overlap.durationMin).isEqualTo(60.0)
        // A 6:00/km plan at 10 km/h is (10.52 - 1) * 80 * 1 h on top of the recorded 500 kcal.
        assertThat(both.kcal).isWithin(0.5).of(500.0 + 761.6)
        assertThat(both.durationMin).isEqualTo(120.0)
    }
}
