package com.myhealth.data.repository

import com.myhealth.data.db.dao.BodyDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [BodyRepository] (PLAN P1.7) over `body_measurement`.
 *
 * The `within` window of the two latest-* lookups is measured back from [clock] so the nutrition
 * engine's weight ladder (§3.1.1) is testable with a pinned clock; the DAO does the ordering.
 */
class RoomBodyRepository(
    private val bodyDao: BodyDao,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BodyRepository {

    override fun observeLatest(): Flow<BodyMeasurement?> =
        bodyDao.observeLatest().map { it?.toDomain() }

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<BodyMeasurement>> =
        bodyDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } }

    override suspend fun latestWeight(withinDays: Long): BodyMeasurement? =
        withContext(ioDispatcher) { bodyDao.latestWeightSince(sinceMillis(withinDays))?.toDomain() }

    override suspend fun latestWithBodyFat(withinDays: Long): BodyMeasurement? =
        withContext(ioDispatcher) { bodyDao.latestBodyFatSince(sinceMillis(withinDays))?.toDomain() }

    override suspend fun insert(measurement: BodyMeasurement): Outcome<Long> =
        withContext(ioDispatcher) { runCatchingApp { bodyDao.upsert(measurement.toEntity()) } }

    override suspend fun upsertAll(measurements: List<BodyMeasurement>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp { bodyDao.upsertAllResolvingIds(measurements.map { it.toEntity() }) }
        }

    override suspend fun delete(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { bodyDao.deleteById(id) } }

    override suspend fun deleteByExternalId(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val row = bodyDao.getBySourceExternalId(source, externalId)
            if (row != null) bodyDao.deleteById(row.id)
        }
    }

    /** Start of the look-back window; a non-positive [withinDays] means "now or later" only. */
    private fun sinceMillis(withinDays: Long): Long =
        clock.millis() - withinDays * MILLIS_PER_DAY
}

private const val MILLIS_PER_DAY = 86_400_000L
