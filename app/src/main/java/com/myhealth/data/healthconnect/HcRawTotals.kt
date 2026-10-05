package com.myhealth.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/**
 * Raw-read fallback for the five daily totals (verification BUG-6).
 *
 * `aggregateGroupByPeriod` only returns data from apps Health Connect lists in its data-source
 * priority list; an app that was granted write access outside the permission sheet writes records
 * that are readable but invisible to the aggregates. Whenever a day's aggregate for a metric is
 * null, that metric's records are therefore read raw over the whole range **once** and summed per
 * local day.
 *
 * Double counting is the risk a raw sum has and the aggregate does not: a source that writes both
 * per-session and whole-day totals would be counted twice. Hence one origin per day per metric —
 * the origin with the most records for that day — and, inside it, whole-day records (a span of at
 * least [WHOLE_DAY_MILLIS]) win over the partial ones.
 */
internal data class HcRawPoint(
    val origin: String,
    val startMillis: Long,
    val endMillis: Long,
    val value: Double,
)

/** A record covering at least this much of a day is treated as that day's own total. */
internal const val WHOLE_DAY_MILLIS: Long = 20L * 60L * 60L * 1000L

/** Sums [points] into local-day totals following the dedup rules above. */
internal fun sumRawPointsByDay(points: List<HcRawPoint>, zone: ZoneId): Map<Long, Double> =
    points.groupBy { LocalDate.ofInstant(Instant.ofEpochMilli(it.startMillis), zone).toEpochDay() }
        .mapValues { (_, ofDay) -> sumOneDay(ofDay) }

/** One day: pick the richest origin, then prefer its whole-day records over its partial ones. */
private fun sumOneDay(points: List<HcRawPoint>): Double {
    val richest = points.groupBy { it.origin }.values.maxByOrNull { it.size } ?: return 0.0
    val wholeDay = richest.filter { it.endMillis - it.startMillis >= WHOLE_DAY_MILLIS }
    return (if (wholeDay.isNotEmpty()) wholeDay else richest).sumOf { it.value }
}

/** One metric's fallback: how to spot it missing, how to read it raw, how to write it back. */
private class RawMetric(
    val missing: (HcDailyTotals) -> Boolean,
    val read: suspend (HealthConnectClient, Instant, Instant) -> List<HcRawPoint>,
    val fill: (HcDailyTotals, Double) -> HcDailyTotals,
)

private val RAW_METRICS: List<RawMetric> = listOf(
    RawMetric(
        missing = { it.steps == null },
        read = { client, from, to ->
            client.readAllRecords(StepsRecord::class, from, to).map {
                it.rawPoint(it.startTime, it.endTime, it.count.toDouble())
            }
        },
        fill = { totals, value -> totals.copy(steps = floor(value + 0.5).toInt()) },
    ),
    RawMetric(
        missing = { it.totalEnergyKcal == null },
        read = { client, from, to ->
            client.readAllRecords(TotalCaloriesBurnedRecord::class, from, to).map {
                it.rawPoint(it.startTime, it.endTime, it.energy.inKilocalories)
            }
        },
        fill = { totals, value -> totals.copy(totalEnergyKcal = value) },
    ),
    RawMetric(
        missing = { it.activeEnergyKcal == null },
        read = { client, from, to ->
            client.readAllRecords(ActiveCaloriesBurnedRecord::class, from, to).map {
                it.rawPoint(it.startTime, it.endTime, it.energy.inKilocalories)
            }
        },
        fill = { totals, value -> totals.copy(activeEnergyKcal = value) },
    ),
    RawMetric(
        missing = { it.distanceMeters == null },
        read = { client, from, to ->
            client.readAllRecords(DistanceRecord::class, from, to).map {
                it.rawPoint(it.startTime, it.endTime, it.distance.inMeters)
            }
        },
        fill = { totals, value -> totals.copy(distanceMeters = value) },
    ),
    RawMetric(
        missing = { it.floors == null },
        read = { client, from, to ->
            client.readAllRecords(FloorsClimbedRecord::class, from, to).map {
                it.rawPoint(it.startTime, it.endTime, it.floors)
            }
        },
        fill = { totals, value -> totals.copy(floors = value) },
    ),
)

/**
 * Fills every metric [aggregated] left null in `[fromDay, toDay]` from raw records. A metric whose
 * aggregate already covers every day of the range is never read.
 */
internal suspend fun HealthConnectClient.fillMissingDailyTotals(
    aggregated: Map<Long, HcDailyTotals>,
    fromDay: LocalDate,
    toDay: LocalDate,
    zone: ZoneId,
): Map<Long, HcDailyTotals> {
    val days = fromDay.toEpochDay()..toDay.toEpochDay()
    val from = fromDay.atStartOfDay(zone).toInstant()
    val to = toDay.plusDays(1).atStartOfDay(zone).toInstant()
    var totals = aggregated
    for (metric in RAW_METRICS) {
        if (days.none { metric.missing(totals[it] ?: HcDailyTotals()) }) continue
        for ((day, value) in sumRawPointsByDay(metric.read(this, from, to), zone)) {
            val current = totals[day] ?: HcDailyTotals()
            if (day !in days || !metric.missing(current)) continue
            totals = totals + (day to metric.fill(current, value))
        }
    }
    return totals
}

/** The `metadata.dataOrigin` of a record plus the span and value the fallback sums. */
private fun androidx.health.connect.client.records.Record.rawPoint(
    start: Instant,
    end: Instant,
    value: Double,
): HcRawPoint = HcRawPoint(
    origin = metadata.dataOrigin.packageName,
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    value = value,
)
