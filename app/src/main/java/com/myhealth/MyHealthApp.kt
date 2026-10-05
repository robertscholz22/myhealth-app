package com.myhealth

import android.app.Application
import androidx.work.Configuration
import com.myhealth.di.AppGraph

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
        // Import repair, sync and recompute schedules, the start-up load recompute (CoreGraph).
        graph.start()
    }
}
