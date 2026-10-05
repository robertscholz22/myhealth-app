package com.myhealth.ui.activities

import androidx.compose.runtime.Composable
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.SportGroup
import com.myhealth.ui.common.charts.ChartSeries
import com.myhealth.ui.common.charts.LineChartCard
import com.myhealth.ui.common.charts.invertY
import com.myhealth.ui.common.fmtDecimal

/** Heart rate against elapsed minutes (PLAN P8.3). */
@Composable
internal fun HrChartCard(activity: ActivitySession) {
    LineChartCard(
        title = stringResource(Res.string.activity_chart_hr_title),
        series = listOf(ChartSeries(name = stringResource(Res.string.activity_chart_hr_series), points = hrPoints(activity.streams))),
        xLabels = minuteAxisLabels(activity.streams),
        yFormatter = { fmtDecimal(it, 0) },
        emptyMessage = stringResource(Res.string.activity_chart_hr_empty),
    )
}

/**
 * Runs get pace (min/km) on an **inverted** axis so a faster kilometre sits higher; everything
 * else gets plain speed in km/h.
 */
@Composable
internal fun PaceOrSpeedChartCard(activity: ActivitySession) {
    val streams = activity.streams
    val distance = streams?.distanceMeters
    val speed = streams?.speedMps
    val pace = when {
        streams == null || activity.sportGroup != SportGroup.RUN -> null
        distance != null -> paceSeriesFromStream(streams.sampleOffsetsSec, distance)
        speed != null -> paceSeriesFromSpeed(streams.sampleOffsetsSec, speed)
        else -> null
    }
    if (pace != null) {
        LineChartCard(
            title = stringResource(Res.string.activity_chart_pace_title),
            series = listOf(ChartSeries(name = stringResource(Res.string.activity_chart_pace_series), points = pace.invertY())),
            xLabels = minuteAxisLabels(streams),
            yFormatter = { formatPaceAxis(-it) },
            emptyMessage = stringResource(Res.string.activity_chart_pace_empty),
        )
    } else {
        LineChartCard(
            title = stringResource(Res.string.activity_chart_speed_title),
            series = listOf(ChartSeries(name = stringResource(Res.string.activity_chart_speed_series), points = speedPoints(streams))),
            xLabels = minuteAxisLabels(streams),
            yFormatter = { fmtDecimal(it, 1) },
            emptyMessage = stringResource(Res.string.activity_chart_speed_empty),
        )
    }
}

/** Power over elapsed minutes, when the ride carries a `powerW` stream (PLAN "UI.", P12.4). */
@Composable
internal fun PowerChartCard(activity: ActivitySession) {
    LineChartCard(
        title = stringResource(Res.string.activity_chart_power_title),
        series = listOf(ChartSeries(name = stringResource(Res.string.activity_chart_power_series), points = powerPoints(activity.streams))),
        xLabels = minuteAxisLabels(activity.streams),
        yFormatter = { fmtDecimal(it, 0) },
        emptyMessage = stringResource(Res.string.activity_chart_power_empty),
    )
}

@Composable
internal fun AltitudeChartCard(activity: ActivitySession) {
    LineChartCard(
        title = stringResource(Res.string.activity_chart_altitude_title),
        series = listOf(ChartSeries(name = stringResource(Res.string.activity_chart_altitude_series), points = altitudePoints(activity.streams))),
        xLabels = minuteAxisLabels(activity.streams),
        yFormatter = { fmtDecimal(it, 0) },
        emptyMessage = stringResource(Res.string.activity_chart_altitude_empty),
    )
}
