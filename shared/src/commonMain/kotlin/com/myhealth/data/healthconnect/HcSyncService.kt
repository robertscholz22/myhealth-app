package com.myhealth.data.healthconnect

import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.SyncKeys
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.epochMillisToDay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** One sync channel: a `sync_state` key, its own changes token and its record types (P2.6). */
enum class HcSyncChannel(val key: String, val kind: HcRecordKind) {
    EXERCISE(SyncKeys.HC_EXERCISE, HcRecordKind.EXERCISE),
    DAILY(SyncKeys.HC_DAILY, HcRecordKind.DAILY),
    SLEEP(SyncKeys.HC_SLEEP, HcRecordKind.SLEEP),
    BODY(SyncKeys.HC_BODY, HcRecordKind.BODY),
}

/** What one [HcSyncService.syncIncremental] run changed. */
data class SyncSummary(
    val activitiesInserted: Int = 0,
    val activitiesMerged: Int = 0,
    val activitiesDuplicate: Int = 0,
    val activitiesDeleted: Int = 0,
    val daysUpdated: Int = 0,
    val sleepUpdated: Int = 0,
    val sleepDeleted: Int = 0,
    val bodyUpdated: Int = 0,
    val bodyDeleted: Int = 0,
    /** How many bounded 30-day full reads ran (first run, or one token-expiry recovery). */
    val fullReads: Int = 0,
    /** Daily rows deleted because they carried only the synthetic basal-calorie baseline. */
    val emptyDaysRemoved: Int = 0,
    /**
     * Oldest local day whose training load this run can have changed — the earliest ingested or
     * changed exercise day (verification BUG-5). Null when no activity was touched; the caller
     * then falls back to today. `LoadRecomputeService.recompute` widens it by 28 days itself.
     */
    val minAffectedDay: Long? = null,
    val errors: List<String> = emptyList(),
) {
    operator fun plus(other: SyncSummary): SyncSummary = SyncSummary(
        activitiesInserted + other.activitiesInserted,
        activitiesMerged + other.activitiesMerged,
        activitiesDuplicate + other.activitiesDuplicate,
        activitiesDeleted + other.activitiesDeleted,
        daysUpdated + other.daysUpdated,
        sleepUpdated + other.sleepUpdated,
        sleepDeleted + other.sleepDeleted,
        bodyUpdated + other.bodyUpdated,
        bodyDeleted + other.bodyDeleted,
        fullReads + other.fullReads,
        emptyDaysRemoved + other.emptyDaysRemoved,
        minOfDays(minAffectedDay, other.minAffectedDay),
        errors + other.errors,
    )
}

/**
 * Incremental Health Connect sync driven by changes tokens (PLAN P2.6, amendment A6).
 *
 * Per channel: with no stored token, a bounded initial read of the last [WINDOW_DAYS] days runs
 * and a token is acquired; with a token, `getChanges` is paged while `hasMore` is true, the
 * `nextChangesToken` being persisted after **every** page so an interrupted run resumes instead
 * of replaying. A token reported as expired clears the token, triggers exactly **one** full
 * 30-day re-read, acquires a fresh token and records `lastError` — it never loops.
 *
 * Daily summaries are re-aggregated rather than patched: a change notification names records
 * (steps, calories, …), not daily totals, so `[minChangedDay, today]` is recomputed from Health
 * Connect's own aggregates, capped at the 30-day window.
 */
class HcSyncService(
    private val reader: HcReader,
    private val mapper: HcMapper,
    private val activityRepo: ActivityRepository,
    private val healthRepo: HealthRepository,
    private val bodyRepo: BodyRepository,
    private val syncStateRepo: SyncStateRepository,
    private val clock: PlatformClock,
    private val zone: TimeZone = clock.timeZone,
) {

    suspend fun syncIncremental(): Outcome<SyncSummary> {
        var summary = SyncSummary()
        val failures = mutableListOf<AppError>()
        for (channel in HcSyncChannel.entries) {
            when (val outcome = syncChannel(channel)) {
                is Outcome.Ok -> {
                    summary += outcome.value
                    syncStateRepo.recordSuccess(channel.key, clock.millis())
                }

                is Outcome.Err -> {
                    failures += outcome.error
                    val message = outcome.error.describe()
                    syncStateRepo.recordError(channel.key, clock.millis(), message)
                    summary += SyncSummary(errors = listOf("${channel.key}: $message"))
                }
            }
        }
        if (failures.size == HcSyncChannel.entries.size) return Outcome.Err(failures.first())
        // Rows that ended up with nothing but the synthetic basal-calorie baseline are worse than
        // no row at all — the target engine would read them as a measured TDEE (BUG-1).
        val purged = healthRepo.deleteEmptySummaries()
        if (purged is Outcome.Ok) summary = summary.copy(emptyDaysRemoved = purged.value)
        return Outcome.Ok(summary)
    }

    private suspend fun syncChannel(channel: HcSyncChannel): Outcome<SyncSummary> {
        val token = syncStateRepo.get(channel.key)?.changesToken
        return if (token.isNullOrEmpty()) firstRun(channel) else incremental(channel, token)
    }

    /** No token yet: read the bounded window, then subscribe from now on. */
    private suspend fun firstRun(channel: HcSyncChannel): Outcome<SyncSummary> {
        val read = fullRead(channel)
        if (read is Outcome.Err) return read
        val token = reader.getChangesToken(setOf(channel.kind))
        if (token is Outcome.Err) return token
        syncStateRepo.setChangesToken(channel.key, (token as Outcome.Ok).value)
        return read
    }

    private suspend fun incremental(
        channel: HcSyncChannel,
        token: String,
    ): Outcome<SyncSummary> {
        val upserts = mutableListOf<HcRecordDto>()
        val deleted = mutableListOf<String>()
        var current = token
        var pages = 0
        while (true) {
            val page = when (val outcome = reader.getChanges(current)) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err ->
                    return if (outcome.error.looksLikeTokenProblem()) {
                        recoverFromExpiredToken(channel, outcome.error.describe())
                    } else {
                        outcome
                    }
            }
            if (page.expired) return recoverFromExpiredToken(channel, "changes token expired")

            upserts += page.upserts
            deleted += page.deletedIds
            if (page.nextToken.isNotEmpty()) {
                syncStateRepo.setChangesToken(channel.key, page.nextToken)
                current = page.nextToken
            }
            pages++
            if (!page.hasMore || pages >= MAX_PAGES) break
        }
        return apply(channel, upserts, deleted)
    }

    /**
     * Expiry recovery (A6): drop the token, re-read the whole window **once**, take a fresh
     * token. The old error is kept in `sync_state.lastError` so the Integrations screen can say
     * why history was re-read.
     */
    private suspend fun recoverFromExpiredToken(
        channel: HcSyncChannel,
        reason: String,
    ): Outcome<SyncSummary> {
        syncStateRepo.setChangesToken(channel.key, null)
        syncStateRepo.recordError(channel.key, clock.millis(), reason)
        val read = fullRead(channel)
        if (read is Outcome.Err) return read
        when (val token = reader.getChangesToken(setOf(channel.kind))) {
            is Outcome.Ok -> syncStateRepo.setChangesToken(channel.key, token.value)
            is Outcome.Err -> return token
        }
        return read
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** The bounded 30-day read used on the first run and on expiry recovery. */
    private suspend fun fullRead(channel: HcSyncChannel): Outcome<SyncSummary> {
        val today = clock.todayEpochDay()
        val fromDay = today - (WINDOW_DAYS - 1)
        val summary = when (channel) {
            HcSyncChannel.EXERCISE -> readExercise(fromDay, today)
            HcSyncChannel.DAILY -> readDaily(fromDay, today)
            HcSyncChannel.SLEEP -> readSleep(fromDay, today)
            HcSyncChannel.BODY -> readBody(fromDay, today)
        }
        return summary.map { it + SyncSummary(fullReads = 1) }
    }

    /**
     * Re-reads every exercise session of the last [days] days with its detail streams and merges
     * it into the existing rows. Needed after a *new* per-session permission is granted (P12:
     * `READ_POWER`): the changes token reports nothing for sessions that did not change, and a
     * backfill never revisits windows below its watermark, so without this the power of already
     * synced rides would never arrive. Bounded to [days] so it stays a single foreground run.
     */
    suspend fun rereadExerciseDetail(days: Long): Outcome<SyncSummary> {
        val today = clock.todayEpochDay()
        val outcome = readExercise(today - days.coerceAtLeast(0L), today)
        when (outcome) {
            is Outcome.Ok -> syncStateRepo.recordSuccess(SyncKeys.HC_EXERCISE, clock.millis())
            is Outcome.Err -> syncStateRepo.recordError(
                SyncKeys.HC_EXERCISE,
                clock.millis(),
                outcome.error.describe(),
            )
        }
        return outcome
    }

    internal suspend fun readExercise(fromDay: Long, toDay: Long): Outcome<SyncSummary> {
        val sessions = reader.readExerciseSessions(startOf(fromDay), endOf(toDay))
        if (sessions is Outcome.Err) return sessions
        val now = clock.millis()
        val items = (sessions as Outcome.Ok).value.map { exercise ->
            ActivityIngestItem(
                record = mapper.toSourceRecord(exercise, now),
                session = mapper.toSession(exercise, zone, now),
            )
        }
        if (items.isEmpty()) return Outcome.Ok(SyncSummary())
        val minDay = items.minOf { it.session.day }
        return activityRepo.ingest(items).map { it.toSummary(minDay) }
    }

    internal suspend fun readDaily(fromDay: Long, toDay: Long): Outcome<SyncSummary> {
        val read = reader.readDailySummaries(
            fromDay,
            toDay,
            zone,
        )
        if (read is Outcome.Err) return read
        val now = clock.millis()
        val summaries = (read as Outcome.Ok).value.map { mapper.toDailySummary(it, now) }
        if (summaries.isEmpty()) return Outcome.Ok(SyncSummary())
        return healthRepo.upsertAll(summaries).map { SyncSummary(daysUpdated = summaries.size) }
    }

    internal suspend fun readSleep(fromDay: Long, toDay: Long): Outcome<SyncSummary> {
        val read = reader.readSleep(startOf(fromDay), endOf(toDay))
        if (read is Outcome.Err) return read
        val records = mapper.toSleepRecords((read as Outcome.Ok).value, zone)
        if (records.isEmpty()) return Outcome.Ok(SyncSummary())
        return healthRepo.upsertSleep(records).map { SyncSummary(sleepUpdated = records.size) }
    }

    internal suspend fun readBody(fromDay: Long, toDay: Long): Outcome<SyncSummary> {
        val read = reader.readBody(startOf(fromDay), endOf(toDay))
        if (read is Outcome.Err) return read
        val measurements = mapper.toBodyMeasurements((read as Outcome.Ok).value, zone)
        if (measurements.isEmpty()) return Outcome.Ok(SyncSummary())
        return bodyRepo.upsertAll(measurements)
            .map { SyncSummary(bodyUpdated = measurements.size) }
    }

    // ---- change application --------------------------------------------------------------------

    private suspend fun apply(
        channel: HcSyncChannel,
        upserts: List<HcRecordDto>,
        deleted: List<String>,
    ): Outcome<SyncSummary> = when (channel) {
        HcSyncChannel.EXERCISE -> applyExercise(upserts, deleted)
        HcSyncChannel.DAILY -> applyDaily(upserts, deleted)
        HcSyncChannel.SLEEP -> applySleep(upserts, deleted)
        HcSyncChannel.BODY -> applyBody(upserts, deleted)
    }

    private suspend fun applyExercise(
        upserts: List<HcRecordDto>,
        deleted: List<String>,
    ): Outcome<SyncSummary> {
        val now = clock.millis()
        val items = upserts.filterIsInstance<HcRecordDto.Exercise>().map {
            ActivityIngestItem(
                record = mapper.toSourceRecord(it.exercise, now),
                session = mapper.toSession(it.exercise, zone, now),
            )
        }
        var summary = SyncSummary()
        if (items.isNotEmpty()) {
            val minDay = items.minOf { it.session.day }
            when (val ingested = activityRepo.ingest(items)) {
                is Outcome.Ok -> summary += ingested.value.toSummary(minDay)
                is Outcome.Err -> return ingested
            }
        }
        for (id in deleted) {
            when (val removed = activityRepo.removeSourceRecord(ActivitySource.HEALTH_CONNECT, id)) {
                is Outcome.Ok -> summary += SyncSummary(activitiesDeleted = 1)
                is Outcome.Err -> return removed
            }
        }
        return Outcome.Ok(summary)
    }

    /**
     * Changes name records, not daily totals, so the affected days are re-aggregated from scratch.
     * A deletion carries no timestamp at all — the whole window is then the only safe answer.
     */
    private suspend fun applyDaily(
        upserts: List<HcRecordDto>,
        deleted: List<String>,
    ): Outcome<SyncSummary> {
        val today = clock.todayEpochDay()
        val oldest = today - (WINDOW_DAYS - 1)
        if (upserts.isEmpty() && deleted.isEmpty()) return Outcome.Ok(SyncSummary())
        val changedDays = upserts.filterIsInstance<HcRecordDto.DailyPoint>()
            .map { dayOf(it.startMillis) }
        val from = if (deleted.isNotEmpty() || changedDays.isEmpty()) {
            oldest
        } else {
            maxOf(oldest, changedDays.min())
        }
        return readDaily(from, today)
    }

    private suspend fun applySleep(
        upserts: List<HcRecordDto>,
        deleted: List<String>,
    ): Outcome<SyncSummary> {
        var summary = SyncSummary()
        val sleeps = upserts.filterIsInstance<HcRecordDto.Sleep>().map { it.sleep }
        if (sleeps.isNotEmpty()) {
            val records = mapper.toSleepRecords(sleeps, zone)
            when (val written = healthRepo.upsertSleep(records)) {
                is Outcome.Ok -> summary += SyncSummary(sleepUpdated = records.size)
                is Outcome.Err -> return written
            }
        }
        for (id in deleted) {
            val removed = healthRepo.deleteSleepByExternalId(ActivitySource.HEALTH_CONNECT, id)
            if (removed is Outcome.Err) return removed
            summary += SyncSummary(sleepDeleted = 1)
        }
        return Outcome.Ok(summary)
    }

    private suspend fun applyBody(
        upserts: List<HcRecordDto>,
        deleted: List<String>,
    ): Outcome<SyncSummary> {
        var summary = SyncSummary()
        val bodies = upserts.filterIsInstance<HcRecordDto.Body>().map { it.body }
        if (bodies.isNotEmpty()) {
            val measurements = mapper.toBodyMeasurements(bodies, zone)
            when (val written = bodyRepo.upsertAll(measurements)) {
                is Outcome.Ok -> summary += SyncSummary(bodyUpdated = measurements.size)
                is Outcome.Err -> return written
            }
        }
        for (id in deleted) {
            val removed = bodyRepo.deleteByExternalId(ActivitySource.HEALTH_CONNECT, id)
            if (removed is Outcome.Err) return removed
            summary += SyncSummary(bodyDeleted = 1)
        }
        return Outcome.Ok(summary)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Epoch millis of the start of local [day]. */
    private fun startOf(day: Long): Long = day.epochDayDate().atStartOfDayIn(zone).toEpochMilliseconds()

    /** Epoch millis of the start of the local day after [day]. */
    private fun endOf(day: Long): Long = startOf(day + 1)

    private fun dayOf(millis: Long): Long = millis.epochMillisToDay(zone)

    companion object {
        /** Health Connect only guarantees 30 days without `READ_HEALTH_DATA_HISTORY` (R5). */
        const val WINDOW_DAYS: Long = 30L

        /** Hard stop for the paging loop: a provider that never clears `hasMore` cannot hang us. */
        const val MAX_PAGES: Int = 500
    }
}
