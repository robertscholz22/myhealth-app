package com.myhealth.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * [SyncScheduler] on WorkManager: schedules and observes [HealthSyncWorker] runs (PLAN P2.7). Three unique work names so the
 * periodic sync, an on-demand "Sync now" and a backfill never collide with each other:
 * - [PERIODIC_NAME]: recurring incremental sync, re-enqueued with [ExistingPeriodicWorkPolicy.UPDATE]
 *   whenever the user changes the sync interval.
 * - [NOW_NAME]: one-shot "Sync now", [ExistingWorkPolicy.KEEP] — a tap while one is already running
 *   or queued is a no-op rather than a pile-up.
 * - [BACKFILL_NAME]: one-shot historical backfill, [ExistingWorkPolicy.REPLACE] — starting a new
 *   backfill (e.g. a different start date) supersedes one in flight.
 *
 * It also owns the two [TargetRecomputeWorker] schedules of P4.12:
 * - [TARGETS_DAILY_NAME]: every 24 h, first run at the next 03:00 local.
 * - [TARGETS_NOW_NAME]: one-shot with a 30 s initial delay and [ExistingWorkPolicy.REPLACE], so a
 *   burst of writes (a profile save that also logs a weight, a plan edit per session) collapses
 *   into a single recompute.
 */
class WorkManagerSyncScheduler(
    private val workManager: WorkManager,
    private val clock: Clock = Clock.systemDefaultZone(),
) : SyncScheduler {

    override fun schedulePeriodic(intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            intervalHours.toLong().coerceAtLeast(1L),
            TimeUnit.HOURS,
        )
            .setConstraints(batteryNotLowConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(batteryNotLowConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun backfill(fromDay: Long) {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInputData(workDataOf(HealthSyncWorker.KEY_BACKFILL_FROM_DAY to fromDay))
            .setConstraints(batteryNotLowConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(BACKFILL_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Re-reads the last [days] days of exercise sessions with their detail streams (P12): run
     * when a per-session permission such as `READ_POWER` is granted after the sessions were
     * already synced. [ExistingWorkPolicy.REPLACE] — a second grant supersedes a queued run.
     */
    override fun rereadExerciseDetail(days: Long) {
        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setInputData(workDataOf(HealthSyncWorker.KEY_REREAD_DAYS to days))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(REREAD_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Nutrition targets are recomputed once a day (PLAN P4.12): `[today - 1, today + 7]`, first run
     * at the next 03:00 local so a new day's targets exist before the user wakes up.
     */
    override fun scheduleDailyTargetRecompute() {
        val request = PeriodicWorkRequestBuilder<TargetRecomputeWorker>(
            TARGET_RECOMPUTE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(minutesUntilNext(TARGET_RECOMPUTE_HOUR), TimeUnit.MINUTES)
            .setConstraints(batteryNotLowConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            TARGETS_DAILY_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Requests a target recompute after a write that can change one (profile, weight, planned
     * session, event, Health Connect sync). [ExistingWorkPolicy.REPLACE] plus the
     * [TARGET_RECOMPUTE_DEBOUNCE_SECONDS] initial delay is the debounce: each new request pushes
     * the run out again, so only the last one in a burst actually executes.
     */
    override fun requestTargetRecompute() {
        val request = OneTimeWorkRequestBuilder<TargetRecomputeWorker>()
            .setInitialDelay(TARGET_RECOMPUTE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(TARGETS_NOW_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** State of the last/current target recompute request. */
    override fun observeTargetRecomputeState(): Flow<SyncWorkState> = observeUniqueWork(TARGETS_NOW_NAME)

    /**
     * `daily_load` is also rebuilt once a day (PLAN P5.5), independently of the targets schedule:
     * `[today - 28, today]` at the next [LOAD_RECOMPUTE_HOUR]:00 local.
     */
    override fun scheduleDailyLoadRecompute() {
        val request = PeriodicWorkRequestBuilder<LoadRecomputeWorker>(
            LOAD_RECOMPUTE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(minutesUntilNext(LOAD_RECOMPUTE_HOUR), TimeUnit.MINUTES)
            .setConstraints(batteryNotLowConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(LOAD_DAILY_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Requests a load/recovery recompute after a write that can change one: an activity ingest, an
     * RPE edit, or a Health Connect sync.
     *
     * [ExistingWorkPolicy.APPEND_OR_REPLACE], **not** `REPLACE` (BUG-14): `REPLACE` cancels a run
     * that is already executing, and a recompute over years of freshly imported history takes
     * minutes — the Health Connect backfill that followed the owner's 600-row import requested its
     * own, much later `fromDay` and thereby cancelled the historical run, leaving every activity
     * before the backfill window without TRIMP. Appending chains the new request behind the one in
     * flight (or replaces it only when the previous chain failed or was cancelled), so the earliest
     * `fromDay` is always completed. A burst still costs one short extra run per request instead of
     * a single debounced run, which is cheap for the usual 28-day window.
     *
     * @param fromDay the earliest day known to be affected; the worker rebuilds
     *   `[fromDay − 28, today]` (the EWMA prefix `LoadSeriesEngine` needs).
     */
    override fun requestLoadRecompute(fromDay: Long) {
        val request = OneTimeWorkRequestBuilder<LoadRecomputeWorker>()
            .setInputData(workDataOf(LoadRecomputeWorker.KEY_FROM_DAY to fromDay))
            .setInitialDelay(LOAD_RECOMPUTE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(LOAD_NOW_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** State of the last/current load recompute request. */
    override fun observeLoadRecomputeState(): Flow<SyncWorkState> = observeUniqueWork(LOAD_NOW_NAME)

    /**
     * Starts one FIT/CSV/ZIP import (P7.5). [ExistingWorkPolicy.REPLACE] so picking a second file
     * while one is running supersedes it rather than queueing behind it, and no battery constraint:
     * the user is standing in front of the screen waiting for this one.
     */
    override fun startImport(uri: String, kind: String, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                workDataOf(
                    ImportWorker.KEY_URI to uri,
                    ImportWorker.KEY_KIND to kind,
                    ImportWorker.KEY_FORCE to force,
                ),
            )
            .build()
        workManager.enqueueUniqueWork(IMPORT_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Counts and outcome of the last/current import, for the Import screen (P7.6). */
    override fun observeImportState(): Flow<ImportWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(IMPORT_NAME).map { ImportWorker.stateOf(it) }

    /** Clears a finished import so its summary stops being shown (e.g. after "Import another"). */
    override fun clearImportState() {
        workManager.cancelUniqueWork(IMPORT_NAME)
    }

    /**
     * Whole minutes from "now" (off the injected [clock]) to the next occurrence of [hour]`:00`
     * local. Never 0 — a delay of 0 would run the periodic work immediately on every reschedule.
     */
    internal fun minutesUntilNext(hour: Int): Long = minutesUntilNext(clock, hour)

    /** State of the last/current "Sync now" run. */
    override fun observeState(): Flow<SyncWorkState> = observeUniqueWork(NOW_NAME)

    /** State of the last/current backfill run. */
    override fun observeBackfillState(): Flow<SyncWorkState> = observeUniqueWork(BACKFILL_NAME)

    private fun observeUniqueWork(uniqueName: String): Flow<SyncWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName).map { it.toWorkState() }

    private fun List<WorkInfo>.toWorkState(): SyncWorkState {
        if (any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }) {
            return SyncWorkState.Running
        }
        val failed = firstOrNull { it.state == WorkInfo.State.FAILED }
        if (failed != null) {
            return SyncWorkState.Failed(failed.outputData.getString(HealthSyncWorker.KEY_FAILURE_REASON))
        }
        return SyncWorkState.Idle
    }

    private fun batteryNotLowConstraints(): Constraints =
        Constraints.Builder().setRequiresBatteryNotLow(true).build()

    companion object {
        const val PERIODIC_NAME: String = "hc_sync"
        const val NOW_NAME: String = "hc_sync_now"
        const val BACKFILL_NAME: String = "hc_backfill"
        const val REREAD_NAME: String = "hc_reread_detail"
        const val TARGETS_DAILY_NAME: String = "targets_daily"
        const val TARGETS_NOW_NAME: String = "targets_now"
        const val IMPORT_NAME: String = "file_import"

        /** WorkManager's minimum allowed backoff delay. */
        const val BACKOFF_MINUTES: Long = 15L

        const val TARGET_RECOMPUTE_INTERVAL_HOURS: Long = 24L
        const val TARGET_RECOMPUTE_HOUR: Int = 3
        const val TARGET_RECOMPUTE_DEBOUNCE_SECONDS: Long = 30L

        const val LOAD_DAILY_NAME: String = "load_daily"
        const val LOAD_NOW_NAME: String = "load_now"
        const val LOAD_RECOMPUTE_INTERVAL_HOURS: Long = 24L

        /** Staggered from [TARGET_RECOMPUTE_HOUR] so the two nightly jobs do not collide. */
        const val LOAD_RECOMPUTE_HOUR: Int = 4
        const val LOAD_RECOMPUTE_DEBOUNCE_SECONDS: Long = 10L

        /**
         * Pure form of [minutesUntilNext], so the 03:00 schedule can be unit-tested against a
         * fixed clock without WorkManager.
         */
        internal fun minutesUntilNext(clock: Clock, hour: Int): Long {
            val now = clock.instant().atZone(clock.zone)
            var next = now.with(LocalTime.of(hour, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return maxOf(1L, Duration.between(now, next).toMinutes())
        }
    }
}
