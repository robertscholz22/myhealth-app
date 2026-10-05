package com.myhealth.domain.repository

import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `ride_best` (PLAN §2.2.6, P12) — the cycling counterpart of [RunningBestRepository].
 *
 * Every qualifying effort is kept; the PR per kind is derived (`MAX(value)` for the `POWER_*`
 * kinds, `MIN(value)` for the `TIME_*` kinds), so [observeBestPerKind] is the PR table.
 */
interface RideBestRepository {

    fun observeBestPerKind(): Flow<List<RideBest>>

    /** The best [limit] efforts of one kind, best first. */
    fun observeByKind(kind: RideBestKind, limit: Int): Flow<List<RideBest>>

    suspend fun getForActivity(activityId: Long): List<RideBest>

    /** Every effort on or after [day] — the look-back window the FTP estimate reads (P12.2). */
    suspend fun getSince(day: Long): List<RideBest>

    /** Idempotent refresh after a ride is (re-)ingested: deletes the ride's rows, inserts these. */
    suspend fun replaceForActivity(activityId: Long, bests: List<RideBest>): Outcome<Unit>

    suspend fun upsertAll(bests: List<RideBest>): Outcome<Unit>

    suspend fun delete(id: Long): Outcome<Unit>
}
