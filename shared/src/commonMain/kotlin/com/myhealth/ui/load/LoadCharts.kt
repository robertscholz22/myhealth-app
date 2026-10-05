package com.myhealth.ui.load

import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.charts.ChartPoint
import com.myhealth.ui.common.charts.dailySeries
import com.myhealth.ui.common.usText

private const val DAY_LABEL = "d MMM"

/** ACWR zone boundaries (§3.2.3) reused as the shaded band edges of the ACWR chart (P8.3). */
const val ACWR_OPTIMAL_MIN: Double = 0.8
const val ACWR_OPTIMAL_MAX: Double = 1.3
const val ACWR_CAUTION_MAX: Double = 1.5

/** The x domain of every Load chart: `[first cached day, last cached day]`. Empty → `null`. */
fun loadDayBounds(series: List<DailyLoad>): Pair<Long, Long>? {
    if (series.isEmpty()) return null
    val days = series.map { it.day }
    return days.min() to days.max()
}

/** ATL (acute) and CTL (chronic) as one point per day, `x` = epoch day, gaps preserved. */
fun atlPoints(series: List<DailyLoad>): List<ChartPoint> = pointsOf(series) { it.atl }

fun ctlPoints(series: List<DailyLoad>): List<ChartPoint> = pointsOf(series) { it.ctl }

/** ACWR is `null` until there is enough chronic load (§3.2.3), so the line legitimately breaks. */
fun acwrPoints(series: List<DailyLoad>): List<ChartPoint> = pointsOf(series) { it.acwr }

fun recoveryPoints(series: List<DailyLoad>): List<ChartPoint> =
    pointsOf(series) { it.recoveryScore?.toDouble() }

private fun pointsOf(series: List<DailyLoad>, valueOf: (DailyLoad) -> Double?): List<ChartPoint> {
    val bounds = loadDayBounds(series) ?: return emptyList()
    val byDay = series.associateBy { it.day }
    return dailySeries(bounds.first, bounds.second) { day -> byDay[day]?.let(valueOf)?.takeIf { it.isFinite() } }
}

/** One bar per day in the cached window; a day with no session is a real zero, not a gap. */
fun trimpBars(series: List<DailyLoad>): List<Double> {
    val bounds = loadDayBounds(series) ?: return emptyList()
    val byDay = series.associateBy { it.day }
    return (bounds.first..bounds.second).map { day -> byDay[day]?.trimp ?: 0.0 }
}

/** First / middle / last date labels for the shared epoch-day x axis. */
fun loadAxisLabels(series: List<DailyLoad>): List<String> {
    val (from, to) = loadDayBounds(series) ?: return emptyList()
    val middle = from + (to - from) / 2
    return listOf(from, middle, to).distinct().map { it.epochDayDate().usText(DAY_LABEL) }
}
