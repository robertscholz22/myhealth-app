package com.myhealth.ui.activities

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for the pace series behind the activity-detail pace chart (PLAN P8.3). */
class PaceSeriesTest {

    @Test
    fun steady_two_hundred_metres_per_minute_is_five_minutes_per_kilometre() {
        val points = paceSeriesFromStream(
            offsetsSec = intArrayOf(0, 60, 120, 180),
            distanceMeters = doubleArrayOf(0.0, 200.0, 400.0, 600.0),
        )
        assertThat(points.map { it.x }).containsExactly(1.0, 2.0, 3.0).inOrder()
        points.forEach { assertThat(it.y!!).isWithin(1e-6).of(300.0) }
    }

    @Test
    fun a_stopped_sample_becomes_a_gap_not_an_infinite_pace() {
        val points = paceSeriesFromStream(
            offsetsSec = intArrayOf(0, 10, 20, 30),
            distanceMeters = doubleArrayOf(0.0, 50.0, 50.0, 100.0),
        )
        assertThat(points).hasSize(3)
        assertThat(points[0].y!!).isWithin(1e-6).of(200.0)
        assertThat(points[1].y).isNull()
        assertThat(points[2].y!!).isWithin(1e-6).of(200.0)
    }

    @Test
    fun a_stream_shorter_than_two_samples_has_no_pace() {
        assertThat(paceSeriesFromStream(intArrayOf(0), doubleArrayOf(0.0))).isEmpty()
    }

    @Test
    fun pace_from_a_speed_channel_inverts_metres_per_second() {
        val points = paceSeriesFromSpeed(intArrayOf(0, 10), doubleArrayOf(10.0 / 3.0, 0.0))
        assertThat(points).hasSize(2)
        assertThat(points[0].y!!).isWithin(1e-6).of(300.0)
        assertThat(points[1].y).isNull()
    }

    @Test
    fun formatPaceAxis_prints_minutes_and_seconds() {
        assertThat(formatPaceAxis(300.0)).isEqualTo("5:00")
        assertThat(formatPaceAxis(365.4)).isEqualTo("6:05")
    }
}
