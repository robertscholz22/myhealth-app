package com.myhealth.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myhealth.MyHealthApp
import com.myhealth.domain.util.Outcome
import java.time.LocalDate

/**
 * `CoroutineWorker` driving Health Connect sync (PLAN P2.7). The graph is pulled from
 * `(applicationContext as MyHealthApp).graph` (§1.3) — no `WorkerFactory` is registered.
 *
 * With [KEY_BACKFILL_FROM_DAY] present in the input data, [com.myhealth.data.healthconnect.HcBackfill]
 * runs for that start day instead of the incremental sync — this is how "Sync now" and "Start
 * backfill" share one worker. With [KEY_REREAD_DAYS] the last N days of exercise sessions are
 * re-read with their detail streams (a newly granted per-session permission, P12).
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as MyHealthApp).graph
        val backfillFromDay = inputData.getLong(KEY_BACKFILL_FROM_DAY, NO_BACKFILL).takeIf { it >= 0 }

        val rereadDays = inputData.getLong(KEY_REREAD_DAYS, NO_BACKFILL).takeIf { it >= 0 }

        val outcome: Outcome<*> = if (backfillFromDay != null) {
            val backfill = graph.hcBackfill
                ?: return Result.failure(failureData("Health Connect unavailable"))
            backfill.run(backfillFromDay)
        } else if (rereadDays != null) {
            val sync = graph.hcSync ?: return Result.failure(failureData("Health Connect unavailable"))
            sync.rereadExerciseDetail(rereadDays)
        } else {
            val sync = graph.hcSync ?: return Result.failure(failureData("Health Connect unavailable"))
            sync.syncIncremental()
        }

        return when (val verdict = mapOutcome(outcome)) {
            WorkerVerdict.Success -> {
                // Fresh activities / daily totals change the measured TDEE, so the day's targets
                // have to be re-derived (P4.12); the request is debounced by 30 s.
                graph.syncScheduler.requestTargetRecompute()
                // New or changed activities/HR/sleep also change TRIMP, ACWR and recovery (P5.5).
                val today = LocalDate.now(graph.clock).toEpochDay()
                graph.syncScheduler.requestLoadRecompute(
                    loadRecomputeDay(outcome, backfillFromDay ?: rereadDays?.let { today - it }, today),
                )
                Result.success()
            }
            WorkerVerdict.Retry -> Result.retry()
            is WorkerVerdict.Failure -> Result.failure(failureData(verdict.reason))
        }
    }

    private fun failureData(reason: String): Data = workDataOf(KEY_FAILURE_REASON to reason)


    companion object {
        /** Input key: epoch day to backfill from. Absent for the ordinary incremental sync. */
        const val KEY_BACKFILL_FROM_DAY: String = "backfillFromDay"

        /**
         * Input key: number of days of exercise sessions to re-read with their detail streams
         * (P12, after a new per-session permission such as `READ_POWER` was granted).
         */
        const val KEY_REREAD_DAYS: String = "rereadDays"

        /** Output key: a human-readable reason, set whenever the worker returns `Result.failure()`. */
        const val KEY_FAILURE_REASON: String = "reason"

        private const val NO_BACKFILL: Long = -1L
    }
}
