package com.myhealth.data.repository

import com.myhealth.data.db.dao.RunningBestDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [RunningBestRepository] over `running_best` (PLAN §2.2.6, §3.4, P5.4/P5.5/P5.7).
 * Every qualifying effort is kept; [RunningBestDao.observeBestPerDistance] derives the PR per
 * distance (`MIN(timeSec)`), so this class does no aggregation of its own.
 */
class RoomRunningBestRepository(
    private val dao: RunningBestDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RunningBestRepository {

    override fun observeBestPerDistance(): Flow<List<RunningBest>> =
        dao.observeBestPerDistance().map { rows -> rows.map { it.toDomain() } }

    override fun observeByDistance(distanceMeters: Double, limit: Int): Flow<List<RunningBest>> =
        dao.observeByDistance(distanceMeters, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSince(fromDay: Long): Flow<List<RunningBest>> =
        dao.observeSince(fromDay).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getForActivity(activityId: Long): List<RunningBest> =
        withContext(ioDispatcher) { dao.getByActivity(activityId).map { it.toDomain() } }

    /** Idempotent refresh after a run is (re-)ingested (P5.5): clears the activity's rows first so
     * a distance the new computation no longer reaches is not left behind as a stale PR. */
    override suspend fun replaceForActivity(activityId: Long, bests: List<RunningBest>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                dao.deleteByActivity(activityId)
                dao.upsertAll(bests.map { it.toEntity() })
            }
        }

    override suspend fun upsertAll(bests: List<RunningBest>): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.upsertAll(bests.map { it.toEntity() }) }
    }

    override suspend fun delete(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.deleteById(id) }
    }
}
