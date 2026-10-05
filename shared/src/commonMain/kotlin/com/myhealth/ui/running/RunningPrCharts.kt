package com.myhealth.ui.running

import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.charts.ChartPoint
import com.myhealth.ui.common.charts.ChartSeries
import com.myhealth.ui.common.usText

private const val DAY_LABEL = "MMM yyyy"

/** A single effort is a dot, not a progression — a distance needs this many to earn a line. */
const val MIN_EFFORTS_FOR_PROGRESSION: Int = 2

/**
 * PR progression (PLAN P8.3): one line per canonical distance that has at least
 * [MIN_EFFORTS_FOR_PROGRESSION] efforts, plotting finishing time (y, seconds) against the day the
 * effort was run (x, epoch day). Distances are ordered shortest first so the legend matches the PR
 * table above it. Pure — unit-tested in `RunningPrChartsTest`.
 */
fun prProgressionSeries(efforts: List<RunningBest>): List<ChartSeries> =
    efforts
        .groupBy { it.distanceMeters }
        .filterValues { it.size >= MIN_EFFORTS_FOR_PROGRESSION }
        .entries
        .sortedBy { it.key }
        .map { (distance, rows) ->
            ChartSeries(
                name = distanceLabel(distance),
                points = rows.sortedBy { it.day }
                    .map { ChartPoint(it.day.toDouble(), it.timeSec.toDouble()) },
            )
        }

/** First / middle / last month labels across every plotted effort. */
fun prAxisLabels(series: List<ChartSeries>): List<String> {
    val days = series.flatMap { s -> s.points.map { it.x } }
    if (days.isEmpty()) return emptyList()
    val from = days.min().toLong()
    val to = days.max().toLong()
    val middle = from + (to - from) / 2
    return listOf(from, middle, to).distinct().map { it.epochDayDate().usText(DAY_LABEL) }
}
