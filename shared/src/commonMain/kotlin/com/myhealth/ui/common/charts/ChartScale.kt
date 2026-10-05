package com.myhealth.ui.common.charts

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A y-axis domain rounded outwards to "nice" tick values (PLAN P8.2, amendment A2 — the charts are
 * hand-drawn, so the scaling has to live somewhere unit-testable).
 *
 * [min] and [max] are always `ticks.first()` and `ticks.last()`, so a caller can map a value to a
 * pixel with [ChartScale.fraction] without re-deriving the padded bounds.
 */
data class NiceRange(val min: Double, val max: Double, val ticks: List<Double>)

/**
 * Axis maths for `ui/common/charts` — pure Kotlin (no Compose, no Android) so `ChartScaleTest`
 * can assert exact tick lists.
 */
object ChartScale {

    /** Allowed tick steps, as the mantissa of `mantissa × 10^n` (PLAN P8.2: "1/2/5 × 10^n"). */
    private val MANTISSAS = doubleArrayOf(1.0, 2.0, 5.0)

    /**
     * Tick values covering `[min, max]`, spaced by the smallest `1/2/5 × 10^n` step that needs at
     * most [maxTicks] ticks. The bounds are **included**: the first tick is `<= min` and the last
     * is `>= max`, so the data always fits inside the drawn axis.
     *
     * Degenerate inputs are handled rather than thrown on: a reversed range is swapped, a single
     * value is padded symmetrically, and non-finite input falls back to `[0, 1]`.
     */
    fun niceTicks(min: Double, max: Double, maxTicks: Int = 5): List<Double> {
        if (!min.isFinite() || !max.isFinite()) return listOf(0.0, 1.0)
        val ticks = maxTicks.coerceAtLeast(2)
        var lo = kotlin.math.min(min, max)
        var hi = kotlin.math.max(min, max)
        if (hi - lo <= 0.0) {
            val pad = if (lo == 0.0) 1.0 else abs(lo) * 0.05
            lo -= pad
            hi += pad
        }
        val step = stepFor(lo, hi, ticks)
        val first = floor(lo / step)
        val last = ceil(hi / step)
        val count = (last - first).toInt()
        return (0..count).map { i -> roundToStep((first + i) * step, step) }
    }

    /** [niceTicks] plus the padded bounds it implies. */
    fun niceRange(min: Double, max: Double, maxTicks: Int = 5): NiceRange {
        val ticks = niceTicks(min, max, maxTicks)
        return NiceRange(min = ticks.first(), max = ticks.last(), ticks = ticks)
    }

    /** Where [value] sits in [range]: `0f` at [NiceRange.min], `1f` at [NiceRange.max]. */
    fun fraction(value: Double, range: NiceRange): Float {
        val span = range.max - range.min
        if (span <= 0.0) return 0.5f
        return ((value - range.min) / span).toFloat()
    }

    /**
     * Pixel y of [value] inside a plot of [heightPx] whose top edge is `0f` — the y axis grows
     * upwards, the canvas downwards, so this is the inverted [fraction].
     */
    fun toY(value: Double, range: NiceRange, heightPx: Float): Float =
        heightPx - fraction(value, range) * heightPx

    /** Pixel x of [value] between [min] and [max] across a plot of [widthPx]. */
    fun toX(value: Double, min: Double, max: Double, widthPx: Float): Float {
        val span = max - min
        if (span <= 0.0) return widthPx / 2f
        return (((value - min) / span) * widthPx).toFloat()
    }

    /** Smallest `1/2/5 × 10^n` step that covers `[lo, hi]` in at most `maxTicks` ticks. */
    private fun stepFor(lo: Double, hi: Double, maxTicks: Int): Double {
        val span = hi - lo
        val rough = span / (maxTicks - 1)
        var exponent = floor(log10(rough)).toInt()
        // log10 of a tiny span can land an exponent low enough that no mantissa fits; walk up.
        repeat(MANTISSAS.size * 4) {
            for (mantissa in MANTISSAS) {
                val step = mantissa * 10.0.pow(exponent)
                if (step <= 0.0) continue
                val intervals = ceil(hi / step) - floor(lo / step)
                if (intervals <= maxTicks - 1) return step
            }
            exponent++
        }
        return span
    }

    /**
     * `floor(x / step) * step` accumulates binary error (0.30000000000000004); snap the tick back
     * onto the step grid so `yFormatter` never prints a 17-digit artefact.
     */
    private fun roundToStep(value: Double, step: Double): Double {
        val decimals = decimalsFor(step)
        val factor = 10.0.pow(decimals)
        return floor(value * factor + 0.5) / factor
    }

    /** How many decimals a [step] needs — also useful to callers writing a `yFormatter`. */
    fun decimalsFor(step: Double): Int {
        if (step <= 0.0 || !step.isFinite()) return 0
        val exponent = floor(log10(step)).toInt()
        return if (exponent >= 0) 0 else (-exponent + 1).coerceAtMost(8)
    }
}
