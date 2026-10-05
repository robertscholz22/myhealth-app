package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.EngineWarningCode
import org.junit.Test

/** The load-series cases of PLAN §3.2.4 (`load09`…`load17`), plus the secondary rolling view. */
class LoadSeriesEngineTest {

    private val startDay = 20_000L
    private val now = 1_700_000_000_000L

    private fun series(loads: List<Double>, sessionCounts: List<Int>? = null): List<DayLoadInput> =
        loads.mapIndexed { index, trimp ->
            DayLoadInput(
                day = startDay + index,
                trimp = trimp,
                sessionCount = sessionCounts?.get(index) ?: if (trimp > 0.0) 1 else 0,
            )
        }

    private fun compute(loads: List<Double>, sessionCounts: List<Int>? = null) =
        LoadSeriesEngine.computeDetailed(series(loads, sessionCounts), now)

    @Test
    fun load09_ewma_atl_ctl_converge_on_constant_load() {
        val last = compute(List(200) { 100.0 }).last()

        assertThat(last.load.atl).isWithin(0.5).of(100.0)
        assertThat(last.load.ctl).isWithin(0.5).of(100.0)
        assertThat(last.load.acwr!!).isWithin(0.01).of(1.0)
        assertThat(last.zone).isEqualTo(AcwrZone.OPTIMAL)
        assertThat(last.reliable).isTrue()
    }

    @Test
    fun load10_acwr_null_when_ctl_below_one() {
        val days = compute(listOf(5.0, 5.0))

        assertThat(days.last().load.ctl).isLessThan(1.0)
        assertThat(days[0].load.acwr).isNull()
        assertThat(days[1].load.acwr).isNull()
        assertThat(days[1].zone).isNull()
    }

    @Test
    fun load11_monotony_zero_when_no_load() {
        val last = compute(List(10) { 0.0 }).last()

        assertThat(last.load.monotony).isEqualTo(0.0)
        assertThat(last.load.strain).isEqualTo(0.0)
        assertThat(last.weeklyLoad).isEqualTo(0.0)
    }

    @Test
    fun load12_monotony_capped_at_three_for_constant_load() {
        val last = compute(List(7) { 100.0 }).last()

        assertThat(last.load.monotony).isEqualTo(3.0)
        assertThat(last.load.strain).isWithin(0.001).of(2100.0)
        assertThat(last.load.flags).contains(LoadFlags.HIGH_MONOTONY)
    }

    @Test
    fun load13_monotony_known_value() {
        val week = listOf(100.0, 0.0, 50.0, 80.0, 0.0, 120.0, 60.0)
        val last = compute(week).last()

        assertThat(LoadSeriesEngine.populationSd(week)).isWithin(0.001).of(42.905)
        assertThat(last.weeklyLoad).isWithin(0.001).of(410.0)
        assertThat(last.load.monotony!!).isWithin(0.001).of(1.3652)
        assertThat(last.load.strain!!).isWithin(0.5).of(559.7)
    }

    @Test
    fun load14_acwr_zones_boundaries() {
        assertThat(LoadSeriesEngine.acwrZone(0.79)).isEqualTo(AcwrZone.DETRAINING)
        assertThat(LoadSeriesEngine.acwrZone(0.80)).isEqualTo(AcwrZone.OPTIMAL)
        assertThat(LoadSeriesEngine.acwrZone(1.30)).isEqualTo(AcwrZone.OPTIMAL)
        assertThat(LoadSeriesEngine.acwrZone(1.31)).isEqualTo(AcwrZone.CAUTION)
        assertThat(LoadSeriesEngine.acwrZone(1.50)).isEqualTo(AcwrZone.CAUTION)
        assertThat(LoadSeriesEngine.acwrZone(1.51)).isEqualTo(AcwrZone.HIGH_RISK)
    }

    @Test
    fun load15_no_rest_day_flag() {
        val sevenTrainingDays = compute(List(7) { 60.0 }, sessionCounts = List(7) { 1 })
        val withOneRestDay = compute(
            listOf(60.0, 60.0, 60.0, 0.0, 60.0, 60.0, 60.0),
            sessionCounts = listOf(1, 1, 1, 0, 1, 1, 1),
        )

        assertThat(sevenTrainingDays.last().load.flags).contains(LoadFlags.NO_REST_DAY_7D)
        assertThat(withOneRestDay.last().load.flags).doesNotContain(LoadFlags.NO_REST_DAY_7D)
        // The flag needs a full 7-day window inside the series.
        assertThat(sevenTrainingDays[5].load.flags).doesNotContain(LoadFlags.NO_REST_DAY_7D)
    }

    @Test
    fun load16_ramp_high_flag() {
        val days = compute(List(7) { 100.0 } + List(7) { 120.0 })

        assertThat(days.last().weeklyLoad).isWithin(0.001).of(840.0)
        assertThat(days.last().load.flags).contains(LoadFlags.RAMP_HIGH)
        // A flat second week is not a ramp.
        assertThat(compute(List(14) { 100.0 }).last().load.flags)
            .doesNotContain(LoadFlags.RAMP_HIGH)
    }

    @Test
    fun load17_insufficient_history_warning() {
        val last = compute(List(10) { 80.0 }).last()

        assertThat(last.reliable).isFalse()
        assertThat(last.load.flags).contains(LoadFlags.INSUFFICIENT_HISTORY)
        assertThat(last.warnings.map { it.code }).contains(EngineWarningCode.INSUFFICIENT_HISTORY)
        assertThat(last.load.acwr).isNotNull()
    }

    @Test
    fun tsb_uses_yesterdays_atl_and_ctl() {
        val days = compute(listOf(0.0, 0.0, 100.0, 0.0))

        assertThat(days[0].load.tsb).isEqualTo(0.0)
        // Day 3 sees day 2's fatigue: ctl2 - atl2 = 6.897 - 25.0.
        assertThat(days[3].load.tsb).isWithin(0.01).of(6.8966 - 25.0)
    }

    @Test
    fun rolling_atl7_and_ctl28_are_reported_alongside_the_ewma() {
        val last = compute(List(28) { 70.0 }).last()

        assertThat(last.rolling.atl7).isWithin(0.001).of(70.0)
        assertThat(last.rolling.ctl28).isWithin(0.001).of(70.0)
        assertThat(last.rolling.acwr!!).isWithin(0.001).of(1.0)
    }

    @Test
    fun gaps_in_the_input_are_filled_with_zero_load_days() {
        val days = LoadSeriesEngine.compute(
            listOf(
                DayLoadInput(startDay, 100.0, 1),
                DayLoadInput(startDay + 3, 100.0, 1),
            ),
            now,
        )

        assertThat(days).hasSize(4)
        assertThat(days.map { it.day }).containsExactly(
            startDay, startDay + 1, startDay + 2, startDay + 3,
        ).inOrder()
        assertThat(days[1].trimp).isEqualTo(0.0)
    }

    @Test
    fun high_strain_flag_above_six_thousand() {
        val last = compute(List(7) { 400.0 }).last()

        assertThat(last.load.strain!!).isWithin(0.001).of(8400.0)
        assertThat(last.load.flags).contains(LoadFlags.HIGH_STRAIN)
    }

    @Test
    fun an_empty_series_produces_no_rows() {
        assertThat(LoadSeriesEngine.compute(emptyList(), now)).isEmpty()
    }
}
