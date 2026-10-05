package com.myhealth.sync

import kotlinx.coroutines.flow.Flow

/** What the Integrations screen (P2.8) shows for one of the scheduler's background jobs. */
sealed interface SyncWorkState {
    data object Idle : SyncWorkState
    data object Running : SyncWorkState
    data class Failed(val reason: String?) : SyncWorkState
}

/** What the Import screen (P7.6) shows for the single import work slot. */
data class ImportWorkState(
    val stage: Stage = Stage.IDLE,
    val parsed: Int = 0,
    val inserted: Int = 0,
    val duplicate: Int = 0,
    val failed: Int = 0,
    val currentItem: String? = null,
    /** Failure reason, or the name of the earlier import a duplicate file matched. */
    val message: String? = null,
) {
    enum class Stage { IDLE, RUNNING, DONE, ALREADY_IMPORTED, FAILED }

    val isRunning: Boolean get() = stage == Stage.RUNNING
}

/**
 * Background work the screens start and observe (PLAN P2.7, P4.12, P5.5, P7.5): Health Connect
 * sync, backfill and detail re-read, the nightly and on-demand target and load recomputes, and
 * file imports. P20.2: the platform seam — WorkManager on Android (`WorkManagerSyncScheduler`),
 * `BGTaskScheduler` on iOS (P22). The work itself (`HcSyncService`, `LoadRecomputeService`,
 * `NutritionRepository.ensureTarget`, `ImportService`) is common code.
 */
interface SyncScheduler {

    /** Recurring incremental sync every [intervalHours]; a new interval replaces the old schedule. */
    fun schedulePeriodic(intervalHours: Int)

    /** One-shot "Sync now"; a tap while one is queued or running is a no-op. */
    fun syncNow()

    /** One-shot historical backfill from [fromDay]; supersedes a backfill in flight. */
    fun backfill(fromDay: Long)

    /**
     * Re-reads the last [days] days of exercise sessions with their detail streams (P12): run
     * when a per-session permission such as `READ_POWER` is granted after the sessions were
     * already synced.
     */
    fun rereadExerciseDetail(days: Long)

    /** Daily target recompute (`[today - 1, today + 7]`) at the next 03:00 local, then every 24 h. */
    fun scheduleDailyTargetRecompute()

    /** Debounced target recompute after a write that can change a target. */
    fun requestTargetRecompute()

    /** State of the last/current target recompute request. */
    fun observeTargetRecomputeState(): Flow<SyncWorkState>

    /** Daily load/recovery recompute at the next 04:00 local, then every 24 h. */
    fun scheduleDailyLoadRecompute()

    /**
     * Load/recovery recompute from [fromDay] after an activity ingest, an RPE edit or a sync. A
     * request never cancels a recompute that is already running (BUG-14): the earliest `fromDay`
     * is always completed.
     */
    fun requestLoadRecompute(fromDay: Long)

    /** State of the last/current load recompute request. */
    fun observeLoadRecomputeState(): Flow<SyncWorkState>

    /** Starts one FIT/CSV/ZIP import of the document [uri]; supersedes an import in flight. */
    fun startImport(uri: String, kind: String, force: Boolean = false)

    /** Counts and outcome of the last/current import, for the Import screen (P7.6). */
    fun observeImportState(): Flow<ImportWorkState>

    /** Clears a finished import so its summary stops being shown (e.g. after "Import another"). */
    fun clearImportState()

    /** State of the last/current "Sync now" run. */
    fun observeState(): Flow<SyncWorkState>

    /** State of the last/current backfill run. */
    fun observeBackfillState(): Flow<SyncWorkState>

    companion object {
        /** How far back a newly granted per-session permission re-reads sessions (P12). */
        const val REREAD_DETAIL_DAYS: Long = 90L
    }
}
