package com.myhealth.domain.repository

import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `daily_load` (PLAN §2.2.6) — the cached ATL/CTL/ACWR/recovery series (§3.2, §3.3, P5.5).
 *
 * [recomputeFrom] rebuilds `[fromDay − 28, today]` (the EWMA prefix) and is idempotent: running
 * it twice produces identical rows.
 */
interface LoadRepository {

    fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyLoad>>

    fun observeLatest(): Flow<DailyLoad?>

    suspend fun getRange(fromDay: Long, toDay: Long): List<DailyLoad>

    suspend fun getLatest(): DailyLoad?

    suspend fun recomputeFrom(fromDay: Long): Outcome<Unit>

    suspend fun upsertAll(days: List<DailyLoad>): Outcome<Unit>

    /** POLISH-13: deletes cached rows before [beforeDay] (the first activity day, or
     * `Long.MAX_VALUE` to clear everything when there are no activities). */
    suspend fun deleteBefore(beforeDay: Long): Outcome<Unit>
}
