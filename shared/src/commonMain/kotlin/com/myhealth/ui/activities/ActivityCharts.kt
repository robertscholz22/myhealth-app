package com.myhealth.ui.activities

import com.myhealth.domain.model.ActivityStreams
import com.myhealth.ui.common.charts.ChartPoint
import com.myhealth.ui.common.charts.downsample
import com.myhealth.ui.common.zeroPad

/** Seconds of elapsed time a pace sample is averaged over — raw 1 Hz pace is unreadable noise. */
const val PACE_SPAN_SEC: Int = 10

/** Pace outside `[2:00, 20:00] /km` is a GPS artefact or a standing stop, not a pace worth drawing. */
private const val PACE_MIN_SEC_PER_KM = 120.0
private const val PACE_MAX_SEC_PER_KM = 1200.0

/** Heart rate against elapsed minutes; a dropped sample stays `null` so the line breaks. */
fun hrPoints(streams: ActivityStreams?): List<ChartPoint> {
    if (streams == null) return emptyList()
    val points = streams.sampleOffsetsSec.mapIndexed { index, offset ->
        ChartPoint(offset / 60.0, streams.hr.getOrNull(index)?.toDouble())
    }
    return downsample(points)
}

/**
 * Pace in **seconds per kilometre** against elapsed minutes, differentiated over a window of at
 * least [minSpanSec] seconds. A sample where the athlete did not move (or the distance channel
 * jumped) becomes a gap rather than an infinite pace. Pure — unit-tested in `PaceSeriesTest`.
 */
fun paceSeriesFromStream(
    offsetsSec: IntArray,
    distanceMeters: DoubleArray,
    minSpanSec: Int = PACE_SPAN_SEC,
): List<ChartPoint> {
    val n = minOf(offsetsSec.size, distanceMeters.size)
    if (n < 2) return emptyList()
    val points = ArrayList<ChartPoint>(n - 1)
    var back = 0
    for (i in 1 until n) {
        while (back + 1 < i && offsetsSec[i] - offsetsSec[back + 1] >= minSpanSec) back++
        val dt = (offsetsSec[i] - offsetsSec[back]).toDouble()
        val dd = distanceMeters[i] - distanceMeters[back]
        val pace = if (dt > 0.0 && dd > 0.0) dt / (dd / 1000.0) else null
        val usable = pace?.takeIf { it in PACE_MIN_SEC_PER_KM..PACE_MAX_SEC_PER_KM }
        points += ChartPoint(offsetsSec[i] / 60.0, usable)
    }
    return downsample(points)
}

/**
 * Pace in seconds per kilometre derived from a **speed** channel — Health Connect exercise
 * sessions carry `speedMps` but no cumulative distance, so a run imported from HC would otherwise
 * fall back to the generic speed chart.
 */
fun paceSeriesFromSpeed(offsetsSec: IntArray, speedMps: DoubleArray): List<ChartPoint> {
    val n = minOf(offsetsSec.size, speedMps.size)
    if (n == 0) return emptyList()
    val points = (0 until n).map { i ->
        val mps = speedMps[i]
        val pace = if (mps.isFinite() && mps > 0.0) 1000.0 / mps else null
        ChartPoint(offsetsSec[i] / 60.0, pace?.takeIf { it in PACE_MIN_SEC_PER_KM..PACE_MAX_SEC_PER_KM })
    }
    return downsample(points)
}

/** Speed in km/h against elapsed minutes — the non-running counterpart of the pace chart. */
fun speedPoints(streams: ActivityStreams?): List<ChartPoint> {
    val speeds = streams?.speedMps ?: return emptyList()
    val points = streams.sampleOffsetsSec.mapIndexed { index, offset ->
        ChartPoint(offset / 60.0, speeds.getOrNull(index)?.takeIf { it.isFinite() && it >= 0.0 }?.times(3.6))
    }
    return downsample(points)
}

/** Power in watts against elapsed minutes (P12.4); empty when the ride carries no power stream. */
fun powerPoints(streams: ActivityStreams?): List<ChartPoint> {
    val power = streams?.powerW ?: return emptyList()
    val points = streams.sampleOffsetsSec.mapIndexed { index, offset ->
        ChartPoint(offset / 60.0, power.getOrNull(index)?.toDouble())
    }
    return downsample(points)
}

/** Altitude in metres against elapsed minutes; empty when the activity carries no barometer data. */
fun altitudePoints(streams: ActivityStreams?): List<ChartPoint> {
    val altitude = streams?.altitudeM ?: return emptyList()
    val points = streams.sampleOffsetsSec.mapIndexed { index, offset ->
        ChartPoint(offset / 60.0, altitude.getOrNull(index)?.takeIf { it.isFinite() })
    }
    return downsample(points)
}

/** `0:00 / 14:30 / 29:00` style labels for an elapsed-minutes x axis. */
fun minuteAxisLabels(streams: ActivityStreams?): List<String> {
    val offsets = streams?.sampleOffsetsSec ?: return emptyList()
    if (offsets.isEmpty()) return emptyList()
    val last = offsets.last()
    return listOf(offsets.first(), offsets.first() + (last - offsets.first()) / 2, last)
        .distinct()
        .map { formatElapsed(it) }
}

/** `m:ss` for a pace label, used by the inverted pace axis. */
fun formatPaceAxis(secPerKm: Double): String {
    val total = kotlin.math.floor(secPerKm + 0.5).toInt().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).zeroPad(2)}"
}

private fun formatElapsed(offsetSec: Int): String =
    "${offsetSec / 60}:${(offsetSec % 60).zeroPad(2)}"
