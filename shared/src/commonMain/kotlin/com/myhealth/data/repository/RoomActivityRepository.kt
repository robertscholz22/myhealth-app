package com.myhealth.data.repository

import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.mapper.toSummary
import com.myhealth.domain.engine.activity.ActivityFields
import com.myhealth.domain.engine.activity.DedupeKey
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.IngestResult
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.myhealth.data.time.PlatformClock

/**
 * Room-backed [ActivityRepository] (PLAN P2.5) over `activity_session` and its children.
 *
 * All de-duplication and merging lives in [ActivityIngestor]; this class is the boundary that
 * maps entities to domain models and wraps writes in [Outcome]. The user-facing setters record
 * the edited field in `userEditedFieldsCsv`, which is what stops a later merge from clobbering it
 * (§2.4).
 */
class RoomActivityRepository(
    private val activityDao: ActivityDao,
    private val ingestor: ActivityIngestor,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActivityRepository {

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<ActivitySummary>> =
        activityDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toSummary() } }

    override fun observeRecent(limit: Int): Flow<List<ActivitySummary>> =
        activityDao.observeRecent(limit).map { rows -> rows.map { it.toSummary() } }

    override fun observeBySportGroup(
        group: SportGroup,
        fromDay: Long,
    ): Flow<List<ActivitySummary>> =
        activityDao.observeBySportGroup(group, fromDay).map { rows -> rows.map { it.toSummary() } }

    override fun observeFullById(id: Long): Flow<ActivitySession?> =
        activityDao.observeFullById(id).map { it?.toDomain() }

    override suspend fun getById(id: Long): ActivitySession? =
        withContext(ioDispatcher) { activityDao.getFullById(id)?.toDomain() }

    override suspend fun getByDay(day: Long): List<ActivitySummary> =
        withContext(ioDispatcher) { activityDao.getByDay(day).map { it.toSummary() } }

    override suspend fun getRange(fromDay: Long, toDay: Long): List<ActivitySession> =
        withContext(ioDispatcher) {
            activityDao.getRangeFull(fromDay, toDay).map { it.toDomain() }
        }

    override suspend fun ingest(items: List<ActivityIngestItem>): Outcome<IngestResult> =
        withContext(ioDispatcher) { runCatchingApp { ingestor.ingest(items) } }

    override suspend fun removeSourceRecord(
        source: ActivitySource,
        externalId: String,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { ingestor.removeSourceRecord(source, externalId) }
    }

    override suspend fun upsert(session: ActivitySession): Outcome<Long> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val entity = session.toEntity()
                val newId = activityDao.upsert(entity)
                val id = if (entity.id != 0L) entity.id else newId
                session.streams?.let { activityDao.upsertStream(it.toEntity(id)) }
                id
            }
        }

    override suspend fun setRpe(id: Long, rpe: Int?): Outcome<Unit> =
        edit(id, ActivityFields.RPE) { it.copy(rpe = rpe) }

    override suspend fun setNote(id: Long, note: String?): Outcome<Unit> =
        edit(id, ActivityFields.NOTE) { it.copy(note = note) }

    /** The denormalized `sportGroup` and the dedupe bucket are derived, so both follow the type. */
    override suspend fun setSportType(id: Long, sportType: SportType): Outcome<Unit> =
        edit(id, ActivityFields.SPORT_TYPE) { row ->
            row.copy(
                sportType = sportType,
                sportGroup = sportType.group,
                dedupeBucket = DedupeKey.of(sportType.group, row.startAtMillis),
            )
        }

    /** Written by the load recompute worker (§3.2, P5.5) — not a user edit, so nothing is pinned. */
    override suspend fun setTrimp(id: Long, trimp: Double?, method: LoadMethod?): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val row = activityDao.getById(id)
                if (row != null) {
                    activityDao.upsert(
                        row.copy(trimp = trimp, loadMethod = method, updatedAtMillis = clock.millis()),
                    )
                }
            }
        }

    override suspend fun delete(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { activityDao.deleteById(id) } }

    /**
     * Applies a manual edit and pins [field] in `userEditedFieldsCsv` so the merger leaves it
     * alone from now on (§2.4).
     */
    private suspend fun edit(
        id: Long,
        field: String,
        change: (ActivitySessionEntity) -> ActivitySessionEntity,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val row = activityDao.getById(id)
            if (row != null) {
                val pinned = (row.userEditedFieldsCsv.split(",").filter { it.isNotBlank() } + field)
                    .distinct()
                    .joinToString(",")
                activityDao.upsert(
                    change(row).copy(userEditedFieldsCsv = pinned, updatedAtMillis = clock.millis()),
                )
            }
        }
    }
}
