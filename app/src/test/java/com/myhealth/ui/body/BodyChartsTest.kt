package com.myhealth.ui.body

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.SleepRecord
import com.myhealth.ui.common.charts.ChartPoint
import org.junit.Test

/** Unit tests for the Body screen's data to chart-series mappers (PLAN P8.3). */
class BodyChartsTest {

    private fun measurement(day: Long, weightKg: Double? = null, bodyFatPercent: Double? = null) =
        BodyMeasurement(
            id = day,
            measuredAtMillis = day * 86_400_000L,
            day = day,
            weightKg = weightKg,
            bodyFatPercent = bodyFatPercent,
            muscleMassKg = null,
            boneMassKg = null,
            bodyWaterPercent = null,
            source = ActivitySource.MANUAL,
        )

    @Test
    fun weightPoints_emits_one_point_per_day_with_gaps_for_missing_days() {
        val points = weightPoints(listOf(measurement(10, 80.0), measurement(12, 79.0)), 10, 12)
        assertThat(points.map { it.x }).containsExactly(10.0, 11.0, 12.0).inOrder()
        assertThat(points[0].y!!).isWithin(1e-9).of(80.0)
        assertThat(points[1].y).isNull()
        assertThat(points[2].y!!).isWithin(1e-9).of(79.0)
    }

    @Test
    fun weightPoints_averages_several_readings_on_the_same_day() {
        val points = weightPoints(listOf(measurement(10, 80.0), measurement(10, 82.0)), 10, 10)
        assertThat(points).hasSize(1)
        assertThat(points[0].y!!).isWithin(1e-9).of(81.0)
    }

    @Test
    fun bodyFatPoints_ignores_rows_that_only_carry_a_weight() {
        val points = bodyFatPoints(listOf(measurement(10, weightKg = 80.0), measurement(11, bodyFatPercent = 17.5)), 10, 11)
        assertThat(points[0].y).isNull()
        assertThat(points[1].y!!).isWithin(1e-9).of(17.5)
    }

    @Test
    fun movingAveragePoints_keeps_the_x_grid_and_smooths_y() {
        val points = listOf(ChartPoint(1.0, 10.0), ChartPoint(2.0, 20.0), ChartPoint(3.0, 30.0))
        val averaged = movingAveragePoints(points, window = 2)
        assertThat(averaged.map { it.x }).containsExactly(1.0, 2.0, 3.0).inOrder()
        assertThat(averaged[1].y!!).isWithin(1e-9).of(15.0)
        assertThat(averaged[2].y!!).isWithin(1e-9).of(25.0)
    }

    @Test
    fun sleepHoursBars_returns_one_slot_per_night_with_zero_for_missing_nights() {
        val record = SleepRecord(
            id = 1,
            startAtMillis = 0,
            endAtMillis = 0,
            night = 11,
            totalSleepMin = 450,
            lightMin = null,
            deepMin = null,
            remMin = null,
            awakeMin = null,
            stages = null,
            source = ActivitySource.HEALTH_CONNECT,
            externalId = null,
            sleepScore = null,
        )
        val bars = sleepHoursBars(listOf(record), 10, 12)
        assertThat(bars).hasSize(3)
        assertThat(bars[0]).isWithin(1e-9).of(0.0)
        assertThat(bars[1]).isWithin(1e-9).of(7.5)
        assertThat(bars[2]).isWithin(1e-9).of(0.0)
    }

    @Test
    fun dayAxisLabels_gives_first_middle_and_last() {
        // Epoch days 19723 = 2024-01-01, 19753 = 2024-01-31.
        assertThat(dayAxisLabels(19723, 19753)).containsExactly("1 Jan", "16 Jan", "31 Jan").inOrder()
    }

    @Test
    fun dayAxisLabels_collapses_a_single_day_window() {
        assertThat(dayAxisLabels(19723, 19723)).hasSize(1)
    }
}
