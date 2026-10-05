package com.myhealth.domain.engine.running

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Daniels' training paces from the §3.4 VDOT polynomial (PLAN §3.10.1) — `vd01`…`vd06`.
 *
 * The reference column is VDOT 50: E 334 · M 268 · T 255 · I 234 · R 220 s/km, each asserted with
 * the plan's ±1 s tolerance (the exact repetition pace is 219.47 s/km, i.e. 219 when rounded).
 */
class DanielsPacesTest {

    private fun pace(pace: DanielsPace, vdot: Double = 50.0): Double =
        DanielsPaces.secPerKmExact(vdot, pace)!!

    @Test
    fun vd01_vdot50_easy_334() {
        assertThat(pace(DanielsPace.EASY)).isWithin(1.0).of(334.0)
        assertThat(DanielsPaces.secPerKm(50.0, DanielsPace.EASY)).isEqualTo(334)
    }

    @Test
    fun vd02_vdot50_marathon_268() {
        assertThat(pace(DanielsPace.MARATHON)).isWithin(1.0).of(268.0)
        assertThat(DanielsPaces.secPerKm(50.0, DanielsPace.MARATHON)).isEqualTo(268)
    }

    @Test
    fun vd03_vdot50_threshold_255() {
        assertThat(pace(DanielsPace.THRESHOLD)).isWithin(1.0).of(255.0)
        assertThat(DanielsPaces.secPerKm(50.0, DanielsPace.THRESHOLD)).isEqualTo(255)
    }

    @Test
    fun vd04_vdot50_interval_234() {
        assertThat(pace(DanielsPace.INTERVAL)).isWithin(1.0).of(234.0)
        assertThat(DanielsPaces.secPerKm(50.0, DanielsPace.INTERVAL)).isEqualTo(234)
    }

    @Test
    fun vd05_vdot50_repetition_220() {
        // Exactly 219.47 s/km — inside the plan's ±1 s, but it rounds to 219, not 220.
        assertThat(pace(DanielsPace.REPETITION)).isWithin(1.0).of(220.0)
        assertThat(DanielsPaces.secPerKm(50.0, DanielsPace.REPETITION)).isEqualTo(219)
    }

    @Test
    fun vd06_paces_are_strictly_monotone() {
        var vdot = 35.0
        while (vdot <= 75.0) {
            val table = DanielsPace.entries.map { pace(it, vdot) }
            assertThat(table.zipWithNext().all { (slower, faster) -> slower > faster }).isTrue()
            vdot += 0.5
        }
    }

    @Test
    fun velocity_for_inverts_the_vo2_polynomial() {
        val velocity = VdotCalculator.velocityFor(vdot = 50.0, pct = 0.88)!!

        assertThat(VdotCalculator.vo2(velocity)).isWithin(1e-9).of(44.0)
        assertThat(VdotCalculator.velocityFor(0.0, 0.88)).isNull()
        assertThat(VdotCalculator.velocityFor(50.0, 0.0)).isNull()
    }

    @Test
    fun table_covers_all_five_paces_and_is_empty_for_a_degenerate_vdot() {
        assertThat(DanielsPaces.table(50.0).keys).containsExactlyElementsIn(DanielsPace.entries)
        assertThat(DanielsPaces.table(0.0)).isEmpty()
    }

    @Test
    fun a_fitter_athlete_runs_every_pace_faster() {
        DanielsPace.entries.forEach { named ->
            assertThat(pace(named, vdot = 60.0)).isLessThan(pace(named, vdot = 50.0))
        }
    }
}
