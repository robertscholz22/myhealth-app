package com.myhealth.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.myhealth.MyHealthApp

/**
 * Recomputes the `nutrition_target_snapshot` rows of the `[today - 1, today + 7]` window (PLAN
 * P4.12) by running [TargetRecomputeService] (common code since P20.2); a day whose `inputsHash`
 * is unchanged is skipped — so a run over a window that nothing touched writes nothing.
 *
 * Scheduled two ways by [SyncScheduler]: daily at 03:00 local
 * ([SyncScheduler.scheduleDailyTargetRecompute]) and debounced after any profile / weight / plan /
 * event write and after every Health Connect sync ([SyncScheduler.requestTargetRecompute]).
 *
 * Like [HealthSyncWorker] it reads the graph from `(applicationContext as MyHealthApp).graph`
 * (§1.3) — no `WorkerFactory` is registered.
 */
class TargetRecomputeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val run = (applicationContext as MyHealthApp).graph.targetRecomputeService.recompute()
        return when {
            run.failure != null -> Result.retry()
            else -> Result.success(workDataOf(KEY_DAYS_RECOMPUTED to run.recomputed, KEY_DAYS_SKIPPED to run.skipped))
        }
    }

    companion object {
        /** Output keys, shown by the Integrations screen's diagnostics (P8). */
        const val KEY_DAYS_RECOMPUTED: String = "daysRecomputed"
        const val KEY_DAYS_SKIPPED: String = "daysSkipped"
    }
}
