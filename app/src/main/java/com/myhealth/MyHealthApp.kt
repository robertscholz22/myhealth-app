package com.myhealth

import android.app.Application
import androidx.work.Configuration
import com.myhealth.di.AppGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Application entry point. Owns the single [AppGraph] instance for the process.
 * Workers reach it through `(applicationContext as MyHealthApp).graph` (§1.3).
 *
 * Implements [Configuration.Provider] instead of registering a `WorkerFactory` (§1.3, P2.7):
 * [HealthSyncWorker][com.myhealth.sync.HealthSyncWorker] pulls the graph from this class directly,
 * so the default on-demand `WorkManager.getInstance` initialization is fine as-is. The default
 * `androidx.startup` initializer is disabled in the manifest so WorkManager is only ever touched
 * through [AppGraph.syncScheduler].
 */
class MyHealthApp : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)

        // BUG-19: shrink oversized import error lists (≤ 0.9.1) before any screen reads them.
        graph.appScope.launch { graph.importRepo.repairOversizedErrors() }

        // Schedule (and re-schedule on change) the periodic sync at the user's configured
        // interval (P2.7) — an app-lifetime subscription, not a one-shot background task.
        graph.settings.settings
            .map { it.syncIntervalHours }
            .distinctUntilChanged()
            .onEach { hours -> graph.syncScheduler.schedulePeriodic(hours) }
            .launchIn(graph.appScope)

        // Nutrition targets for [today - 1, today + 7], daily at 03:00 local (P4.12).
        graph.syncScheduler.scheduleDailyTargetRecompute()

        // TRIMP / ACWR / recovery for the last 28 days, daily at 04:00 local (P5.5).
        graph.syncScheduler.scheduleDailyLoadRecompute()

        // BUG-18: one recompute per app start. It runs the EWMAs over the whole history and repairs
        // any stored row that disagrees (zero-seeded rows from ≤ 0.9.0), so a training plan generated
        // right after an update already sees the right CTL. Cheap: one pass over the daily sums.
        graph.syncScheduler.requestLoadRecompute(LocalDate.now().toEpochDay())
    }
}
