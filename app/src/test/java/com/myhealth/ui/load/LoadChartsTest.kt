package com.myhealth.ui.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.DailyLoad
import org.junit.Test

/** Unit tests for the Load screen's daily-load to chart mappers (PLAN P8.3). */
class LoadChartsTest {

    private fun load(day: Long, trimp: Double, atl: Double, ctl: Double, acwr: Double?, recovery: Int?) =
        DailyLoad(
            day = day,
            trimp = trimp,
            sessionCount = if (trimp > 0) 1 else 0,
            atl = atl,
            ctl = ctl,
            acwr = acwr,
            tsb = ctl - atl,
            monotony = null,
            strain = null,
            recoveryScore = recovery,
            recoveryBand = null,
            recoveryConfidence = 1.0,
            flags = emptyList(),
            computedAtMillis = 0,
        )

    private val series = listOf(
        load(10, 60.0, 40.0, 30.0, null, 70),
        load(12, 0.0, 35.0, 31.0, 1.1, 80),
    )

    @Test
    fun loadDayBounds_spans_the_cached_days() {
        assertThat(loadDayBounds(series)).isEqualTo(10L to 12L)
        assertThat(loadDayBounds(emptyList())).isNull()
    }

    @Test
    fun atl_and_ctl_points_fill_the_missing_day_with_a_gap() {
        val atl = atlPoints(series)
        assertThat(atl.map { it.x }).containsExactly(10.0, 11.0, 12.0).inOrder()
        assertThat(atl[0].y!!).isWithin(1e-9).of(40.0)
        assertThat(atl[1].y).isNull()
        assertThat(ctlPoints(series)[2].y!!).isWithin(1e-9).of(31.0)
    }

    @Test
    fun acwrPoints_leaves_a_gap_while_the_ratio_is_undefined() {
        val acwr = acwrPoints(series)
        assertThat(acwr[0].y).isNull()
        assertThat(acwr[2].y!!).isWithin(1e-9).of(1.1)
    }

    @Test
    fun trimpBars_uses_zero_rather_than_a_gap_for_days_without_training() {
        assertThat(trimpBars(series)).containsExactly(60.0, 0.0, 0.0).inOrder()
    }

    @Test
    fun recoveryPoints_converts_the_cached_score() {
        assertThat(recoveryPoints(series)[0].y!!).isWithin(1e-9).of(70.0)
        assertThat(recoveryPoints(series)[2].y!!).isWithin(1e-9).of(80.0)
    }

    @Test
    fun loadAxisLabels_is_empty_without_data() {
        assertThat(loadAxisLabels(emptyList())).isEmpty()
        assertThat(loadAxisLabels(series)).hasSize(3)
    }
}
