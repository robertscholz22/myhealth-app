package com.myhealth.ui.common.charts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for the derived-line maths behind the Body weight chart (PLAN P8.3). */
class MovingAverageTest {

    @Test
    fun window_of_one_returns_the_input_unchanged() {
        val values = listOf(80.0, 81.0, null, 79.5)
        assertThat(movingAverage(values, 1)).containsExactly(80.0, 81.0, null, 79.5).inOrder()
    }

    @Test
    fun window_larger_than_the_series_degrades_to_a_prefix_average() {
        val result = movingAverage(listOf(10.0, 20.0, 30.0), 10)
        assertThat(result[0]!!).isWithin(1e-9).of(10.0)
        assertThat(result[1]!!).isWithin(1e-9).of(15.0)
        assertThat(result[2]!!).isWithin(1e-9).of(20.0)
    }

    @Test
    fun nulls_are_skipped_not_counted() {
        // Window [null, 4.0] averages the single real value, not 4.0 / 2.
        val result = movingAverage(listOf(null, 4.0, null), 2)
        assertThat(result[0]).isNull()
        assertThat(result[1]!!).isWithin(1e-9).of(4.0)
        assertThat(result[2]!!).isWithin(1e-9).of(4.0)
    }

    @Test
    fun exact_values_for_a_three_day_window() {
        val result = movingAverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertThat(result[0]!!).isWithin(1e-9).of(1.0)
        assertThat(result[1]!!).isWithin(1e-9).of(1.5)
        assertThat(result[2]!!).isWithin(1e-9).of(2.0)
        assertThat(result[3]!!).isWithin(1e-9).of(3.0)
        assertThat(result[4]!!).isWithin(1e-9).of(4.0)
    }

    @Test
    fun dropGaps_keeps_only_the_sampled_points() {
        val points = listOf(ChartPoint(1.0, 10.0), ChartPoint(2.0, null), ChartPoint(3.0, 30.0))
        assertThat(points.dropGaps().map { it.x }).containsExactly(1.0, 3.0).inOrder()
    }

    @Test
    fun downsample_keeps_the_first_and_last_sample() {
        val points = (0..999).map { ChartPoint(it.toDouble(), it.toDouble()) }
        val reduced = downsample(points, maxPoints = 100)
        assertThat(reduced.size).isAtMost(101)
        assertThat(reduced.first().x).isEqualTo(0.0)
        assertThat(reduced.last().x).isEqualTo(999.0)
    }

    @Test
    fun invertY_mirrors_values_and_preserves_gaps() {
        val points = listOf(ChartPoint(0.0, 300.0), ChartPoint(1.0, null))
        assertThat(points.invertY()[0].y!!).isWithin(1e-9).of(-300.0)
        assertThat(points.invertY()[1].y).isNull()
    }

    @Test
    fun dailySeries_spans_every_day_and_leaves_gaps() {
        val byDay = mapOf(2L to 5.0)
        val series = dailySeries(1L, 3L) { byDay[it] }
        assertThat(series.map { it.x }).containsExactly(1.0, 2.0, 3.0).inOrder()
        assertThat(series.map { it.y }).containsExactly(null, 5.0, null).inOrder()
    }
}
