package com.myhealth.data.repository

import com.myhealth.data.db.dao.HealthDao
import com.myhealth.data.db.dao.SleepDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SleepStageInterval
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Room-backed [HealthRepository] (PLAN P2.5) over `daily_health_summary` and `sleep_session`.
 *
 * Two merge rules make repeated Health Connect syncs safe:
 * - a daily summary re-read from Health Connect never carries `bodyBattery`, `stressAvg` or
 *   `trainingReadiness` (only the Tier-3 Garmin client of P9 can), so those columns are carried
 *   over from the stored row instead of being overwritten with `null` (§2.2.3).
 * - sleep is keyed by `night` (a unique index): a session landing on a night that already has a
 *   row updates that row rather than colliding with it, taking the union of the two intervals and
 *   of their stages (§2.2.3).
 */
class RoomHealthRepository(
    private val healthDao: HealthDao,
    private val sleepDao: SleepDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HealthRepository {

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyHealthSummary>> =
        healthDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } }

    override fun observeDay(day: Long): Flow<DailyHealthSummary?> =
        healthDao.observeRange(day, day).map { rows -> rows.firstOrNull()?.toDomain() }

    override fun observeLatest(): Flow<DailyHealthSummary?> =
        healthDao.observeLatest().map { it?.toDomain() }

    override suspend fun getDay(day: Long): DailyHealthSummary? =
        withContext(ioDispatcher) { healthDao.getByDay(day)?.toDomain() }

    override suspend fun upsertAll(summaries: List<DailyHealthSummary>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val merged = summaries.map { incoming ->
                    keepTierThree(incoming, healthDao.getByDay(incoming.day)?.toDomain())
                }
                healthDao.upsertAll(merged.map { it.toEntity() })
            }
        }

    override suspend fun deleteEmptySummaries(): Outcome<Int> = withContext(ioDispatcher) {
        runCatchingApp { healthDao.deleteEmptySummaries() }
    }

    override fun observeSleepRange(fromNight: Long, toNight: Long): Flow<List<SleepRecord>> =
        sleepDao.observeRange(fromNight, toNight).map { rows -> rows.map { it.toDomain() } }

    override fun observeLatestSleep(): Flow<SleepRecord?> =
        sleepDao.observeLatest().map { it?.toDomain() }

    override suspend fun getSleep(night: Long): SleepRecord? =
        withContext(ioDispatcher) { sleepDao.getByNight(night)?.toDomain() }

    override suspend fun upsertSleep(records: List<SleepRecord>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                for (incoming in records) {
                    val stored = sleepDao.getByNight(incoming.night)?.toDomain()
                    sleepDao.upsert(mergeNight(stored, incoming).toEntity())
                }
            }
        }

    override suspend fun deleteSleepByExternalId(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val row = sleepDao.getBySourceExternalId(source, externalId)
            if (row != null) sleepDao.deleteById(row.id)
        }
    }

    /** A `null` Tier-3 value means "this source cannot know", never "the value is gone". */
    private fun keepTierThree(
        incoming: DailyHealthSummary,
        stored: DailyHealthSummary?,
    ): DailyHealthSummary {
        if (stored == null) return incoming
        return incoming.copy(
            bodyBattery = incoming.bodyBattery ?: stored.bodyBattery,
            stressAvg = incoming.stressAvg ?: stored.stressAvg,
            trainingReadiness = incoming.trainingReadiness ?: stored.trainingReadiness,
        )
    }

    /** Union of two sessions attributed to the same night; keeps the stored row's id. */
    private fun mergeNight(stored: SleepRecord?, incoming: SleepRecord): SleepRecord {
        if (stored == null) return incoming
        return incoming.copy(
            id = stored.id,
            startAtMillis = min(stored.startAtMillis, incoming.startAtMillis),
            endAtMillis = max(stored.endAtMillis, incoming.endAtMillis),
            totalSleepMin = max(stored.totalSleepMin, incoming.totalSleepMin),
            lightMin = incoming.lightMin ?: stored.lightMin,
            deepMin = incoming.deepMin ?: stored.deepMin,
            remMin = incoming.remMin ?: stored.remMin,
            awakeMin = incoming.awakeMin ?: stored.awakeMin,
            stages = mergeStages(stored.stages, incoming.stages),
            externalId = incoming.externalId ?: stored.externalId,
            sleepScore = incoming.sleepScore ?: stored.sleepScore,
        )
    }

    private fun mergeStages(
        stored: List<SleepStageInterval>?,
        incoming: List<SleepStageInterval>?,
    ): List<SleepStageInterval>? {
        if (stored == null) return incoming
        if (incoming == null) return stored
        return (stored + incoming).distinct().sortedBy { it.startAtMillis }
    }
}
