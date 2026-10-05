package com.myhealth.ui.body

import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.charts.ChartPoint
import com.myhealth.ui.common.charts.dailySeries
import com.myhealth.ui.common.charts.movingAverage
import com.myhealth.ui.common.usText

/** The smoothing window of the dashed weight trend line (PLAN P8.3). */
const val WEIGHT_AVERAGE_WINDOW: Int = 7

/** Nights shown by the sleep-duration bars. */
const val SLEEP_BAR_NIGHTS: Int = 14

private const val DAY_LABEL = "d MMM"
private const val NIGHT_LABEL = "d/M"

/**
 * One point per day in `[fromDay, toDay]`, `x` = epoch day. Days with several readings average
 * them; days with none stay `null` so the line breaks instead of inventing a trend. Pure —
 * unit-tested in `BodyChartsTest`.
 */
fun weightPoints(measurements: List<BodyMeasurement>, fromDay: Long, toDay: Long): List<ChartPoint> =
    dayAveraged(measurements, fromDay, toDay) { it.weightKg }

fun bodyFatPoints(measurements: List<BodyMeasurement>, fromDay: Long, toDay: Long): List<ChartPoint> =
    dayAveraged(measurements, fromDay, toDay) { it.bodyFatPercent }

private fun dayAveraged(
    measurements: List<BodyMeasurement>,
    fromDay: Long,
    toDay: Long,
    valueOf: (BodyMeasurement) -> Double?,
): List<ChartPoint> {
    val byDay = measurements
        .mapNotNull { m -> valueOf(m)?.takeIf { it.isFinite() }?.let { m.day to it } }
        .groupBy({ it.first }, { it.second })
    return dailySeries(fromDay, toDay) { day -> byDay[day]?.average() }
}

/** The dashed trend line: a trailing [window]-day average of [points], keeping the same x grid. */
fun movingAveragePoints(points: List<ChartPoint>, window: Int = WEIGHT_AVERAGE_WINDOW): List<ChartPoint> {
    val averaged = movingAverage(points.map { it.y }, window)
    return points.mapIndexed { index, point -> ChartPoint(point.x, averaged[index]) }
}

/** Resting heart rate per day, `x` = epoch day, missing days left as gaps. */
fun restingHrPoints(summaries: List<DailyHealthSummary>, fromDay: Long, toDay: Long): List<ChartPoint> {
    val byDay = summaries.mapNotNull { s -> s.restingHr?.let { s.day to it.toDouble() } }.toMap()
    return dailySeries(fromDay, toDay) { byDay[it] }
}

/**
 * Sleep duration in hours for every night in `[fromNight, toNight]`; a night with no session is
 * `0.0` so the bar chart keeps one slot per night rather than silently compressing the axis.
 */
fun sleepHoursBars(records: List<SleepRecord>, fromNight: Long, toNight: Long): List<Double> {
    val byNight = records.associateBy { it.night }
    return (fromNight..toNight).map { night -> (byNight[night]?.totalSleepMin ?: 0) / 60.0 }
}

/** First / middle / last date labels for an epoch-day x axis. */
fun dayAxisLabels(fromDay: Long, toDay: Long): List<String> {
    if (toDay < fromDay) return emptyList()
    val middle = fromDay + (toDay - fromDay) / 2
    return listOf(fromDay, middle, toDay).distinct().map { it.epochDayDate().usText(DAY_LABEL) }
}

/** Shorter labels for the 14-night sleep bars, which have far less room per slot. */
fun nightAxisLabels(fromNight: Long, toNight: Long): List<String> {
    if (toNight < fromNight) return emptyList()
    val middle = fromNight + (toNight - fromNight) / 2
    return listOf(fromNight, middle, toNight).distinct().map { it.epochDayDate().usText(NIGHT_LABEL) }
}
