package com.myhealth.domain.repository

import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `daily_health_summary` and `sleep_session` (PLAN §2.2.3) — the non-exercise side of Health
 * Connect. Sleep is keyed by the `night` epoch day it is attributed to, not by start time.
 */
interface HealthRepository {

    fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyHealthSummary>>

    fun observeDay(day: Long): Flow<DailyHealthSummary?>

    fun observeLatest(): Flow<DailyHealthSummary?>

    suspend fun getDay(day: Long): DailyHealthSummary?

    suspend fun upsertAll(summaries: List<DailyHealthSummary>): Outcome<Unit>

    /**
     * Removes daily rows that carry nothing but a total-energy value — the basal baseline Health
     * Connect reports for days no app wrote to. Returns how many rows were deleted.
     */
    suspend fun deleteEmptySummaries(): Outcome<Int>

    // ---- sleep ---------------------------------------------------------------------------------

    fun observeSleepRange(fromNight: Long, toNight: Long): Flow<List<SleepRecord>>

    fun observeLatestSleep(): Flow<SleepRecord?>

    suspend fun getSleep(night: Long): SleepRecord?

    suspend fun upsertSleep(records: List<SleepRecord>): Outcome<Unit>

    /** Health Connect deleted the session behind this row (P2.6); a no-op when it is unknown. */
    suspend fun deleteSleepByExternalId(source: ActivitySource, externalId: String): Outcome<Unit>
}
