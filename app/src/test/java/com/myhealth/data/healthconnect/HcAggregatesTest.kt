package com.myhealth.data.healthconnect

import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The two daily-total guards of verification BUG-1 / BUG-6: buckets with no data origin are not
 * data, and a metric the aggregates left empty is recovered from the raw records without double
 * counting a source that writes both per-session and whole-day totals.
 */
class HcAggregatesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day: LocalDate = LocalDate.of(2026, 9, 11)

    // ---- BUG-1: the synthetic basal baseline ------------------------------------------------------

    @Test
    fun bucket_with_a_value_but_no_data_origins_is_treated_as_absent() {
        val result = AggregationResult(
            longValues = emptyMap(),
            doubleValues = mapOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL.metricKey to 1_564.5),
            dataOrigins = emptySet(),
        )

        val totals = HcAggregates.totalsOf(result)

        assertThat(totals.totalEnergyKcal).isNull()
        assertThat(totals.isAbsent()).isTrue()
    }

    @Test
    fun bucket_with_a_data_origin_keeps_every_metric() {
        val result = AggregationResult(
            longValues = mapOf(StepsRecord.COUNT_TOTAL.metricKey to 9_123L),
            doubleValues = mapOf(
                TotalCaloriesBurnedRecord.ENERGY_TOTAL.metricKey to 2_400.0,
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL.metricKey to 640.0,
                DistanceRecord.DISTANCE_TOTAL.metricKey to 7_500.0,
                FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL.metricKey to 12.0,
            ),
            dataOrigins = setOf(DataOrigin("com.garmin.android.apps.connectmobile")),
        )

        val totals = HcAggregates.totalsOf(result)

        assertThat(totals.steps).isEqualTo(9_123)
        assertThat(totals.totalEnergyKcal).isWithin(1e-6).of(2_400.0)
        assertThat(totals.activeEnergyKcal).isWithin(1e-6).of(640.0)
        assertThat(totals.distanceMeters).isWithin(1e-6).of(7_500.0)
        assertThat(totals.floors).isWithin(1e-6).of(12.0)
        assertThat(totals.isAbsent()).isFalse()
    }

    // ---- BUG-6: the raw-read fallback -------------------------------------------------------------

    @Test
    fun raw_fallback_sums_two_step_records_of_the_same_day() {
        val points = listOf(
            point(fromHour = 8, toHour = 9, value = 3_000.0),
            point(fromHour = 17, toHour = 18, value = 5_500.0),
        )

        val sums = sumRawPointsByDay(points, zone)

        assertThat(sums.keys).containsExactly(day.toEpochDay())
        assertThat(sums.getValue(day.toEpochDay())).isWithin(1e-6).of(8_500.0)
    }

    @Test
    fun raw_fallback_keeps_local_days_apart() {
        val points = listOf(
            point(fromHour = 23, toHour = 24, value = 1_000.0),
            point(fromHour = 25, toHour = 26, value = 400.0),
        )

        val sums = sumRawPointsByDay(points, zone)

        assertThat(sums.getValue(day.toEpochDay())).isWithin(1e-6).of(1_000.0)
        assertThat(sums.getValue(day.toEpochDay() + 1)).isWithin(1e-6).of(400.0)
    }

    @Test
    fun a_whole_day_record_wins_over_the_partial_records_of_the_same_origin() {
        val points = listOf(
            point(fromHour = 8, toHour = 9, value = 3_000.0),
            point(fromHour = 17, toHour = 18, value = 5_500.0),
            point(fromHour = 0, toHour = 24, value = 9_100.0),
        )

        val sums = sumRawPointsByDay(points, zone)

        assertThat(sums.getValue(day.toEpochDay())).isWithin(1e-6).of(9_100.0)
    }

    @Test
    fun only_the_origin_with_the_most_records_of_a_day_is_summed() {
        val points = listOf(
            point(fromHour = 8, toHour = 9, value = 3_000.0, origin = "com.garmin"),
            point(fromHour = 17, toHour = 18, value = 5_500.0, origin = "com.garmin"),
            point(fromHour = 0, toHour = 24, value = 4_000.0, origin = "com.phone"),
        )

        val sums = sumRawPointsByDay(points, zone)

        assertThat(sums.getValue(day.toEpochDay())).isWithin(1e-6).of(8_500.0)
    }

    /** One raw record starting [fromHour] hours after local midnight of [day]. */
    private fun point(
        fromHour: Long,
        toHour: Long,
        value: Double,
        origin: String = "com.garmin",
    ): HcRawPoint {
        val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return HcRawPoint(
            origin = origin,
            startMillis = midnight + fromHour * 3_600_000L,
            endMillis = midnight + toHour * 3_600_000L,
            value = value,
        )
    }
}
