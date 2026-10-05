package com.myhealth.data.healthconnect

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SyncState
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.IngestResult
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Test doubles for `HcSyncServiceTest` (PLAN P2.6) — no Health Connect, no Room, no Android. */

/** Programmable [HcReader] that records every window it was asked for. */
class FakeHcReader : HcReader {

    var exercises: List<HcExercise> = emptyList()
    var daily: List<HcDailySummary> = emptyList()
    var sleeps: List<HcSleep> = emptyList()
    var bodies: List<HcBody> = emptyList()

    val exerciseWindows = mutableListOf<Pair<Instant, Instant>>()
    val dailyWindows = mutableListOf<Pair<LocalDate, LocalDate>>()
    val sleepWindows = mutableListOf<Pair<Instant, Instant>>()
    val bodyWindows = mutableListOf<Pair<Instant, Instant>>()
    val tokenRequests = mutableListOf<Set<HcRecordKind>>()
    val changesCalls = mutableListOf<String>()

    /** Tokens handed out by [getChangesToken], in order. */
    val nextTokens = ArrayDeque(listOf("tok-1", "tok-2", "tok-3", "tok-4", "tok-5", "tok-6"))

    /** Pages returned by [getChanges], keyed by the token they are requested with. */
    val pages = mutableMapOf<String, HcChanges>()

    /** Epoch day whose exercise window must fail — used to interrupt a backfill. */
    var failExerciseFromDay: Long? = null
    var zone: ZoneId = ZoneId.of("UTC")

    override suspend fun readExerciseSessions(
        fromMillis: Long,
        toMillis: Long,
    ): Outcome<List<HcExercise>> {
        val from = Instant.ofEpochMilli(fromMillis)
        exerciseWindows += from to Instant.ofEpochMilli(toMillis)
        if (failExerciseFromDay != null && dayOf(from) == failExerciseFromDay) {
            return Outcome.Err(AppError.HealthConnectUnavailable)
        }
        return Outcome.Ok(exercises)
    }

    override suspend fun readDailySummaries(
        fromDay: Long,
        toDay: Long,
        zone: TimeZone,
    ): Outcome<List<HcDailySummary>> {
        dailyWindows += LocalDate.ofEpochDay(fromDay) to LocalDate.ofEpochDay(toDay)
        return Outcome.Ok(daily)
    }

    override suspend fun readSleep(fromMillis: Long, toMillis: Long): Outcome<List<HcSleep>> {
        sleepWindows += Instant.ofEpochMilli(fromMillis) to Instant.ofEpochMilli(toMillis)
        return Outcome.Ok(sleeps)
    }

    override suspend fun readBody(fromMillis: Long, toMillis: Long): Outcome<List<HcBody>> {
        bodyWindows += Instant.ofEpochMilli(fromMillis) to Instant.ofEpochMilli(toMillis)
        return Outcome.Ok(bodies)
    }

    override suspend fun getChangesToken(kinds: Set<HcRecordKind>): Outcome<String> {
        tokenRequests += kinds
        return Outcome.Ok(nextTokens.removeFirstOrNull() ?: "tok-exhausted")
    }

    override suspend fun getChanges(token: String): Outcome<HcChanges> {
        changesCalls += token
        return Outcome.Ok(pages[token] ?: HcChanges(nextToken = token))
    }

    private fun dayOf(instant: Instant): Long =
        LocalDate.ofInstant(instant, zone).toEpochDay()
}

class FakeSyncStateRepository : SyncStateRepository {

    val states = mutableMapOf<String, SyncState>()

    override fun observeAll(): Flow<List<SyncState>> = flowOf(states.values.toList())

    override fun observe(key: String): Flow<SyncState?> = flowOf(states[key])

    override suspend fun get(key: String): SyncState? = states[key]

    override suspend fun setChangesToken(key: String, token: String?): Outcome<Unit> =
        update(key) { it.copy(changesToken = token) }

    override suspend fun recordSuccess(key: String, atMillis: Long): Outcome<Unit> =
        update(key) { it.copy(lastSuccessAtMillis = atMillis) }

    override suspend fun recordError(key: String, atMillis: Long, message: String): Outcome<Unit> =
        update(key) { it.copy(lastErrorAtMillis = atMillis, lastError = message) }

    override suspend fun setBackfillCompleteDay(key: String, day: Long): Outcome<Unit> =
        update(key) { it.copy(backfillCompleteDay = day) }

    override suspend fun upsert(state: SyncState): Outcome<Unit> {
        states[state.key] = state
        return Outcome.Ok(Unit)
    }

    private fun update(key: String, change: (SyncState) -> SyncState): Outcome<Unit> {
        val current = states[key] ?: SyncState(key, null, null, null, null, null)
        states[key] = change(current)
        return Outcome.Ok(Unit)
    }
}

class FakeActivityRepository : ActivityRepository {

    val ingested = mutableListOf<ActivityIngestItem>()
    val removed = mutableListOf<Pair<ActivitySource, String>>()
    var ingestError: AppError? = null

    /** Canned answers for [getByDay] — used by the target-snapshot tests of P4.12. */
    val byDay = mutableMapOf<Long, List<ActivitySummary>>()

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<ActivitySummary>> = emptyFlow()
    override fun observeRecent(limit: Int): Flow<List<ActivitySummary>> = emptyFlow()
    override fun observeBySportGroup(group: SportGroup, fromDay: Long): Flow<List<ActivitySummary>> =
        emptyFlow()

    override fun observeFullById(id: Long): Flow<ActivitySession?> = emptyFlow()
    override suspend fun getById(id: Long): ActivitySession? = null
    override suspend fun getByDay(day: Long): List<ActivitySummary> = byDay[day] ?: emptyList()
    override suspend fun getRange(fromDay: Long, toDay: Long): List<ActivitySession> = emptyList()

    override suspend fun ingest(items: List<ActivityIngestItem>): Outcome<IngestResult> {
        ingestError?.let { return Outcome.Err(it) }
        ingested += items
        return Outcome.Ok(IngestResult(inserted = items.size, merged = 0, duplicate = 0))
    }

    override suspend fun removeSourceRecord(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> {
        removed += source to externalId
        return Outcome.Ok(Unit)
    }

    override suspend fun upsert(session: ActivitySession): Outcome<Long> = Outcome.Ok(0L)
    override suspend fun setRpe(id: Long, rpe: Int?): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun setNote(id: Long, note: String?): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun setSportType(id: Long, sportType: SportType): Outcome<Unit> =
        Outcome.Ok(Unit)
    override suspend fun setTrimp(id: Long, trimp: Double?, method: LoadMethod?): Outcome<Unit> =
        Outcome.Ok(Unit)

    override suspend fun delete(id: Long): Outcome<Unit> = Outcome.Ok(Unit)
}

class FakeHealthRepository : HealthRepository {

    val summaries = MutableStateFlow<Map<Long, DailyHealthSummary>>(emptyMap())
    val sleep = mutableListOf<SleepRecord>()
    val deletedSleep = mutableListOf<String>()

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyHealthSummary>> =
        summaries.map { all -> all.values.filter { it.day in fromDay..toDay } }

    override fun observeDay(day: Long): Flow<DailyHealthSummary?> = summaries.map { it[day] }
    override fun observeLatest(): Flow<DailyHealthSummary?> =
        summaries.map { all -> all.values.maxByOrNull { it.day } }

    override suspend fun getDay(day: Long): DailyHealthSummary? = summaries.value[day]

    override suspend fun upsertAll(summaries: List<DailyHealthSummary>): Outcome<Unit> {
        this.summaries.value = this.summaries.value + summaries.associateBy { it.day }
        return Outcome.Ok(Unit)
    }

    override suspend fun deleteEmptySummaries(): Outcome<Int> {
        val empty = summaries.value.filterValues { it.isEmptyExceptTotalEnergy() }
        this.summaries.value = this.summaries.value - empty.keys
        return Outcome.Ok(empty.size)
    }

    private fun DailyHealthSummary.isEmptyExceptTotalEnergy(): Boolean = steps == null &&
        activeEnergyKcal == null && distanceMeters == null && floors == null &&
        restingHr == null && avgSpo2Percent == null && avgRespiratoryRate == null &&
        hrvRmssdMs == null && vo2Max == null && bodyBattery == null && stressAvg == null &&
        trainingReadiness == null

    override fun observeSleepRange(fromNight: Long, toNight: Long): Flow<List<SleepRecord>> =
        flowOf(sleep.filter { it.night in fromNight..toNight })

    override fun observeLatestSleep(): Flow<SleepRecord?> = flowOf(sleep.maxByOrNull { it.night })

    override suspend fun getSleep(night: Long): SleepRecord? = sleep.firstOrNull { it.night == night }

    override suspend fun upsertSleep(records: List<SleepRecord>): Outcome<Unit> {
        sleep += records
        return Outcome.Ok(Unit)
    }

    override suspend fun deleteSleepByExternalId(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> {
        deletedSleep += externalId
        sleep.removeAll { it.externalId == externalId && it.source == source }
        return Outcome.Ok(Unit)
    }
}

class FakeBodyRepository : BodyRepository {

    val measurements = mutableListOf<BodyMeasurement>()
    val deleted = mutableListOf<String>()

    override fun observeLatest(): Flow<BodyMeasurement?> =
        flowOf(measurements.maxByOrNull { it.measuredAtMillis })

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<BodyMeasurement>> =
        flowOf(measurements.filter { it.day in fromDay..toDay })

    override suspend fun latestWeight(withinDays: Long): BodyMeasurement? =
        measurements.filter { it.weightKg != null }.maxByOrNull { it.measuredAtMillis }

    override suspend fun latestWithBodyFat(withinDays: Long): BodyMeasurement? =
        measurements.filter { it.bodyFatPercent != null }.maxByOrNull { it.measuredAtMillis }

    override suspend fun insert(measurement: BodyMeasurement): Outcome<Long> {
        measurements += measurement
        return Outcome.Ok(measurements.size.toLong())
    }

    override suspend fun upsertAll(measurements: List<BodyMeasurement>): Outcome<Unit> {
        this.measurements += measurements
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: Long): Outcome<Unit> = Outcome.Ok(Unit)

    override suspend fun deleteByExternalId(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> {
        deleted += externalId
        measurements.removeAll { it.externalId == externalId && it.source == source }
        return Outcome.Ok(Unit)
    }
}
