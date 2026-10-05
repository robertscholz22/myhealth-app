package com.myhealth.data.healthconnect

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** The Health Connect record types each [HcRecordKind] subscribes to (P2.2/P2.6). */
internal fun recordTypesOf(kinds: Set<HcRecordKind>): Set<KClass<out Record>> =
    kinds.flatMapTo(mutableSetOf()) { kind ->
        when (kind) {
            HcRecordKind.EXERCISE -> setOf(ExerciseSessionRecord::class)
            HcRecordKind.SLEEP -> setOf(SleepSessionRecord::class)
            HcRecordKind.BODY -> setOf(WeightRecord::class, BodyFatRecord::class)
            HcRecordKind.DAILY -> setOf(
                StepsRecord::class,
                TotalCaloriesBurnedRecord::class,
                ActiveCaloriesBurnedRecord::class,
                DistanceRecord::class,
                FloorsClimbedRecord::class,
                RestingHeartRateRecord::class,
                OxygenSaturationRecord::class,
                RespiratoryRateRecord::class,
                HeartRateVariabilityRmssdRecord::class,
                Vo2MaxRecord::class,
            )
        }
    }

/**
 * Runs a Health Connect read and maps its failures to [AppError] (PLAN §1.5, P2.2):
 * `SecurityException` → permission denied, `RemoteException`/`IllegalStateException` → the
 * service is gone, `IOException` → storage. `CancellationException` is re-thrown so structured
 * concurrency keeps working.
 */
suspend fun <T> hcCatching(block: suspend () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: SecurityException) {
    Outcome.Err(AppError.HealthConnectPermissionDenied)
} catch (e: RemoteException) {
    Outcome.Err(AppError.HealthConnectUnavailable)
} catch (e: IllegalStateException) {
    Outcome.Err(AppError.HealthConnectUnavailable)
} catch (e: IOException) {
    Outcome.Err(AppError.Storage(e))
} catch (e: Exception) {
    Outcome.Err(AppError.Unexpected(e))
}

/**
 * Reads every page of [type] in `[from, to)`. Health Connect returns at most `pageSize` records
 * (default 1000) per call; the loop continues while the returned token is neither null nor empty
 * — an empty string is the "no more pages" marker on some provider versions.
 */
internal suspend fun <T : Record> HealthConnectClient.readAllRecords(
    type: KClass<T>,
    from: Instant,
    to: Instant,
): List<T> {
    val filter = TimeRangeFilter.between(from, to)
    val all = mutableListOf<T>()
    var token: String? = null
    do {
        val response = readRecords(ReadRecordsRequest(type, filter, pageToken = token))
        all += response.records
        token = response.pageToken
    } while (!token.isNullOrEmpty())
    return all
}

/**
 * The real [HcReader]. Obtain [client] from `HealthConnectProvider.client()`; a null client means
 * the caller must not construct this at all.
 *
 * Each exercise session's heart rate, distance, speed, cadence, elevation and calories are read
 * with **that session's own** time window, so a long backfill never pulls one giant series.
 */
class HealthConnectReader(
    private val client: HealthConnectClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HcReader {

    override suspend fun readExerciseSessions(
        fromMillis: Long,
        toMillis: Long,
    ): Outcome<List<HcExercise>> = hcCatching {
        withContext(io) {
            client.readAllRecords(ExerciseSessionRecord::class, fromMillis.instant(), toMillis.instant())
                .map { readDetail(it) }
        }
    }

    override suspend fun readDailySummaries(
        fromDay: Long,
        toDay: Long,
        zone: TimeZone,
    ): Outcome<List<HcDailySummary>> = hcCatching {
        withContext(io) {
            client.readDailySummaryRange(LocalDate.ofEpochDay(fromDay), LocalDate.ofEpochDay(toDay), zone.toJavaZoneId())
        }
    }

    override suspend fun readSleep(fromMillis: Long, toMillis: Long): Outcome<List<HcSleep>> =
        hcCatching {
            withContext(io) {
                client.readAllRecords(SleepSessionRecord::class, fromMillis.instant(), toMillis.instant())
                    .map { it.toDto() }
            }
        }

    override suspend fun readBody(fromMillis: Long, toMillis: Long): Outcome<List<HcBody>> = hcCatching {
        val from = fromMillis.instant()
        val to = toMillis.instant()
        withContext(io) {
            val weights = client.readAllRecords(WeightRecord::class, from, to).map { it.toDto() }
            val fats = client.readAllRecords(BodyFatRecord::class, from, to).map { it.toDto() }
            (weights + fats).sortedBy { it.timeMillis }
        }
    }

    override suspend fun getChangesToken(kinds: Set<HcRecordKind>): Outcome<String> = hcCatching {
        withContext(io) {
            client.getChangesToken(ChangesTokenRequest(recordTypes = recordTypesOf(kinds)))
        }
    }

    override suspend fun getChanges(token: String): Outcome<HcChanges> = hcCatching {
        withContext(io) {
            val response = client.getChanges(token)
            val upserts = mutableListOf<HcRecordDto>()
            val deleted = mutableListOf<String>()
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> toRecordDto(change.record)?.let { upserts += it }
                    is DeletionChange -> deleted += change.recordId
                    else -> Unit
                }
            }
            HcChanges(
                upserts = upserts,
                deletedIds = deleted,
                nextToken = response.nextChangesToken,
                hasMore = response.hasMore,
                expired = response.changesTokenExpired,
            )
        }
    }

    /**
     * One changed record → its DTO. An exercise session is re-read in full (its heart-rate and
     * distance series live in other record types), everything that only feeds the daily rows
     * collapses to its time span.
     */
    private suspend fun toRecordDto(record: Record): HcRecordDto? = when (record) {
        is ExerciseSessionRecord -> HcRecordDto.Exercise(readDetail(record))
        is SleepSessionRecord -> HcRecordDto.Sleep(record.toDto())
        is WeightRecord -> HcRecordDto.Body(record.toDto())
        is BodyFatRecord -> HcRecordDto.Body(record.toDto())
        is StepsRecord -> record.dailyPoint(record.startTime, record.endTime)
        is TotalCaloriesBurnedRecord -> record.dailyPoint(record.startTime, record.endTime)
        is ActiveCaloriesBurnedRecord -> record.dailyPoint(record.startTime, record.endTime)
        is DistanceRecord -> record.dailyPoint(record.startTime, record.endTime)
        is FloorsClimbedRecord -> record.dailyPoint(record.startTime, record.endTime)
        is RestingHeartRateRecord -> record.dailyPoint(record.time, record.time)
        is OxygenSaturationRecord -> record.dailyPoint(record.time, record.time)
        is RespiratoryRateRecord -> record.dailyPoint(record.time, record.time)
        is HeartRateVariabilityRmssdRecord -> record.dailyPoint(record.time, record.time)
        is Vo2MaxRecord -> record.dailyPoint(record.time, record.time)
        else -> null
    }

    /** Reads the series and totals belonging to one session, using that session's window. */
    private suspend fun readDetail(session: ExerciseSessionRecord): HcExercise {
        val from = session.startTime
        val to = session.endTime
        val window = from.toEpochMilli()..to.toEpochMilli()

        val heartRate = client.readAllRecords(HeartRateRecord::class, from, to)
            .flatMap { record -> record.samples }
            .map { HcHeartRateSample(it.time.toEpochMilli(), it.beatsPerMinute.toInt()) }
            .filter { it.timeMillis in window }
            .sortedBy { it.timeMillis }

        val speed = client.readAllRecords(SpeedRecord::class, from, to)
            .flatMap { record -> record.samples }
            .map { HcSample(it.time.toEpochMilli(), it.speed.inMetersPerSecond) }
            .filter { it.timeMillis in window }
            .sortedBy { it.timeMillis }

        val cadence = client.readAllRecords(StepsCadenceRecord::class, from, to)
            .flatMap { record -> record.samples }
            .map { HcSample(it.time.toEpochMilli(), it.rate) }
            .filter { it.timeMillis in window }
            .sortedBy { it.timeMillis }

        // P12: the two optional detail channels. They live in `HcPermissions.OPTIONAL_DETAIL`,
        // which is part of ALL but not of REQUIRED_CORE, so a phone upgrading from 1.0.x keeps
        // syncing without re-granting anything — each read simply degrades to an empty list until
        // the owner grants it (see `optionalSamples`).
        val power = optionalSamples {
            client.readAllRecords(PowerRecord::class, from, to)
                .flatMap { record -> record.samples }
                .map { HcSample(it.time.toEpochMilli(), it.power.inWatts) }
                .filter { it.timeMillis in window }
                .sortedBy { it.timeMillis }
        }

        val pedalCadence = optionalSamples {
            client.readAllRecords(CyclingPedalingCadenceRecord::class, from, to)
                .flatMap { record -> record.samples }
                .map { HcSample(it.time.toEpochMilli(), it.revolutionsPerMinute) }
                .filter { it.timeMillis in window }
                .sortedBy { it.timeMillis }
        }

        return HcExercise(
            externalId = session.metadata.id,
            packageName = session.metadata.dataOrigin.packageName,
            startMillis = window.first,
            endMillis = window.last,
            exerciseType = session.exerciseType,
            title = session.title,
            notes = session.notes,
            distanceMeters = client.readAllRecords(DistanceRecord::class, from, to)
                .sumOrNull { it.distance.inMeters },
            totalEnergyKcal = client.readAllRecords(TotalCaloriesBurnedRecord::class, from, to)
                .sumOrNull { it.energy.inKilocalories },
            activeEnergyKcal = client.readAllRecords(ActiveCaloriesBurnedRecord::class, from, to)
                .sumOrNull { it.energy.inKilocalories },
            elevationGainM = client.readAllRecords(ElevationGainedRecord::class, from, to)
                .sumOrNull { it.elevation.inMeters },
            heartRateSamples = heartRate,
            speedSamples = speed,
            cadenceSamples = cadence,
            powerSamples = power,
            pedalCadenceSamples = pedalCadence,
            laps = session.laps.mapIndexed { index, lap ->
                HcLap(
                    lapIndex = index,
                    startMillis = lap.startTime.toEpochMilli(),
                    endMillis = lap.endTime.toEpochMilli(),
                    distanceMeters = lap.length?.inMeters,
                )
            },
            segments = session.segments.map { segment ->
                HcSegment(
                    startMillis = segment.startTime.toEpochMilli(),
                    endMillis = segment.endTime.toEpochMilli(),
                    segmentType = segment.segmentType,
                    repetitions = segment.repetitions,
                )
            },
        )
    }
}

private fun SleepSessionRecord.toDto(): HcSleep = HcSleep(
    externalId = metadata.id,
    packageName = metadata.dataOrigin.packageName,
    startMillis = startTime.toEpochMilli(),
    endMillis = endTime.toEpochMilli(),
    title = title,
    stages = stages.map {
        HcSleepStage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.stage)
    },
)

private fun WeightRecord.toDto(): HcBody = HcBody(
    externalId = metadata.id,
    packageName = metadata.dataOrigin.packageName,
    timeMillis = time.toEpochMilli(),
    weightKg = weight.inKilograms,
)

private fun BodyFatRecord.toDto(): HcBody = HcBody(
    externalId = metadata.id,
    packageName = metadata.dataOrigin.packageName,
    timeMillis = time.toEpochMilli(),
    bodyFatPercent = percentage.value,
)

/**
 * A record that only feeds the daily aggregates, reduced to its time span. The interval and
 * instantaneous record interfaces of connect-client are `internal`, so each concrete type is
 * listed explicitly rather than matched by a common supertype.
 */
private fun Record.dailyPoint(from: Instant, to: Instant): HcRecordDto.DailyPoint =
    HcRecordDto.DailyPoint(metadata.id, from.toEpochMilli(), to.toEpochMilli())

/**
 * An optional per-session channel (P12): a `SecurityException` means the permission behind it was
 * never granted, which is an expected state, not a failure — the session is still worth having
 * without power or pedalling cadence. Every other exception is left to [hcCatching], which maps
 * it to the right [AppError]; `CancellationException` is a subclass of nothing caught here, so
 * structured concurrency is unaffected.
 */
private inline fun optionalSamples(read: () -> List<HcSample>): List<HcSample> = try {
    read()
} catch (_: SecurityException) {
    emptyList()
}

/** Sum of [select] over the list, or `null` when the list is empty (no data is not zero). */
private inline fun <T> List<T>.sumOrNull(select: (T) -> Double): Double? =
    if (isEmpty()) null else sumOf(select)

private fun Long.instant(): Instant = Instant.ofEpochMilli(this)
