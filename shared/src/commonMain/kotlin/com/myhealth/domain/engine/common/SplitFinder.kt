package com.myhealth.domain.engine.common

/**
 * The fastest split of a given distance inside a cumulative distance/time stream (PLAN §3.4
 * method B), shared by the running and the cycling best-effort engines (P12.2).
 *
 * The scan is a two-pointer pass that interpolates **both** edges — once with the right edge
 * anchored on a sample and the left edge interpolated, once the other way round — so a 5-second
 * sampling interval does not quantise the result. O(n).
 *
 * The code below was moved **verbatim** out of `RunningBestEngine` (which now delegates to it), so
 * the running PR cases `pr01…pr12` keep their exact values.
 */
object SplitFinder {

    /**
     * Fastest time to cover [target] metres inside the stream, or `null` when the stream never
     * covers it. Both arrays must be monotone: `offsetsSec` strictly increasing, `cumulativeMeters`
     * non-decreasing (the callers clean their samples first).
     */
    fun bestSplitSec(offsetsSec: DoubleArray, cumulativeMeters: DoubleArray, target: Double): Double? {
        val n = minOf(offsetsSec.size, cumulativeMeters.size)
        if (n < 2 || target <= 0.0) return null
        if (cumulativeMeters[n - 1] - cumulativeMeters[0] < target) return null

        var best = Double.MAX_VALUE

        // Right edge anchored on sample j, left edge interpolated at distance[j] - target.
        var i = 0
        for (j in 1 until n) {
            while (i + 1 < j && cumulativeMeters[j] - cumulativeMeters[i + 1] >= target) i++
            val leftDistance = cumulativeMeters[j] - target
            if (leftDistance < cumulativeMeters[i]) continue
            val tLeft = interpolate(
                x = leftDistance,
                x0 = cumulativeMeters[i], x1 = cumulativeMeters[i + 1],
                y0 = offsetsSec[i], y1 = offsetsSec[i + 1],
            )
            best = minOf(best, offsetsSec[j] - tLeft)
        }

        // Left edge anchored on sample i, right edge interpolated at distance[i] + target.
        var k = 1
        for (left in 0 until n - 1) {
            val rightDistance = cumulativeMeters[left] + target
            if (k <= left) k = left + 1
            while (k < n && cumulativeMeters[k] < rightDistance) k++
            if (k >= n) break
            val tRight = interpolate(
                x = rightDistance,
                x0 = cumulativeMeters[k - 1], x1 = cumulativeMeters[k],
                y0 = offsetsSec[k - 1], y1 = offsetsSec[k],
            )
            best = minOf(best, tRight - offsetsSec[left])
        }

        return best.takeIf { it > 0.0 && it < Double.MAX_VALUE }
    }

    /** Linear interpolation of `y` at [x], clamped to the `[x0, x1]` segment. */
    fun interpolate(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Double {
        if (x1 <= x0) return y0
        val fraction = ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        return y0 + fraction * (y1 - y0)
    }
}
