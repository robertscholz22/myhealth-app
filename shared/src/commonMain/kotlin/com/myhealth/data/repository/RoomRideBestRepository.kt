package com.myhealth.data.repository

import com.myhealth.data.db.dao.RideBestDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [RideBestRepository] over `ride_best` (PLAN §2.2.6, P12) — the mirror image of
 * [RoomRunningBestRepository]. Every qualifying effort is kept;
 * [RideBestDao.observeBestPerKind] derives the PR per kind, so this class does no aggregation.
 */
class RoomRideBestRepository(
    private val dao: RideBestDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RideBestRepository {

    override fun observeBestPerKind(): Flow<List<RideBest>> =
        dao.observeBestPerKind().map { rows -> rows.map { it.toDomain() } }

    override fun observeByKind(kind: RideBestKind, limit: Int): Flow<List<RideBest>> =
        dao.observeByKind(kind, limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getForActivity(activityId: Long): List<RideBest> =
        withContext(ioDispatcher) { dao.getByActivity(activityId).map { it.toDomain() } }

    override suspend fun getSince(day: Long): List<RideBest> =
        withContext(ioDispatcher) { dao.getSince(day).map { it.toDomain() } }

    /** Clears the ride's rows first so a kind the new computation no longer reaches is not left
     * behind as a stale PR (same contract as [RoomRunningBestRepository.replaceForActivity]). */
    override suspend fun replaceForActivity(activityId: Long, bests: List<RideBest>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                dao.deleteByActivity(activityId)
                dao.upsertAll(bests.map { it.toEntity() })
            }
        }

    override suspend fun upsertAll(bests: List<RideBest>): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.upsertAll(bests.map { it.toEntity() }) }
    }

    override suspend fun delete(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { dao.deleteById(id) }
    }
}
