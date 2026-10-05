package com.myhealth.data.repository

import com.myhealth.data.db.dao.SyncStateDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.SyncState
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [SyncStateRepository] (PLAN P2.5) over `sync_state`.
 *
 * Every mutator is read-modify-write on the whole row rather than a targeted `UPDATE`: the row
 * may not exist yet (the first sync of a channel creates it), and the four fields are written by
 * different call sites of [com.myhealth.data.healthconnect.HcSyncService] that must not erase one
 * another — clearing an expired changes token must keep the backfill watermark, and recording an
 * error must keep the last success.
 */
class RoomSyncStateRepository(
    private val syncStateDao: SyncStateDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncStateRepository {

    override fun observeAll(): Flow<List<SyncState>> =
        syncStateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observe(key: String): Flow<SyncState?> =
        syncStateDao.observe(key).map { it?.toDomain() }

    override suspend fun get(key: String): SyncState? =
        withContext(ioDispatcher) { syncStateDao.getById(key)?.toDomain() }

    override suspend fun setChangesToken(key: String, token: String?): Outcome<Unit> =
        update(key) { it.copy(changesToken = token) }

    override suspend fun recordSuccess(key: String, atMillis: Long): Outcome<Unit> =
        update(key) { it.copy(lastSuccessAtMillis = atMillis, lastError = null, lastErrorAtMillis = null) }

    override suspend fun recordError(key: String, atMillis: Long, message: String): Outcome<Unit> =
        update(key) { it.copy(lastErrorAtMillis = atMillis, lastError = message) }

    override suspend fun setBackfillCompleteDay(key: String, day: Long): Outcome<Unit> =
        update(key) { it.copy(backfillCompleteDay = day) }

    override suspend fun upsert(state: SyncState): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { syncStateDao.upsert(state.toEntity()) } }

    private suspend fun update(key: String, change: (SyncState) -> SyncState): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val current = syncStateDao.getById(key)?.toDomain() ?: empty(key)
                syncStateDao.upsert(change(current).toEntity())
            }
        }

    private fun empty(key: String) = SyncState(
        key = key,
        changesToken = null,
        lastSuccessAtMillis = null,
        lastErrorAtMillis = null,
        lastError = null,
        backfillCompleteDay = null,
    )
}
