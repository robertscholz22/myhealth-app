package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `timeInZones` (PLAN P2.9): minutes per 10 % HRR band, `<60,60-70,70-80,80-90,>=90`.
 * hrRest = 50, hrMax = 190 throughout (range = 140) unless a case says otherwise.
 */
class HrZonesTest {

    @Test
    fun hrz01_all_samples_in_one_zone() {
        // hr = 155 constant -> hrr = (155-50)/140 = 0.75 -> band index 2 (70-80%).
        val offsets = intArrayOf(0, 60, 120, 180)
        val hr = listOf(155, 155, 155, 155)

        val minutes = timeInZones(offsets, hr, hrRest = 50, hrMax = 190)

        assertThat(minutes).hasSize(5)
        assertThat(minutes[2]).isWithin(1e-6).of(3.0)
        assertThat(minutes[0] + minutes[1] + minutes[3] + minutes[4]).isWithin(1e-6).of(0.0)
    }

    @Test
    fun hrz02_spread_across_all_five_zones() {
        // hr picked so each 60s interval lands in a different band, one minute each.
        val offsets = intArrayOf(0, 60, 120, 180, 240, 300)
        val hr = listOf(100, 140, 155, 170, 185, 185)

        val minutes = timeInZones(offsets, hr, hrRest = 50, hrMax = 190)

        assertThat(minutes).containsExactly(1.0, 1.0, 1.0, 1.0, 1.0).inOrder()
    }

    @Test
    fun hrz03_null_samples_are_skipped() {
        // The null at index 1 drops its interval entirely — no NaN, no contribution to any band.
        val offsets = intArrayOf(0, 60, 120, 180)
        val hr = listOf(150, null, 150, 150)

        val minutes = timeInZones(offsets, hr, hrRest = 50, hrMax = 190)

        // hr=150 -> hrr = (150-50)/140 = 0.7143 -> band 2. Only intervals [0,1) and [2,3) count.
        assertThat(minutes[2]).isWithin(1e-6).of(2.0)
        assertThat(minutes.sum()).isWithin(1e-6).of(2.0)
        minutes.forEach { assertThat(it.isNaN()).isFalse() }
    }

    @Test
    fun hrz04_sample_gap_capped_at_60_seconds() {
        // A 600s gap between two samples must contribute at most 1 minute, not 10.
        val offsets = intArrayOf(0, 600)
        val hr = listOf(150, 150)

        val minutes = timeInZones(offsets, hr, hrRest = 50, hrMax = 190)

        assertThat(minutes[2]).isWithin(1e-6).of(1.0)
        assertThat(minutes.sum()).isWithin(1e-6).of(1.0)
    }
}
