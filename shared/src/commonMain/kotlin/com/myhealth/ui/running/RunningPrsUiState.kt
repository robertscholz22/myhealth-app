package com.myhealth.ui.running

import com.myhealth.domain.engine.running.CanonicalDistances
import com.myhealth.domain.engine.running.RacePrediction
import com.myhealth.domain.model.RunningBest
import com.myhealth.ui.common.charts.ChartSeries
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.fmtKm
import com.myhealth.ui.common.zeroPad
import kotlin.math.abs

/** ViewModel state for [RunningPrsScreen] (PLAN §4.2 Running PRs, P5.7). */
data class RunningPrsUiState(
    val isLoading: Boolean = true,
    /** One row per canonical distance the athlete has a PR for, ascending by distance. */
    val bests: List<RunningBest> = emptyList(),
    /** Riegel projections onto every canonical distance from the best recent qualifying effort. */
    val predictions: List<RacePrediction> = emptyList(),
    val vdot: Double? = null,
    /** One line per canonical distance with two or more efforts (P8.3). */
    val progression: List<ChartSeries> = emptyList(),
    val showAddDialog: Boolean = false,
)

/** `mm:ss` under an hour, `h:mm:ss` from an hour on — a race clock never drops the hour digit
 * once it is non-zero, but never pads it in below an hour either. */
fun formatRaceTime(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "$h:${m.zeroPad(2)}:${s.zeroPad(2)}"
    } else {
        "$m:${s.zeroPad(2)}"
    }
}

/** `m:ss /km` pace label. */
fun formatPaceSecPerKm(secPerKm: Int): String {
    val clamped = secPerKm.coerceAtLeast(0)
    return "${clamped / 60}:${(clamped % 60).zeroPad(2)} /km"
}

/** The label a PR-table row or dropdown shows for one of the eight canonical distances (§3.4);
 * a non-canonical value (should not happen, but §2.1's converter rule applies) falls back to km. */
fun distanceLabel(distanceMeters: Double): String = when {
    isClose(distanceMeters, CanonicalDistances.ONE_KM) -> "1 km"
    isClose(distanceMeters, CanonicalDistances.MILE) -> "1 mile"
    isClose(distanceMeters, CanonicalDistances.THREE_KM) -> "3 km"
    isClose(distanceMeters, CanonicalDistances.FIVE_KM) -> "5 km"
    isClose(distanceMeters, CanonicalDistances.TEN_KM) -> "10 km"
    isClose(distanceMeters, CanonicalDistances.FIFTEEN_KM) -> "15 km"
    isClose(distanceMeters, CanonicalDistances.HALF_MARATHON) -> "Half marathon"
    isClose(distanceMeters, CanonicalDistances.MARATHON) -> "Marathon"
    else -> fmtKm(distanceMeters / 1000.0, 1)
}

private fun isClose(a: Double, b: Double): Boolean = abs(a - b) < 1.0

/** "VDOT 49.8" for the header stat, or a placeholder before any qualifying effort exists. */
fun vdotLabel(vdot: Double?): String = vdot?.let { "VDOT ${fmtDecimal(it, 1)}" } ?: "VDOT —"

fun roundHalfUpToInt(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()

/** The manual-PR dialog's pace preview: whole-second pace for a distance/time pair. */
fun paceSecPerKmOf(distanceMeters: Double, timeSec: Int): Int =
    roundHalfUpToInt(timeSec / (distanceMeters / 1000.0))
