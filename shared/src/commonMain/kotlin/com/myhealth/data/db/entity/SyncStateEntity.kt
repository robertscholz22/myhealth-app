package com.myhealth.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * `sync_state` (PLAN §2.2.3) — one row per sync channel, keyed by [key].
 *
 * Known keys: `hc.exercise`, `hc.daily`, `hc.sleep`, `hc.body`, `targets.recompute`,
 * `load.recompute` (see [Keys]).
 */
@Serializable
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    /** Health Connect changes token; null forces a full backfill read. */
    val changesToken: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastErrorAtMillis: Long? = null,
    val lastError: String? = null,
    /** Oldest epoch day already backfilled. */
    val backfillCompleteDay: Long? = null,
) {
    object Keys {
        const val HC_EXERCISE = "hc.exercise"
        const val HC_DAILY = "hc.daily"
        const val HC_SLEEP = "hc.sleep"
        const val HC_BODY = "hc.body"
        const val TARGETS_RECOMPUTE = "targets.recompute"
        const val LOAD_RECOMPUTE = "load.recompute"
    }
}
