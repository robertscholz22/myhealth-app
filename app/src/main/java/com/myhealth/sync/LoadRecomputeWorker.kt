package com.myhealth.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myhealth.MyHealthApp
import com.myhealth.domain.util.Outcome
import java.time.LocalDate

/**
 * `CoroutineWorker` driving [com.myhealth.data.repository.LoadRecomputeService] (PLAN P5.5). Reads
 * the graph from `(applicationContext as MyHealthApp).graph` (§1.3) like [HealthSyncWorker] and
 * [TargetRecomputeWorker] — no `WorkerFactory` is registered.
 *
 * Scheduled two ways by [SyncScheduler]: a nightly full-window run
 * ([SyncScheduler.scheduleDailyLoadRecompute]) and a debounced on-demand request after any activity
 * ingest, Health Connect sync or RPE edit ([SyncScheduler.requestLoadRecompute]).
 */
class LoadRecomputeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as MyHealthApp).graph
        val today = LocalDate.now(graph.clock).toEpochDay()
        val fromDay = inputData.getLong(KEY_FROM_DAY, today)

        return when (graph.loadRecomputeService.recompute(fromDay)) {
            is Outcome.Ok -> Result.success()
            is Outcome.Err -> Result.retry()
        }
    }

    companion object {
        /** Input key: the earliest day the caller knows was affected. */
        const val KEY_FROM_DAY: String = "fromDay"
    }
}
