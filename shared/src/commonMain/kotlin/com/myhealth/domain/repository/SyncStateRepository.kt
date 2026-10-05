package com.myhealth.domain.repository

import com.myhealth.domain.model.SyncState
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/** The `sync_state.key` values used by the app (PLAN §2.2.3, P2.6, P4.12, P5.5). */
object SyncKeys {
    const val HC_EXERCISE = "hc.exercise"
    const val HC_DAILY = "hc.daily"
    const val HC_SLEEP = "hc.sleep"
    const val HC_BODY = "hc.body"
    const val TARGETS_RECOMPUTE = "targets.recompute"
    const val LOAD_RECOMPUTE = "load.recompute"
}

/**
 * `sync_state` (PLAN §2.2.3) — one row per sync channel, holding the Health Connect changes
 * token, the last success/error and the resumable backfill watermark (P2.6).
 */
interface SyncStateRepository {

    fun observeAll(): Flow<List<SyncState>>

    fun observe(key: String): Flow<SyncState?>

    suspend fun get(key: String): SyncState?

    /** `token = null` clears it — the token-expiry path (P2.6) does exactly that. */
    suspend fun setChangesToken(key: String, token: String?): Outcome<Unit>

    suspend fun recordSuccess(key: String, atMillis: Long): Outcome<Unit>

    suspend fun recordError(key: String, atMillis: Long, message: String): Outcome<Unit>

    suspend fun setBackfillCompleteDay(key: String, day: Long): Outcome<Unit>

    suspend fun upsert(state: SyncState): Outcome<Unit>
}
