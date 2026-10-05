package com.myhealth.data.healthconnect

import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.repository.SyncKeys
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.delay

/** What one [HcBackfill.run] call managed to pull in. */
data class BackfillResult(
    val windows: Int = 0,
    val activitiesInserted: Int = 0,
    val activitiesMerged: Int = 0,
    val daysUpdated: Int = 0,
    val sleepUpdated: Int = 0,
    val bodyUpdated: Int = 0,
    /** Oldest epoch day already backfilled — where a later run resumes. */
    val completeDay: Long? = null,
    /** Oldest day an exercise record was ingested for — the load recompute start (BUG-5). */
    val minAffectedDay: Long? = null,
    val finished: Boolean = false,
)

/**
 * Historical import of everything older than the 30-day live window (PLAN P2.6).
 *
 * Requires `READ_HEALTH_DATA_HISTORY`: without it Health Connect simply returns nothing older than
 * 30 days, so the run is refused up front instead of silently writing an empty history (R5).
 *
 * Reads in 14-day windows, newest → oldest, with a courtesy delay between windows (R6). The
 * watermark `sync_state.backfillCompleteDay` is persisted after **every** window, so a run that is
 * cancelled or fails half way resumes at the next window instead of starting over.
 */
class HcBackfill(
    private val sync: HcSyncService,
    private val syncStateRepo: SyncStateRepository,
    /** Whether reading data older than 30 days is allowed (`READ_HEALTH_DATA_HISTORY` on Android). */
    private val historyGranted: suspend () -> Boolean,
    private val clock: PlatformClock,
    private val windowDelayMillis: Long = WINDOW_DELAY_MILLIS,
) {

    suspend fun run(fromDay: Long): Outcome<BackfillResult> {
        if (!historyGranted()) {
            val message = "READ_HEALTH_DATA_HISTORY not granted"
            syncStateRepo.recordError(SyncKeys.HC_EXERCISE, clock.millis(), message)
            return Outcome.Err(AppError.HealthConnectPermissionDenied)
        }

        val today = clock.todayEpochDay()
        val watermark = syncStateRepo.get(SyncKeys.HC_EXERCISE)?.backfillCompleteDay
        var toDay = if (watermark != null) minOf(today, watermark - 1) else today
        var result = BackfillResult(completeDay = watermark)

        while (toDay >= fromDay) {
            val windowStart = maxOf(fromDay, toDay - (WINDOW_DAYS - 1))
            when (val window = readWindow(windowStart, toDay)) {
                is Outcome.Ok -> result = result.accumulate(window.value, windowStart)
                is Outcome.Err -> {
                    syncStateRepo.recordError(
                        SyncKeys.HC_EXERCISE,
                        clock.millis(),
                        window.error.describe(),
                    )
                    return window
                }
            }
            syncStateRepo.setBackfillCompleteDay(SyncKeys.HC_EXERCISE, windowStart)
            toDay = windowStart - 1
            if (toDay >= fromDay) delay(windowDelayMillis)
        }

        syncStateRepo.recordSuccess(SyncKeys.HC_EXERCISE, clock.millis())
        return Outcome.Ok(result.copy(finished = true))
    }

    /** All four channels for one window; the first failure aborts the window (progress is kept). */
    private suspend fun readWindow(fromDay: Long, toDay: Long): Outcome<SyncSummary> {
        var summary = SyncSummary()
        for (read in reads) {
            when (val outcome = read(fromDay, toDay)) {
                is Outcome.Ok -> summary += outcome.value
                is Outcome.Err -> return outcome
            }
        }
        return Outcome.Ok(summary)
    }

    private val reads: List<suspend (Long, Long) -> Outcome<SyncSummary>> = listOf(
        { from, to -> sync.readExercise(from, to) },
        { from, to -> sync.readDaily(from, to) },
        { from, to -> sync.readSleep(from, to) },
        { from, to -> sync.readBody(from, to) },
    )

    private fun BackfillResult.accumulate(window: SyncSummary, windowStart: Long) = copy(
        windows = windows + 1,
        activitiesInserted = activitiesInserted + window.activitiesInserted,
        activitiesMerged = activitiesMerged + window.activitiesMerged,
        daysUpdated = daysUpdated + window.daysUpdated,
        sleepUpdated = sleepUpdated + window.sleepUpdated,
        bodyUpdated = bodyUpdated + window.bodyUpdated,
        completeDay = windowStart,
        minAffectedDay = minOfDays(minAffectedDay, window.minAffectedDay),
    )

    companion object {
        /** Window size (P2.6). Small enough to stay well inside Health Connect's read limits. */
        const val WINDOW_DAYS: Long = 14L

        /** Courtesy pause between windows — Health Connect's rate limits are undocumented (R6). */
        const val WINDOW_DELAY_MILLIS: Long = 250L
    }
}
