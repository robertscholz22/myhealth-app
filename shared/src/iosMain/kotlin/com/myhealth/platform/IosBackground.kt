package com.myhealth.platform

import com.myhealth.data.applehealth.HealthKitTypes
import com.myhealth.di.IosAppGraph
import com.myhealth.sync.InProcessSyncScheduler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.HealthKit.HKObserverQuery
import platform.HealthKit.HKUpdateFrequencyHourly
import platform.HealthKit.enableBackgroundDeliveryForType

/**
 * Work while the app is not on screen (P22.4) — the iOS stand-in for the WorkManager workers:
 * - a `BGAppRefreshTask` the system runs a few times a day: health sync, nutrition targets and
 *   load recompute, all awaited ([InProcessSyncScheduler.syncAndRecomputeNow]);
 * - HealthKit background delivery for workouts and sleep: HealthKit wakes the app (hourly at most)
 *   when Garmin Connect writes a new run or night, and the same run follows.
 * iOS decides when either really happens; opening the app always syncs as well.
 */
@OptIn(ExperimentalForeignApi::class)
object IosBackground {

    /** Must match `BGTaskSchedulerPermittedIdentifiers` in `project.yml`. */
    const val REFRESH_TASK = "io.github.robertscholz22.myhealth.refresh"
    private const val REFRESH_SECONDS = 6 * 60 * 60.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observersStarted = false

    private val scheduler: InProcessSyncScheduler
        get() = IosAppGraph.instance.syncScheduler as InProcessSyncScheduler

    /** Called once from the app's `init`, before launching finishes (a BackgroundTasks rule). */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(REFRESH_TASK, usingQueue = null) { task ->
            task?.let(::runRefresh)
        }
        startHealthObservers()
    }

    /** Asks for the next refresh; called whenever the app goes to the background. */
    fun scheduleRefresh() {
        val request = BGAppRefreshTaskRequest(identifier = REFRESH_TASK).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(REFRESH_SECONDS)
        }
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    private fun runRefresh(task: BGTask) {
        scheduleRefresh()
        val job = scope.launch {
            val ok = runCatching { scheduler.syncAndRecomputeNow() }.getOrDefault(false)
            task.setTaskCompletedWithSuccess(ok)
        }
        task.expirationHandler = {
            job.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    /**
     * Observer queries live only as long as the process, so they are set up on every launch —
     * including a launch HealthKit itself triggered for background delivery. Before the
     * permission sheet was answered nothing is observed.
     */
    fun startHealthObservers() {
        if (observersStarted) return
        val graph = IosAppGraph.instance
        if (!graph.healthKit.isAvailable) return
        scope.launch {
            if (!graph.healthKit.wasAnswered()) return@launch
            observersStarted = true
            val store = graph.healthKit.store
            for (type in listOf(HealthKitTypes.workout, HealthKitTypes.sleep)) {
                val query = HKObserverQuery(sampleType = type, predicate = null) { _, completion, error ->
                    if (error != null) {
                        completion?.invoke()
                    } else {
                        scope.launch {
                            runCatching { scheduler.syncAndRecomputeNow() }
                            completion?.invoke()
                        }
                    }
                }
                store.executeQuery(query)
                store.enableBackgroundDeliveryForType(type, HKUpdateFrequencyHourly) { _, _ -> }
            }
        }
    }
}
