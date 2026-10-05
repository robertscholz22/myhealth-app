package com.myhealth.domain.engine.bike

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `PowerMath.normalizedPower` (PLAN P12, §3.8). The reference cases are the ones every power
 * meter vendor documents: a constant effort normalizes to itself, a variable one normalizes
 * above its average, and a series shorter than the 30-second window has no normalized power.
 */
class PowerMathTest {

    private fun oneHz(count: Int): IntArray = IntArray(count) { it }

    @Test
    fun np01_constant_200w_np_200() {
        val power = IntArray(3_600) { 200 }

        assertThat(PowerMath.normalizedPower(oneHz(3_600), power)).isEqualTo(200)
        assertThat(PowerMath.averagePower(oneHz(3_600), power)).isEqualTo(200)
    }

    @Test
    fun np02_20min_200w_then_20min_300w_np_264_pm1_avg_250() {
        val power = IntArray(2_400) { if (it < 1_200) 200 else 300 }

        val np = checkNotNull(PowerMath.normalizedPower(oneHz(2_400), power))
        // The 4th-power weighting pulls the answer well above the 250 W arithmetic mean.
        assertThat(np).isIn(263..265)
        assertThat(PowerMath.averagePower(oneHz(2_400), power)).isEqualTo(250)
    }

    @Test
    fun np03_under_30s_null() {
        // 29 seconds of data: offsets 0..29 span 29 s, one short of a full window.
        val power = IntArray(30) { 250 }

        assertThat(PowerMath.normalizedPower(oneHz(30), power)).isNull()
        // An average, unlike a normalized power, is still meaningful over 29 seconds.
        assertThat(PowerMath.averagePower(oneHz(30), power)).isEqualTo(250)
        assertThat(PowerMath.normalizedPower(intArrayOf(), intArrayOf())).isNull()
        // Mismatched channels are a programming error, not a number.
        assertThat(PowerMath.normalizedPower(intArrayOf(0, 1), intArrayOf(200))).isNull()
    }

    @Test
    fun a_sparse_5s_axis_normalizes_like_the_1hz_one() {
        val dense = IntArray(2_400) { if (it < 1_200) 200 else 300 }
        val sparseOffsets = IntArray(480) { it * 5 }
        val sparse = IntArray(480) { if (it < 240) 200 else 300 }

        val denseNp = checkNotNull(PowerMath.normalizedPower(oneHz(2_400), dense))
        val sparseNp = checkNotNull(PowerMath.normalizedPower(sparseOffsets, sparse))

        assertThat(sparseNp).isIn((denseNp - 2)..(denseNp + 2))
    }
}
