package com.myhealth.ui.common.charts

/**
 * Pure series maths shared by the chart call sites (PLAN P8.3) — no Compose, no Android, so the
 * derived lines the screens draw are unit-testable (`MovingAverageTest`).
 */

/**
 * Trailing moving average over the last [window] samples, **skipping** `null`s: element `i` is the
 * mean of the non-null values in `[i - window + 1, i]`, or `null` when that slice holds none.
 *
 * - `window = 1` returns the input unchanged (each value is its own average).
 * - `window > values.size` degrades to a growing prefix average rather than dropping everything,
 *   which is what a 7-day weight average should do in its first week.
 */
fun movingAverage(values: List<Double?>, window: Int): List<Double?> {
    val span = window.coerceAtLeast(1)
    return values.indices.map { index ->
        val from = (index - span + 1).coerceAtLeast(0)
        val slice = values.subList(from, index + 1).filterNotNull().filter { it.isFinite() }
        if (slice.isEmpty()) null else slice.sum() / slice.size
    }
}

/**
 * Turns a day-keyed map into an evenly spaced series over `[fromDay, toDay]`, one point per day,
 * with missing days left as gaps. `x` is the epoch day so every series on a screen shares one
 * x domain even when their coverage differs.
 */
fun dailySeries(fromDay: Long, toDay: Long, valueOf: (Long) -> Double?): List<ChartPoint> =
    (fromDay..toDay).map { day -> ChartPoint(day.toDouble(), valueOf(day)) }

/**
 * Mirrors a series about zero so that "lower is better" reads as "higher on the chart" — the pace
 * axis of a run (PLAN P8.3). The caller's `yFormatter` negates again before formatting.
 */
fun List<ChartPoint>.invertY(): List<ChartPoint> = map { ChartPoint(it.x, it.y?.let { y -> -y }) }

/**
 * Keeps at most [maxPoints] evenly spaced samples. A one-second activity stream can hold thousands
 * of points; past a few hundred they are sub-pixel and only cost time to draw.
 */
fun downsample(points: List<ChartPoint>, maxPoints: Int = 400): List<ChartPoint> {
    if (maxPoints < 2 || points.size <= maxPoints) return points
    val stride = (points.size + maxPoints - 1) / maxPoints
    val kept = points.filterIndexed { index, _ -> index % stride == 0 }
    return if (kept.last() === points.last()) kept else kept + points.last()
}

/**
 * Drops the gap points, joining the remaining samples with a straight line. Use it for quantities
 * that are *sampled* irregularly but vary continuously (weight, body fat) — unlike a sensor stream,
 * a day without a reading there means "not weighed", not "nothing happened".
 */
fun List<ChartPoint>.dropGaps(): List<ChartPoint> = filter { it.y != null }
