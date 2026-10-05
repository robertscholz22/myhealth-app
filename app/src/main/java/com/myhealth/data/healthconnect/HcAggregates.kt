package com.myhealth.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.floor

/**
 * The daily-total side of `HcReader` (PLAN P2.2): the five channels Health Connect can aggregate
 * server-side, plus the vitals that have to be read raw and averaged per local day.
 *
 * Day buckets are **local** days (§1.6), so the aggregation uses the local-time flavour of
 * `TimeRangeFilter` together with `aggregateGroupByPeriod(Period.ofDays(1))` — one round trip for
 * the whole range instead of one per day, which matters for the 30-day backfill windows (§6 R6).
 */
internal object HcAggregates {

    /** Exactly the metrics `daily_health_summary` has aggregate columns for (§2.2.3). */
    val DAILY_METRICS: Set<AggregateMetric<*>> = setOf(
        StepsRecord.COUNT_TOTAL,
        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
        DistanceRecord.DISTANCE_TOTAL,
        FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
    )

    /**
     * Pulls the five metrics out of one bucket. A metric with no data is absent, hence null.
     *
     * A bucket with **no** `dataOrigins` carries no app's data at all: the platform synthesises a
     * basal-metabolic calorie baseline for every day, which would otherwise be stored as a
     * measured total and pull the nutrition target down to the BMR floor (verification BUG-1).
     * Such a bucket is treated as entirely absent.
     */
    fun totalsOf(result: AggregationResult): HcDailyTotals {
        if (result.dataOrigins.isEmpty()) return HcDailyTotals()
        return HcDailyTotals(
            steps = result[StepsRecord.COUNT_TOTAL]?.toInt(),
            totalEnergyKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            activeEnergyKcal = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
            distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
            floors = result[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL],
        )
    }
}

/** True when not one of the five totals is known — such a day gets no `daily_health_summary` row. */
internal fun HcDailyTotals.isAbsent(): Boolean = steps == null && totalEnergyKcal == null &&
    activeEnergyKcal == null && distanceMeters == null && floors == null

/** Per-day vitals, read raw because Health Connect has no aggregate metric for most of them. */
private data class HcDailyVitals(
    val restingHr: Int? = null,
    val avgSpo2Percent: Double? = null,
    val avgRespiratoryRate: Double? = null,
    val hrvRmssdMs: Double? = null,
    val vo2Max: Double? = null,
)

/**
 * One [HcDailySummary] per local day in `[fromDay, toDay]` that carries any data at all — days
 * Health Connect knows nothing about are omitted rather than returned as a row of nulls.
 */
internal suspend fun HealthConnectClient.readDailySummaryRange(
    fromDay: LocalDate,
    toDay: LocalDate,
    zone: ZoneId,
): List<HcDailySummary> {
    val totals = readDailyTotals(fromDay, toDay, zone)
    val vitals = readDailyVitals(fromDay, toDay, zone)
    return (totals.keys + vitals.keys).sorted().map { day ->
        val total = totals[day] ?: HcDailyTotals()
        val vital = vitals[day] ?: HcDailyVitals()
        HcDailySummary(
            day = day,
            steps = total.steps,
            totalEnergyKcal = total.totalEnergyKcal,
            activeEnergyKcal = total.activeEnergyKcal,
            distanceMeters = total.distanceMeters,
            floors = total.floors,
            restingHr = vital.restingHr,
            avgSpo2Percent = vital.avgSpo2Percent,
            avgRespiratoryRate = vital.avgRespiratoryRate,
            hrvRmssdMs = vital.hrvRmssdMs,
            vo2Max = vital.vo2Max,
        )
    }
}

/**
 * Aggregates [HcAggregates.DAILY_METRICS] into one-day local buckets, keyed by epoch day, then
 * fills whatever the aggregates left null from the raw records (BUG-6) and drops days that ended
 * up with nothing at all.
 */
private suspend fun HealthConnectClient.readDailyTotals(
    fromDay: LocalDate,
    toDay: LocalDate,
    zone: ZoneId,
): Map<Long, HcDailyTotals> {
    val request = AggregateGroupByPeriodRequest(
        HcAggregates.DAILY_METRICS,
        TimeRangeFilter.between(fromDay.atStartOfDay(), toDay.plusDays(1).atStartOfDay()),
        Period.ofDays(1),
    )
    val aggregated = aggregateGroupByPeriod(request).associate { bucket ->
        bucket.startTime.toLocalDate().toEpochDay() to HcAggregates.totalsOf(bucket.result)
    }
    return fillMissingDailyTotals(aggregated, fromDay, toDay, zone).filterValues { !it.isAbsent() }
}

/**
 * Reads the five vital record types over the whole range in one pass each and folds them into
 * local-day buckets: resting HR, SpO2, respiratory rate and HRV are averaged over the day, VO2max
 * takes the day's last measurement.
 */
private suspend fun HealthConnectClient.readDailyVitals(
    fromDay: LocalDate,
    toDay: LocalDate,
    zone: ZoneId,
): Map<Long, HcDailyVitals> {
    val from = fromDay.atStartOfDay(zone).toInstant()
    val to = toDay.plusDays(1).atStartOfDay(zone).toInstant()

    val restingHr = readAllRecords(RestingHeartRateRecord::class, from, to)
        .groupByLocalDay(zone) { it.time }
        .mapValues { (_, records) -> records.map { it.beatsPerMinute.toDouble() }.average() }
    val spo2 = readAllRecords(OxygenSaturationRecord::class, from, to)
        .groupByLocalDay(zone) { it.time }
        .mapValues { (_, records) -> records.map { it.percentage.value }.average() }
    val respiratory = readAllRecords(RespiratoryRateRecord::class, from, to)
        .groupByLocalDay(zone) { it.time }
        .mapValues { (_, records) -> records.map { it.rate }.average() }
    val hrv = readAllRecords(HeartRateVariabilityRmssdRecord::class, from, to)
        .groupByLocalDay(zone) { it.time }
        .mapValues { (_, records) -> records.map { it.heartRateVariabilityMillis }.average() }
    val vo2Max = readAllRecords(Vo2MaxRecord::class, from, to)
        .groupByLocalDay(zone) { it.time }
        .mapValues { (_, records) ->
            records.maxBy { it.time }.vo2MillilitersPerMinuteKilogram
        }

    val days = restingHr.keys + spo2.keys + respiratory.keys + hrv.keys + vo2Max.keys
    return days.associateWith { day ->
        HcDailyVitals(
            restingHr = restingHr[day]?.let { floor(it + 0.5).toInt() },
            avgSpo2Percent = spo2[day],
            avgRespiratoryRate = respiratory[day],
            hrvRmssdMs = hrv[day],
            vo2Max = vo2Max[day],
        )
    }
}

/** Buckets records by the local epoch day of the instant [at] returns. */
private fun <T> List<T>.groupByLocalDay(
    zone: ZoneId,
    at: (T) -> java.time.Instant,
): Map<Long, List<T>> = groupBy { LocalDate.ofInstant(at(it), zone).toEpochDay() }
