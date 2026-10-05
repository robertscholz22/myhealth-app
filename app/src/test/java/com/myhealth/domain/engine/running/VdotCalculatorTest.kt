package com.myhealth.domain.engine.running

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Daniels VDOT of PLAN §3.4, including `pr11`. */
class VdotCalculatorTest {

    @Test
    fun pr11_vdot_for_5k_in_20min() {
        val vdot = VdotCalculator.vdot(distanceMeters = 5000.0, timeSec = 1200.0)!!

        assertThat(vdot).isWithin(0.3).of(49.8)
    }

    @Test
    fun percent_max_falls_with_race_duration() {
        assertThat(VdotCalculator.percentMax(20.0)).isWithin(0.0005).of(0.9530)
        assertThat(VdotCalculator.percentMax(60.0)).isLessThan(VdotCalculator.percentMax(20.0))
        assertThat(VdotCalculator.percentMax(180.0)).isGreaterThan(0.8)
    }

    @Test
    fun vo2_cost_rises_with_velocity() {
        assertThat(VdotCalculator.vo2(250.0)).isWithin(0.001).of(47.4645)
        assertThat(VdotCalculator.vo2(300.0)).isGreaterThan(VdotCalculator.vo2(250.0))
    }

    @Test
    fun a_faster_five_k_has_a_higher_vdot() {
        val slower = VdotCalculator.vdot(5000.0, 1500.0)!!
        val faster = VdotCalculator.vdot(5000.0, 1200.0)!!

        assertThat(faster).isGreaterThan(slower)
    }

    @Test
    fun degenerate_inputs_return_null() {
        assertThat(VdotCalculator.vdot(0.0, 1200.0)).isNull()
        assertThat(VdotCalculator.vdot(5000.0, 0.0)).isNull()
    }
}
