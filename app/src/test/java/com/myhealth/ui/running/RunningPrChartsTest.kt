package com.myhealth.ui.running

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.running.CanonicalDistances
import com.myhealth.domain.model.RunningBest
import org.junit.Test

/** Unit tests for the PR progression chart's mapper (PLAN P8.3). */
class RunningPrChartsTest {

    private fun best(distance: Double, timeSec: Int, day: Long) = RunningBest(
        id = day,
        distanceMeters = distance,
        timeSec = timeSec,
        activityId = null,
        day = day,
        method = "FULL_ACTIVITY",
        isEstimated = false,
        paceSecPerKm = paceSecPerKmOf(distance, timeSec),
        createdAtMillis = 0,
    )

    @Test
    fun a_distance_with_a_single_effort_gets_no_line() {
        val series = prProgressionSeries(listOf(best(CanonicalDistances.FIVE_KM, 1500, 20000)))
        assertThat(series).isEmpty()
    }

    @Test
    fun each_qualifying_distance_becomes_one_series_sorted_by_date() {
        val series = prProgressionSeries(
            listOf(
                best(CanonicalDistances.TEN_KM, 3100, 20010),
                best(CanonicalDistances.FIVE_KM, 1500, 20005),
                best(CanonicalDistances.FIVE_KM, 1470, 19990),
                best(CanonicalDistances.TEN_KM, 3050, 20050),
            ),
        )
        assertThat(series.map { it.name }).containsExactly("5 km", "10 km").inOrder()
        assertThat(series[0].points.map { it.x }).containsExactly(19990.0, 20005.0).inOrder()
        assertThat(series[0].points.map { it.y }).containsExactly(1470.0, 1500.0).inOrder()
        assertThat(series[1].points).hasSize(2)
    }

    @Test
    fun prAxisLabels_covers_the_full_span_of_the_plotted_efforts() {
        val series = prProgressionSeries(
            listOf(best(CanonicalDistances.FIVE_KM, 1500, 19723), best(CanonicalDistances.FIVE_KM, 1480, 19813)),
        )
        assertThat(prAxisLabels(series)).containsExactly("Jan 2024", "Feb 2024", "Mar 2024").inOrder()
    }

    @Test
    fun prAxisLabels_is_empty_without_a_series() {
        assertThat(prAxisLabels(emptyList())).isEmpty()
    }
}
