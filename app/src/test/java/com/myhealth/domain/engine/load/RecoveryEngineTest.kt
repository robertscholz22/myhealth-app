package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.RecoveryBand
import com.myhealth.domain.model.SleepRecord
import org.junit.Test

/** The recovery cases of PLAN §3.3 (`rec01`…`rec10`). */
class RecoveryEngineTest {

    private val day = 20_000L

    private fun sleep(
        totalSleepMin: Int = 480,
        deepMin: Int? = 86,
        remMin: Int? = 106,
    ) = SleepRecord(
        id = 1L,
        startAtMillis = 0L,
        endAtMillis = totalSleepMin * 60_000L,
        night = day,
        totalSleepMin = totalSleepMin,
        lightMin = null,
        deepMin = deepMin,
        remMin = remMin,
        awakeMin = null,
        stages = null,
        source = ActivitySource.HEALTH_CONNECT,
        externalId = null,
        sleepScore = null,
    )

    private fun load(
        acwr: Double? = 1.0,
        tsb: Double = 0.0,
        monotony: Double? = 1.0,
        flags: List<String> = emptyList(),
    ) = DailyLoad(
        day = day,
        trimp = 80.0,
        sessionCount = 1,
        atl = 80.0,
        ctl = 80.0,
        acwr = acwr,
        tsb = tsb,
        monotony = monotony,
        strain = 1200.0,
        recoveryScore = null,
        recoveryBand = null,
        recoveryConfidence = 0.0,
        flags = flags,
        computedAtMillis = 0L,
    )

    /** The `rec01` reference athlete: everything available and everything good. */
    private fun perfect(
        lastNight: SleepRecord? = sleep(),
        restingHrToday: Int? = 48,
        restingHrLast30: List<Int> = List(30) { 50 },
        dailyLoad: DailyLoad? = load(),
        hrvTodayMs: Double? = 105.0,
        hrvLast7Ms: List<Double> = List(7) { 100.0 },
    ) = RecoveryInput(
        day = day,
        lastNight = lastNight,
        bedtimeMinuteOfDay = 1380,
        bedtimeMinutesLast14 = List(14) { 1380 },
        sleepMinutesLast7 = List(7) { 480 },
        sleepTargetHours = 8.0,
        restingHrToday = restingHrToday,
        restingHrLast30 = restingHrLast30,
        load = dailyLoad,
        hrvTodayMs = hrvTodayMs,
        hrvLast7Ms = hrvLast7Ms,
    )

    private fun points(state: com.myhealth.domain.model.RecoveryState, name: String): Double =
        state.components.first { it.name == name }.points

    @Test
    fun rec01_perfect_inputs_score_fresh() {
        val state = RecoveryEngine.compute(perfect())

        // 38.33 (sleep) + 25 (rhr) + 25 (load) + 10 (hrv) = 98.33 of 100.
        assertThat(state.score!!).isAtLeast(90)
        assertThat(state.band).isEqualTo(RecoveryBand.FRESH)
        assertThat(state.confidence).isWithin(1e-9).of(1.0)
        assertThat(state.components).hasSize(4)
    }

    @Test
    fun rec02_missing_sleep_renormalises_weights() {
        val state = RecoveryEngine.compute(perfect(lastNight = null))

        assertThat(state.confidence).isWithin(1e-9).of(0.60)
        assertThat(state.score!!).isIn(0..100)
        assertThat(state.components.map { it.name }).containsExactly(
            RecoveryComponents.RESTING_HR, RecoveryComponents.LOAD, RecoveryComponents.HRV,
        )
        assertThat(state.warnings.map { it.code }).contains(EngineWarningCode.LOW_CONFIDENCE)
    }

    @Test
    fun rec03_all_missing_returns_null_score() {
        val state = RecoveryEngine.compute(RecoveryInput(day = day))

        assertThat(state.score).isNull()
        assertThat(state.band).isNull()
        assertThat(state.confidence).isEqualTo(0.0)
        assertThat(state.warnings.map { it.code }).contains(EngineWarningCode.INSUFFICIENT_HISTORY)
    }

    @Test
    fun rec04_elevated_rhr_drops_score() {
        val state = RecoveryEngine.compute(perfect(restingHrToday = 55))

        // delta = +5 -> 25 * (7 - 5) / 8 = 6.25.
        assertThat(points(state, RecoveryComponents.RESTING_HR)).isWithin(1e-9).of(6.25)
        assertThat(state.score!!).isLessThan(RecoveryEngine.compute(perfect()).score!!)
    }

    @Test
    fun rec05_high_acwr_drops_load_points() {
        val state = RecoveryEngine.compute(perfect(dailyLoad = load(acwr = 1.6)))

        assertThat(RecoveryEngine.acwrPoints(1.6)).isWithin(1e-9).of(5.0)
        assertThat(points(state, RecoveryComponents.LOAD)).isWithin(1e-9).of(5.0)
    }

    @Test
    fun rec06_short_sleep_four_hours_scores_zero_duration() {
        val state = RecoveryEngine.compute(perfect(lastNight = sleep(totalSleepMin = 240)))

        assertThat(RecoveryEngine.sleepDurationPoints(4.0)).isEqualTo(0.0)
        // Only the quality and consistency sub-scores remain.
        assertThat(points(state, RecoveryComponents.SLEEP)).isAtMost(15.0)
    }

    @Test
    fun rec07_oversleep_ten_hours_penalised_mildly() {
        assertThat(RecoveryEngine.sleepDurationPoints(10.0)).isWithin(1e-9).of(22.0)
        assertThat(RecoveryEngine.sleepDurationPoints(8.5)).isWithin(1e-9).of(25.0)
    }

    @Test
    fun rec08_sleep_debt_flag() {
        val debt = RecoveryEngine.compute(
            perfect().copy(sleepMinutesLast7 = List(7) { 300 }, sleepTargetHours = 8.0),
        )
        val noDebt = RecoveryEngine.compute(perfect())

        assertThat(debt.flags).contains(RecoveryFlags.SLEEP_DEBT)
        assertThat(noDebt.flags).doesNotContain(RecoveryFlags.SLEEP_DEBT)
    }

    @Test
    fun rec09_band_boundaries() {
        assertThat(RecoveryEngine.bandFor(80)).isEqualTo(RecoveryBand.FRESH)
        assertThat(RecoveryEngine.bandFor(79)).isEqualTo(RecoveryBand.GOOD)
        assertThat(RecoveryEngine.bandFor(65)).isEqualTo(RecoveryBand.GOOD)
        assertThat(RecoveryEngine.bandFor(64)).isEqualTo(RecoveryBand.MODERATE)
        assertThat(RecoveryEngine.bandFor(50)).isEqualTo(RecoveryBand.MODERATE)
        assertThat(RecoveryEngine.bandFor(49)).isEqualTo(RecoveryBand.FATIGUED)
        assertThat(RecoveryEngine.bandFor(35)).isEqualTo(RecoveryBand.FATIGUED)
        assertThat(RecoveryEngine.bandFor(34)).isEqualTo(RecoveryBand.STRAINED)
    }

    @Test
    fun rec10_high_monotony_scales_load_points() {
        val state = RecoveryEngine.compute(perfect(dailyLoad = load(monotony = 2.5)))

        assertThat(points(state, RecoveryComponents.LOAD)).isWithin(1e-9).of(25.0 * 0.85)
        assertThat(RecoveryEngine.loadPoints(acwr = 1.0, tsb = 0.0, monotony = 2.0))
            .isWithin(1e-9).of(25.0)
    }

    @Test
    fun hrv_component_needs_a_seven_day_baseline() {
        val state = RecoveryEngine.compute(perfect(hrvLast7Ms = List(6) { 100.0 }))

        assertThat(state.components.map { it.name }).doesNotContain(RecoveryComponents.HRV)
        assertThat(state.confidence).isWithin(1e-9).of(0.90)
    }

    @Test
    fun missing_sleep_stages_score_neutrally_with_a_low_confidence_warning() {
        val state = RecoveryEngine.compute(
            perfect(lastNight = sleep(deepMin = null, remMin = null)),
        )

        // 23.333 duration + 5 neutral quality + 5 consistency.
        assertThat(points(state, RecoveryComponents.SLEEP)).isWithin(0.001).of(33.3333)
        assertThat(state.warnings.map { it.code }).contains(EngineWarningCode.LOW_CONFIDENCE)
    }

    @Test
    fun load_flags_are_copied_through() {
        val state = RecoveryEngine.compute(
            perfect(dailyLoad = load(flags = listOf(LoadFlags.HIGH_STRAIN))),
        )

        assertThat(state.flags).contains(LoadFlags.HIGH_STRAIN)
    }

    @Test
    fun an_extra_component_takes_part_in_the_renormalisation() {
        val bodyBattery = com.myhealth.domain.model.RecoveryComponent("BODY_BATTERY", 5.0, 10.0)
        val state = RecoveryEngine.compute(perfect().copy(extraComponents = listOf(bodyBattery)))

        assertThat(state.components).hasSize(5)
        assertThat(state.confidence).isWithin(1e-9).of(1.0)
        assertThat(state.score!!).isWithin(1).of(94)
    }

    @Test
    fun late_bedtime_costs_consistency_points() {
        val state = RecoveryEngine.compute(perfect().copy(bedtimeMinuteOfDay = 90))

        // Median bedtime 23:00, asleep at 01:30 -> 150 min off -> 5 * (1 - 120/90) < 0 -> 0.
        assertThat(RecoveryEngine.circularDeviationMin(90.0, 1380.0)).isEqualTo(150.0)
        assertThat(points(state, RecoveryComponents.SLEEP)).isWithin(0.001).of(33.3333)
    }

    @Test
    fun negative_form_lowers_the_load_component() {
        // tsbAdj = clamp(tsb/10, -5, +5): +2 is absorbed by the 25-point ceiling, -2 is not.
        val fresh = RecoveryEngine.loadPoints(acwr = 1.0, tsb = 20.0, monotony = 1.0)
        val slightlyTired = RecoveryEngine.loadPoints(acwr = 1.0, tsb = -20.0, monotony = 1.0)
        val deeplyTired = RecoveryEngine.loadPoints(acwr = 1.0, tsb = -80.0, monotony = 1.0)

        assertThat(fresh).isWithin(1e-9).of(25.0)
        assertThat(slightlyTired).isWithin(1e-9).of(23.0)
        assertThat(deeplyTired).isWithin(1e-9).of(20.0)
    }
}
