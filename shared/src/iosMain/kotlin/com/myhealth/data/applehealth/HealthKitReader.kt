package com.myhealth.data.applehealth

import com.myhealth.data.healthconnect.HcBody
import com.myhealth.data.healthconnect.HcChanges
import com.myhealth.data.healthconnect.HcDailySummary
import com.myhealth.data.healthconnect.HcExercise
import com.myhealth.data.healthconnect.HcHeartRateSample
import com.myhealth.data.healthconnect.HcLap
import com.myhealth.data.healthconnect.HcReader
import com.myhealth.data.healthconnect.HcRecordDto
import com.myhealth.data.healthconnect.HcRecordKind
import com.myhealth.data.healthconnect.HcSample
import com.myhealth.data.healthconnect.HcSleep
import com.myhealth.data.healthconnect.roundHalfUp
import com.myhealth.data.healthconnect.toLocalDay
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.domain.util.Outcome
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import platform.Foundation.NSDateComponents
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKMetadataKeyElevationAscended
import platform.HealthKit.HKMetadataKeyIndoorWorkout
import platform.HealthKit.HKMetadataKeySwimmingLocationType
import platform.HealthKit.HKQuantity
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionNone
import platform.HealthKit.HKQueryOptionStrictStartDate
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKStatisticsCollectionQuery
import platform.HealthKit.HKStatisticsOptionCumulativeSum
import platform.HealthKit.HKStatisticsOptionDiscreteAverage
import platform.HealthKit.HKUnit
import platform.HealthKit.HKWorkout
import platform.HealthKit.HKWorkoutActivityTypeCycling
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutEvent
import platform.HealthKit.HKWorkoutEventTypeLap
import platform.HealthKit.predicateForSamplesWithStartDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HcReader] over HealthKit (P22.1): Apple Health data in the Health Connect DTOs, so the common
 * `HcSyncService`, `HcBackfill`, `HcMapper`, de-duplication and TRIMP run unchanged on iOS.
 *
 * - Workouts: `HKWorkout` plus the heart rate, speed, power and cadence samples inside its window
 *   (preferring the workout's own source), lap events → laps.
 * - Daily summaries: `HKStatisticsCollectionQuery` per local day, so HealthKit's own cross-source
 *   priority decides which steps/energy count.
 * - Sleep: stage samples assembled into nights by [AppleSleepAssembler].
 * - Changes: one `HKAnchoredObjectQuery` per type, anchors carried in an [AppleAnchorToken].
 */
@OptIn(ExperimentalForeignApi::class)
class HealthKitReader(
    private val store: HKHealthStore,
    private val clock: PlatformClock,
) : HcReader {

    private val zone: TimeZone get() = clock.timeZone

    // ---- full reads ------------------------------------------------------------------------

    override suspend fun readExerciseSessions(fromMillis: Long, toMillis: Long): Outcome<List<HcExercise>> =
        healthKitCall {
            store.samples(HealthKitTypes.workout, window(fromMillis, toMillis, strictStart = true))
                .filterIsInstance<HKWorkout>()
                .map { exerciseOf(it) }
        }

    override suspend fun readDailySummaries(fromDay: Long, toDay: Long, zone: TimeZone): Outcome<List<HcDailySummary>> =
        healthKitCall { dailySummaries(fromDay, toDay, zone) }

    override suspend fun readSleep(fromMillis: Long, toMillis: Long): Outcome<List<HcSleep>> =
        healthKitCall { sleepNights(fromMillis, toMillis) }

    override suspend fun readBody(fromMillis: Long, toMillis: Long): Outcome<List<HcBody>> = healthKitCall {
        HealthKitTypes.body.flatMap { type ->
            store.samples(type, window(fromMillis, toMillis, strictStart = true))
                .filterIsInstance<HKQuantitySample>()
                .map { bodyOf(it) }
        }
    }

    // ---- changes ---------------------------------------------------------------------------

    /**
     * Anchors "as of now" for [kinds]. HealthKit hands out an anchor only with a query's results,
     * so the query is bounded to the sync window (the caller has just read that window anyway).
     */
    override suspend fun getChangesToken(kinds: Set<HcRecordKind>): Outcome<String> = healthKitCall {
        val kind = kinds.single()
        val since = clock.millis() - TOKEN_WINDOW_MILLIS
        val anchors = typesOf(kind).associate { type ->
            val page = store.anchored(type, window(since, null, strictStart = false), null, platform.HealthKit.HKObjectQueryNoLimit)
            type.identifier to (page.anchor?.let { AnchorCodec.encode(it) } ?: "")
        }
        AppleAnchorToken(kind, anchors).encode()
    }

    override suspend fun getChanges(token: String): Outcome<HcChanges> {
        val decoded = AppleAnchorToken.decode(token) ?: return Outcome.Ok(HcChanges(expired = true))
        return healthKitCall { changes(decoded) }
    }

    private suspend fun changes(token: AppleAnchorToken): HcChanges {
        val limit = if (token.kind == HcRecordKind.EXERCISE) EXERCISE_PAGE else SAMPLE_PAGE
        val added = mutableListOf<platform.HealthKit.HKSample>()
        val deleted = mutableListOf<String>()
        val anchors = token.anchors.toMutableMap()
        var hasMore = false
        for (type in typesOf(token.kind)) {
            val stored = token.anchors[type.identifier]
            val anchor = stored?.takeIf { it.isNotEmpty() }?.let { AnchorCodec.decode(it) ?: return HcChanges(expired = true) }
            val page = store.anchored(type, null, anchor, limit.toULong())
            added += page.added
            deleted += page.deleted.map { it.UUID.UUIDString }
            page.anchor?.let { AnchorCodec.encode(it) }?.let { anchors[type.identifier] = it }
            if (page.added.size + page.deleted.size >= limit) hasMore = true
        }
        val next = AppleAnchorToken(token.kind, anchors).encode()
        val upserts: List<HcRecordDto>
        var deletedIds: List<String> = deleted
        when (token.kind) {
            HcRecordKind.EXERCISE -> upserts = added.filterIsInstance<HKWorkout>().map { HcRecordDto.Exercise(exerciseOf(it)) }
            HcRecordKind.DAILY -> upserts = added.map {
                HcRecordDto.DailyPoint(it.UUID.UUIDString, it.startDate.toMillis(), it.endDate.toMillis())
            }
            HcRecordKind.BODY -> upserts = added.filterIsInstance<HKQuantitySample>().map { HcRecordDto.Body(bodyOf(it)) }
            HcRecordKind.SLEEP -> {
                // Samples change, nights are stored: the touched nights are re-assembled. A deleted
                // sample carries no time, so a deletion re-reads the whole sync window.
                deletedIds = emptyList()
                val from = if (deleted.isNotEmpty() || added.isEmpty()) {
                    clock.millis() - TOKEN_WINDOW_MILLIS
                } else {
                    added.minOf { it.startDate.toMillis() } - DAY_MILLIS
                }
                val to = if (added.isEmpty()) clock.millis() else added.maxOf { it.endDate.toMillis() } + DAY_MILLIS
                upserts = if (added.isEmpty() && deleted.isEmpty()) emptyList() else sleepNights(from, to).map { HcRecordDto.Sleep(it) }
            }
        }
        return HcChanges(upserts = upserts, deletedIds = deletedIds, nextToken = next, hasMore = hasMore)
    }

    private fun typesOf(kind: HcRecordKind): List<HKSampleType> = when (kind) {
        HcRecordKind.EXERCISE -> listOf(HealthKitTypes.workout)
        HcRecordKind.DAILY -> HealthKitTypes.daily.map { it.second }
        HcRecordKind.SLEEP -> listOf(HealthKitTypes.sleep)
        HcRecordKind.BODY -> HealthKitTypes.body
    }

    // ---- workouts --------------------------------------------------------------------------

    private suspend fun exerciseOf(workout: HKWorkout): HcExercise {
        val start = workout.startDate.toMillis()
        val end = workout.endDate.toMillis()
        val source = workout.sourceRevision.source.bundleIdentifier
        val metadata = workout.metadata
        val activity = workout.workoutActivityType
        val active = workout.statisticsForType(HealthKitTypes.activeEnergy)?.sumQuantity().valueIn(HealthKitUnits.kcal)
            ?: workout.totalEnergyBurned.valueIn(HealthKitUnits.kcal)
        val basal = workout.statisticsForType(HealthKitTypes.basalEnergy)?.sumQuantity().valueIn(HealthKitUnits.kcal)
        val isRun = activity == HKWorkoutActivityTypeRunning
        val isRide = activity == HKWorkoutActivityTypeCycling

        suspend fun series(type: HKQuantityType, unit: HKUnit): List<HcSample> =
            streamOf(type, start, end, source).mapNotNull { sample ->
                sample.quantity.valueIn(unit)?.let { HcSample(sample.startDate.toMillis(), it) }
            }

        val heartRate = series(HealthKitTypes.heartRate, HealthKitUnits.perMinute)
            .map { HcHeartRateSample(it.timeMillis, it.value.roundHalfUp()) }
        val speed = when {
            isRun -> series(HealthKitTypes.runningSpeed, HealthKitUnits.meterPerSecond)
            isRide -> series(HealthKitTypes.cyclingSpeed, HealthKitUnits.meterPerSecond)
            else -> emptyList()
        }
        val power = when {
            isRun -> series(HealthKitTypes.runningPower, HealthKitUnits.watt)
            isRide -> series(HealthKitTypes.cyclingPower, HealthKitUnits.watt)
            else -> emptyList()
        }
        val pedalCadence = if (isRide) series(HealthKitTypes.cyclingCadence, HealthKitUnits.perMinute) else emptyList()
        val laps = workout.workoutEvents.orEmpty()
            .filterIsInstance<HKWorkoutEvent>()
            .filter { it.type == HKWorkoutEventTypeLap }
            .mapIndexed { index, event ->
                HcLap(index, event.dateInterval.startDate.toMillis(), event.dateInterval.endDate.toMillis(), null)
            }

        return HcExercise(
            externalId = workout.UUID.UUIDString,
            packageName = source,
            startMillis = start,
            endMillis = end,
            exerciseType = AppleWorkoutType.toHcExerciseType(
                activity.toLong(),
                indoor = metadata?.get(HKMetadataKeyIndoorWorkout).metadataBoolean(),
                swimLocation = metadata?.get(HKMetadataKeySwimmingLocationType).metadataLong(),
            ),
            title = null,
            notes = null,
            distanceMeters = workout.totalDistance.valueIn(HealthKitUnits.meter),
            totalEnergyKcal = basal?.let { it + (active ?: 0.0) },
            activeEnergyKcal = active,
            elevationGainM = (metadata?.get(HKMetadataKeyElevationAscended) as? HKQuantity).valueIn(HealthKitUnits.meter),
            heartRateSamples = heartRate,
            speedSamples = speed,
            powerSamples = power,
            pedalCadenceSamples = pedalCadence,
            laps = laps,
        )
    }

    /**
     * Samples of [type] inside a workout window. Garmin Connect writes its streams as plain samples
     * that are not attached to the workout, so the window is the link; when the workout's own app
     * wrote some, other sources (a phone's step-derived values) are left out.
     */
    private suspend fun streamOf(type: HKQuantityType, start: Long, end: Long, source: String): List<HKQuantitySample> {
        val all = store.samples(type, window(start, end, strictStart = true)).filterIsInstance<HKQuantitySample>()
        val own = all.filter { it.sourceRevision.source.bundleIdentifier == source }
        return own.ifEmpty { all }
    }

    // ---- daily, sleep, body ----------------------------------------------------------------

    private suspend fun dailySummaries(fromDay: Long, toDay: Long, zone: TimeZone): List<HcDailySummary> {
        val from = LocalDate.fromEpochDays(fromDay.toInt()).atStartOfDayIn(zone).toEpochMilliseconds()
        val to = LocalDate.fromEpochDays((toDay + 1).toInt()).atStartOfDayIn(zone).toEpochMilliseconds()
        val values = HealthKitTypes.daily.associate { (metric, type, cumulative) ->
            metric to dailyStatistics(type, cumulative, from, to, unitOf(metric))
                .filterKeys { it in fromDay..toDay }
        }
        return AppleDailyAssembler.assemble(values)
    }

    private fun unitOf(metric: AppleDailyMetric): HKUnit = when (metric) {
        AppleDailyMetric.STEPS, AppleDailyMetric.FLOORS -> HealthKitUnits.count
        AppleDailyMetric.ACTIVE_KCAL, AppleDailyMetric.BASAL_KCAL -> HealthKitUnits.kcal
        AppleDailyMetric.DISTANCE_M -> HealthKitUnits.meter
        AppleDailyMetric.RESTING_HR, AppleDailyMetric.RESPIRATORY_RATE -> HealthKitUnits.perMinute
        AppleDailyMetric.SPO2_FRACTION -> HealthKitUnits.percent
        AppleDailyMetric.VO2_MAX -> HealthKitUnits.vo2
    }

    /** Local day → sum or average of [type] over that day. */
    private suspend fun dailyStatistics(
        type: HKQuantityType,
        cumulative: Boolean,
        fromMillis: Long,
        toMillis: Long,
        unit: HKUnit,
    ): Map<Long, Double> = suspendCancellableCoroutine { cont ->
        val interval = NSDateComponents().apply { day = 1 }
        val query = HKStatisticsCollectionQuery(
            quantityType = type,
            quantitySamplePredicate = window(fromMillis, toMillis, strictStart = true),
            options = if (cumulative) HKStatisticsOptionCumulativeSum else HKStatisticsOptionDiscreteAverage,
            anchorDate = fromMillis.toNSDate(),
            intervalComponents = interval,
        )
        query.initialResultsHandler = { _, collection, error ->
            if (error != null) {
                cont.resumeWithException(HealthKitException(error))
            } else {
                val result = mutableMapOf<Long, Double>()
                collection?.statistics().orEmpty()
                    .filterIsInstance<platform.HealthKit.HKStatistics>()
                    .forEach { stat ->
                        val quantity = if (cumulative) stat.sumQuantity() else stat.averageQuantity()
                        quantity.valueIn(unit)?.let { result[stat.startDate.toMillis().toLocalDay(zone)] = it }
                    }
                cont.resume(result)
            }
        }
        cont.invokeOnCancellation { store.stopQuery(query) }
        store.executeQuery(query)
    }

    /**
     * Nights ending in `[fromMillis, toMillis)`. Samples are read from the evening before, so a
     * night that started before the window is not cut in half.
     */
    private suspend fun sleepNights(fromMillis: Long, toMillis: Long): List<HcSleep> {
        val samples = store.samples(HealthKitTypes.sleep, window(fromMillis - SLEEP_LOOKBACK_MILLIS, toMillis, strictStart = false))
            .filterIsInstance<HKCategorySample>()
            .map {
                AppleSleepSample(
                    id = it.UUID.UUIDString,
                    source = it.sourceRevision.source.bundleIdentifier,
                    startMillis = it.startDate.toMillis(),
                    endMillis = it.endDate.toMillis(),
                    value = it.value,
                )
            }
        val firstNight = fromMillis.toLocalDay(zone)
        val lastNight = (toMillis - 1).toLocalDay(zone)
        return AppleSleepAssembler.assemble(samples, zone).filter { it.endMillis.toLocalDay(zone) in firstNight..lastNight }
    }

    private fun bodyOf(sample: HKQuantitySample): HcBody {
        val isWeight = sample.quantityType == HealthKitTypes.bodyMass
        return HcBody(
            externalId = sample.UUID.UUIDString,
            packageName = sample.sourceRevision.source.bundleIdentifier,
            timeMillis = sample.startDate.toMillis(),
            weightKg = if (isWeight) sample.quantity.valueIn(HealthKitUnits.kilogram) else null,
            bodyFatPercent = if (isWeight) null else sample.quantity.valueIn(HealthKitUnits.percent)?.let { it * 100.0 },
        )
    }

    private fun window(fromMillis: Long, toMillis: Long?, strictStart: Boolean) = HKQuery.predicateForSamplesWithStartDate(
        fromMillis.toNSDate(),
        toMillis?.toNSDate(),
        if (strictStart) HKQueryOptionStrictStartDate else HKQueryOptionNone,
    )

    private companion object {
        const val DAY_MILLIS: Long = 24 * 60 * 60 * 1000L

        /** The window a fresh token's query covers — the same 30 days `HcSyncService` reads first. */
        const val TOKEN_WINDOW_MILLIS: Long = 30 * DAY_MILLIS
        const val SLEEP_LOOKBACK_MILLIS: Long = 18 * 60 * 60 * 1000L
        const val EXERCISE_PAGE: Int = 25
        const val SAMPLE_PAGE: Int = 1000
    }
}
