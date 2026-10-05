package com.myhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.data.time.today
import com.myhealth.di.HcIntegration
import com.myhealth.domain.repository.SyncKeys
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.domain.util.minusDays
import com.myhealth.sync.SyncScheduler
import com.myhealth.sync.SyncWorkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.myhealth.data.time.PlatformClock
import kotlinx.datetime.LocalDate

/** The four Health Connect channels shown in the Integrations screen's sync list (§4.2). */
private val HC_SYNC_KEYS = listOf(
    SyncKeys.HC_EXERCISE,
    SyncKeys.HC_DAILY,
    SyncKeys.HC_SLEEP,
    SyncKeys.HC_BODY,
)

/** How far back "Start backfill" defaults to (PLAN P2.8). */
private const val DEFAULT_BACKFILL_DAYS = 365L

/**
 * Backs [IntegrationsScreen] (PLAN P2.8): SDK status, granted permissions, sync-now and the
 * historical backfill. [hc] and [syncScheduler] are the only Health-Connect/WorkManager surface
 * this file touches — everything else is [SyncStateRepository] (`sync_state`).
 */
class IntegrationsViewModel(
    private val hc: HcIntegration,
    private val syncStateRepo: SyncStateRepository,
    private val syncScheduler: SyncScheduler,
    clock: PlatformClock,
) : ViewModel() {

    /** Every permission the "Grant permissions" button asks for. */
    val allPermissions: Set<String> get() = hc.allPermissions

    private val granted = MutableStateFlow<Set<String>>(emptySet())
    private val backfillStartDay =
        MutableStateFlow(clock.today().minusDays(DEFAULT_BACKFILL_DAYS).toEpochDays())

    init {
        refreshGranted()
    }

    val state: StateFlow<IntegrationsUiState> = combine(
        granted,
        syncStateRepo.observeAll(),
        syncScheduler.observeState(),
        syncScheduler.observeBackfillState(),
        backfillStartDay,
    ) { grantedSet, syncStates, syncNowState, backfillState, startDay ->
        IntegrationsUiState(
            status = hc.status(),
            permissions = hc.allPermissions.sorted().map { permission ->
                PermissionRow(permission, permissionLabel(permission), permission in grantedSet)
            },
            isSyncing = syncNowState == SyncWorkState.Running,
            syncChannels = HC_SYNC_KEYS.map { key ->
                val row = syncStates.firstOrNull { it.key == key }
                SyncChannelRow(key, syncChannelLabel(key), row?.lastSuccessAtMillis, row?.lastError)
            },
            backfillStartDay = startDay,
            backfillCompleteDay = syncStates.firstOrNull { it.key == SyncKeys.HC_EXERCISE }?.backfillCompleteDay,
            isBackfillRunning = backfillState == SyncWorkState.Running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntegrationsUiState())

    /**
     * Called after the permission-request activity result comes back. A per-session permission
     * granted for the first time (P12: power) triggers a re-read of the recent sessions, because
     * neither the changes token nor a backfill would ever revisit them.
     */
    fun onPermissionsResult(result: Set<String>) {
        val before = granted.value
        granted.value = result
        if (newlyGrantedDetailPermissions(before, result, hc.optionalDetailPermissions).isNotEmpty()) {
            syncScheduler.rereadExerciseDetail(SyncScheduler.REREAD_DETAIL_DAYS)
        }
    }

    /** Re-reads what Health Connect currently reports as granted (e.g. on first composition). */
    fun refreshGranted() {
        viewModelScope.launch { granted.value = hc.granted() }
    }

    fun syncNow() {
        syncScheduler.syncNow()
    }

    fun setBackfillStartDay(day: Long) {
        backfillStartDay.value = day
    }

    fun startBackfill() {
        syncScheduler.backfill(backfillStartDay.value)
    }
}
