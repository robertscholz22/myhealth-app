package com.myhealth.domain.repository

import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `running_best` (PLAN §2.2.6, §3.4, P5.4/P5.5/P5.7). Every qualifying effort is kept; the PR per
 * distance is derived (`MIN(timeSec)`), so [observeBestPerDistance] is the PR table.
 */
interface RunningBestRepository {

    fun observeBestPerDistance(): Flow<List<RunningBest>>

    fun observeByDistance(distanceMeters: Double, limit: Int): Flow<List<RunningBest>>

    /**
     * P19.1: **every** effort since [fromDay], not just the PRs. Recent form (VDOT, race
     * predictions, goal progress) must come from here — a recent effort that is slower than an
     * old PR is still the athlete's current form.
     */
    fun observeSince(fromDay: Long): Flow<List<RunningBest>>

    suspend fun getForActivity(activityId: Long): List<RunningBest>

    /** Idempotent refresh after a run is (re-)ingested: deletes the activity's rows, inserts these. */
    suspend fun replaceForActivity(activityId: Long, bests: List<RunningBest>): Outcome<Unit>

    suspend fun upsertAll(bests: List<RunningBest>): Outcome<Unit>

    suspend fun delete(id: Long): Outcome<Unit>
}
