package com.myhealth.domain.engine.bike

import kotlin.math.floor
import kotlin.math.pow

/**
 * Cycling power maths (PLAN P12, §3.8). Pure Kotlin, no Android, no I/O.
 *
 * Normalized power is the standard Coggan definition:
 *
 * 1. a **30-second trailing moving average** of the power series,
 * 2. each of those averages raised to the 4th power,
 * 3. the mean of those 4th powers,
 * 4. the 4th root of that mean.
 *
 * The series this app carries is a sample **axis** (`ActivityStreams.sampleOffsetsSec`) that may
 * be irregular — Health Connect writes every 5 s, a FIT file every second, and a strap dropout
 * leaves a hole — so both the moving average and the final mean are **time-weighted** by the gap
 * each sample represents rather than counting samples. On a regular 1 Hz series that reduces
 * exactly to the textbook formula.
 *
 * A sample's weight is the gap to the **next** sample (the last sample inherits the previous gap,
 * or 1 s for a single sample), capped at [MAX_GAP_SEC] so a long pause does not let one value
 * dominate the average.
 */
object PowerMath {

    /** The rolling window of the Coggan definition. */
    const val WINDOW_SEC: Int = 30

    /** A gap longer than this is a pause, not a sample interval; it is capped, never trusted. */
    const val MAX_GAP_SEC: Int = 60

    /**
     * Normalized power in whole watts (half-up), or `null` when [offsetsSec] and [powerW] do not
     * agree in length, are empty, or cover less than [WINDOW_SEC] seconds of data — under one
     * window there is no 30-second average to take and the number would be meaningless.
     */
    fun normalizedPower(offsetsSec: IntArray, powerW: IntArray): Int? {
        val weights = weightsOf(offsetsSec, powerW) ?: return null
        if (coveredSec(offsetsSec) < WINDOW_SEC) return null

        var fourthSum = 0.0
        var weightSum = 0.0
        for (index in powerW.indices) {
            val rolling = trailingMean(offsetsSec, powerW, weights, index) ?: continue
            // A window that is not yet full (the first 30 s) is skipped, exactly as the
            // definition's "rolling average" only starts once a full window exists.
            fourthSum += rolling.pow(4) * weights[index]
            weightSum += weights[index]
        }
        if (weightSum <= 0.0) return null
        return (fourthSum / weightSum).pow(0.25).roundHalfUp()
    }

    /**
     * Time-weighted average power in whole watts (half-up), or `null` for an empty/mismatched
     * series. Unlike [normalizedPower] this has no minimum duration: an average of three samples
     * is still an average.
     */
    fun averagePower(offsetsSec: IntArray, powerW: IntArray): Int? {
        val weights = weightsOf(offsetsSec, powerW) ?: return null
        var sum = 0.0
        var weightSum = 0.0
        for (index in powerW.indices) {
            sum += powerW[index] * weights[index]
            weightSum += weights[index]
        }
        return if (weightSum <= 0.0) null else (sum / weightSum).roundHalfUp()
    }

    /** Seconds spanned by the axis, `0` for fewer than two samples. */
    private fun coveredSec(offsetsSec: IntArray): Int =
        if (offsetsSec.size < 2) 0 else offsetsSec.last() - offsetsSec.first()

    /**
     * The seconds each sample stands for: the gap to the next sample, capped at [MAX_GAP_SEC].
     * `null` when the two arrays disagree or are empty.
     */
    private fun weightsOf(offsetsSec: IntArray, powerW: IntArray): DoubleArray? {
        if (powerW.isEmpty() || offsetsSec.size != powerW.size) return null
        val weights = DoubleArray(powerW.size)
        for (index in powerW.indices) {
            val gap = if (index + 1 < offsetsSec.size) {
                offsetsSec[index + 1] - offsetsSec[index]
            } else if (index > 0) {
                offsetsSec[index] - offsetsSec[index - 1]
            } else {
                1
            }
            weights[index] = gap.coerceIn(1, MAX_GAP_SEC).toDouble()
        }
        return weights
    }

    /**
     * The time-weighted mean of the [WINDOW_SEC] seconds ending at [end], or `null` while the
     * window is not yet full.
     */
    private fun trailingMean(
        offsetsSec: IntArray,
        powerW: IntArray,
        weights: DoubleArray,
        end: Int,
    ): Double? {
        val windowStart = offsetsSec[end] - WINDOW_SEC
        if (offsetsSec[0] > windowStart) return null
        var sum = 0.0
        var weightSum = 0.0
        var index = end
        while (index >= 0 && offsetsSec[index] >= windowStart) {
            sum += powerW[index] * weights[index]
            weightSum += weights[index]
            index--
        }
        return if (weightSum <= 0.0) null else sum / weightSum
    }

    /** Half-up rounding (§3 preamble, amendment A4) — `kotlin.math.round` is half-to-even. */
    private fun Double.roundHalfUp(): Int = floor(this + 0.5).toInt()
}
