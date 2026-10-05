package com.myhealth.ui.common.charts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the hand-drawn charts' axis maths (PLAN P8.2): `niceTicks` must use 1/2/5 x 10^n
 * steps, must include the data bounds, and must never return more than `maxTicks` values.
 */
class ChartScaleTest {

    @Test
    fun niceTicks_zero_to_one() {
        assertThat(ChartScale.niceTicks(0.0, 1.0)).containsExactly(0.0, 0.5, 1.0).inOrder()
    }

    @Test
    fun niceTicks_zero_to_ninetySeven_rounds_up_to_a_hundred() {
        assertThat(ChartScale.niceTicks(0.0, 97.0)).containsExactly(0.0, 50.0, 100.0).inOrder()
    }

    @Test
    fun niceTicks_symmetric_negative_range() {
        assertThat(ChartScale.niceTicks(-3.0, 3.0)).containsExactly(-4.0, -2.0, 0.0, 2.0, 4.0).inOrder()
    }

    @Test
    fun niceTicks_narrow_offset_range_does_not_start_at_zero() {
        assertThat(ChartScale.niceTicks(1200.0, 1300.0)).containsExactly(1200.0, 1250.0, 1300.0).inOrder()
    }

    @Test
    fun niceTicks_single_value_is_padded_into_a_real_range() {
        val ticks = ChartScale.niceTicks(72.5, 72.5)
        assertThat(ticks.first()).isLessThan(72.5)
        assertThat(ticks.last()).isGreaterThan(72.5)
        assertThat(ticks).containsExactly(65.0, 70.0, 75.0, 80.0).inOrder()
    }

    @Test
    fun niceTicks_never_exceeds_maxTicks() {
        listOf(0.0 to 1.0, 0.0 to 97.0, -3.0 to 3.0, 1200.0 to 1300.0, 0.0 to 0.0).forEach { (lo, hi) ->
            assertThat(ChartScale.niceTicks(lo, hi).size).isAtMost(5)
        }
    }

    @Test
    fun niceTicks_includes_both_bounds() {
        val ticks = ChartScale.niceTicks(37.4, 88.1)
        assertThat(ticks.first()).isAtMost(37.4)
        assertThat(ticks.last()).isAtLeast(88.1)
    }

    @Test
    fun niceTicks_falls_back_for_non_finite_input() {
        assertThat(ChartScale.niceTicks(Double.NaN, 4.0)).containsExactly(0.0, 1.0).inOrder()
    }

    @Test
    fun niceRange_bounds_match_the_outer_ticks() {
        val range = ChartScale.niceRange(0.0, 97.0)
        assertThat(range.min).isEqualTo(0.0)
        assertThat(range.max).isEqualTo(100.0)
        assertThat(range.ticks).hasSize(3)
    }

    @Test
    fun fraction_and_toY_are_inverted_views_of_the_same_position() {
        val range = ChartScale.niceRange(0.0, 100.0)
        assertThat(ChartScale.fraction(50.0, range)).isWithin(1e-6f).of(0.5f)
        assertThat(ChartScale.toY(100.0, range, 200f)).isWithin(1e-6f).of(0f)
        assertThat(ChartScale.toY(0.0, range, 200f)).isWithin(1e-6f).of(200f)
    }

    @Test
    fun toX_maps_the_domain_across_the_plot_width() {
        assertThat(ChartScale.toX(5.0, 0.0, 10.0, 300f)).isWithin(1e-6f).of(150f)
        assertThat(ChartScale.toX(7.0, 7.0, 7.0, 300f)).isWithin(1e-6f).of(150f)
    }
}
