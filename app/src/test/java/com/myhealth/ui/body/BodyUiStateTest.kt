package com.myhealth.ui.body

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import org.junit.Test
import kotlinx.datetime.LocalDate

/** Unit tests for the pure goal-delta and sorting/filtering helpers behind [BodyScreen] (PLAN P1.11). */
class BodyUiStateTest {

    private fun measurement(id: Long, measuredAtMillis: Long, day: Long, weightKg: Double? = 80.0) = BodyMeasurement(
        id = id,
        measuredAtMillis = measuredAtMillis,
        day = day,
        weightKg = weightKg,
        bodyFatPercent = null,
        muscleMassKg = null,
        boneMassKg = null,
        bodyWaterPercent = null,
        source = ActivitySource.MANUAL,
    )

    @Test
    fun weightDeltaToGoalKg_is_null_when_latest_weight_is_missing() {
        assertThat(weightDeltaToGoalKg(null, 75.0)).isNull()
    }

    @Test
    fun weightDeltaToGoalKg_is_null_when_goal_is_missing() {
        assertThat(weightDeltaToGoalKg(80.0, null)).isNull()
    }

    @Test
    fun weightDeltaToGoalKg_is_positive_when_above_goal() {
        assertThat(weightDeltaToGoalKg(80.0, 75.0)).isWithin(1e-9).of(5.0)
    }

    @Test
    fun weightDeltaToGoalKg_is_negative_when_below_goal() {
        assertThat(weightDeltaToGoalKg(70.0, 75.0)).isWithin(1e-9).of(-5.0)
    }

    @Test
    fun weightDeltaToGoalKg_is_zero_at_goal() {
        assertThat(weightDeltaToGoalKg(75.0, 75.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun sortedByRecencyDescending_orders_most_recent_first() {
        val oldest = measurement(id = 1, measuredAtMillis = 100, day = 19900)
        val middle = measurement(id = 2, measuredAtMillis = 200, day = 19901)
        val newest = measurement(id = 3, measuredAtMillis = 300, day = 19902)

        val sorted = listOf(middle, oldest, newest).sortedByRecencyDescending()

        assertThat(sorted.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun withinLastDays_keeps_only_measurements_in_the_window() {
        val today = LocalDate(2026, 9, 12)
        val todayEpochDay = today.toEpochDays()
        val inWindow = measurement(id = 1, measuredAtMillis = 0, day = todayEpochDay - 89)
        val onBoundary = measurement(id = 2, measuredAtMillis = 0, day = todayEpochDay - 90)
        val today_ = measurement(id = 3, measuredAtMillis = 0, day = todayEpochDay)

        val result = listOf(inWindow, onBoundary, today_).withinLastDays(90, today)

        assertThat(result.map { it.id }).containsExactly(1L, 3L)
    }

    @Test
    fun withinLastDays_excludes_measurements_before_the_window() {
        val today = LocalDate(2026, 9, 12)
        val todayEpochDay = today.toEpochDays()
        val tooOld = measurement(id = 1, measuredAtMillis = 0, day = todayEpochDay - 91)

        val result = listOf(tooOld).withinLastDays(90, today)

        assertThat(result).isEmpty()
    }

    @Test
    fun bodyUiState_latest_is_the_first_sorted_measurement() {
        val state = BodyUiState(
            measurements = listOf(
                measurement(id = 2, measuredAtMillis = 200, day = 19901, weightKg = 79.0),
                measurement(id = 1, measuredAtMillis = 100, day = 19900, weightKg = 81.0),
            ),
        )

        assertThat(state.latest?.id).isEqualTo(2L)
    }

    @Test
    fun bodyUiState_deltaToGoalKg_uses_the_latest_measurement() {
        val state = BodyUiState(
            goalWeightKg = 75.0,
            measurements = listOf(measurement(id = 1, measuredAtMillis = 100, day = 19900, weightKg = 80.0)),
        )

        assertThat(state.deltaToGoalKg).isWithin(1e-9).of(5.0)
    }
}
