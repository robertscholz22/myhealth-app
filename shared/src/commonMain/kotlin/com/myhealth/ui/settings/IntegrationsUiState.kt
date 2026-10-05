package com.myhealth.ui.settings


import com.myhealth.di.HcStatus
import com.myhealth.domain.repository.SyncKeys

/** One row of the granted-permission list (§4.2 Integrations). */
data class PermissionRow(
    val permission: String,
    val label: String,
    val granted: Boolean,
)

/** One Health Connect sync channel's last outcome (`sync_state`, keyed by [SyncKeys][com.myhealth.domain.repository.SyncKeys]). */
data class SyncChannelRow(
    val key: String,
    val label: String,
    val lastSuccessAtMillis: Long?,
    val lastError: String?,
)

/** ViewModel state for [IntegrationsScreen] (PLAN §4.2 Integrations, P2.8). */
data class IntegrationsUiState(
    val status: HcStatus = HcStatus.UNAVAILABLE,
    val permissions: List<PermissionRow> = emptyList(),
    val isSyncing: Boolean = false,
    val syncChannels: List<SyncChannelRow> = emptyList(),
    val backfillStartDay: Long = 0L,
    val backfillCompleteDay: Long? = null,
    val isBackfillRunning: Boolean = false,
)

/**
 * Human-readable name for a Health Connect permission string (§4.2). Every permission in
 * `HcPermissions.ALL` (`data/healthconnect`) has a curated entry here; anything unrecognised
 * falls back to a title-cased tail of the permission string so the label is never blank.
 */
fun permissionLabel(permission: String): String =
    KNOWN_PERMISSION_LABELS[permission] ?: fallbackPermissionLabel(permission)

private val KNOWN_PERMISSION_LABELS: Map<String, String> = mapOf(
    "android.permission.health.READ_EXERCISE" to "Workouts and cycling cadence",
    "android.permission.health.READ_STEPS" to "Steps",
    "android.permission.health.READ_DISTANCE" to "Distance",
    "android.permission.health.READ_SPEED" to "Speed",
    "android.permission.health.READ_STEPS_CADENCE" to "Step cadence",
    "android.permission.health.READ_HEART_RATE" to "Heart rate",
    "android.permission.health.READ_RESTING_HEART_RATE" to "Resting heart rate",
    "android.permission.health.READ_HEART_RATE_VARIABILITY" to "Heart rate variability",
    "android.permission.health.READ_SLEEP" to "Sleep",
    "android.permission.health.READ_WEIGHT" to "Weight",
    "android.permission.health.READ_BODY_FAT" to "Body fat",
    "android.permission.health.READ_TOTAL_CALORIES_BURNED" to "Total calories burned",
    "android.permission.health.READ_ACTIVE_CALORIES_BURNED" to "Active calories burned",
    "android.permission.health.READ_FLOORS_CLIMBED" to "Floors climbed",
    "android.permission.health.READ_ELEVATION_GAINED" to "Elevation gained",
    "android.permission.health.READ_OXYGEN_SATURATION" to "Blood oxygen",
    "android.permission.health.READ_RESPIRATORY_RATE" to "Respiratory rate",
    "android.permission.health.READ_VO2_MAX" to "VO2 max",
    // P12. Cycling cadence has no permission of its own — CyclingPedalingCadenceRecord maps to
    // READ_EXERCISE ("Workouts"), so only power gets a row here.
    "android.permission.health.READ_POWER" to "Power",
    "android.permission.health.READ_HEALTH_DATA_HISTORY" to "Full history (30+ days)",
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" to "Background sync",
)

private fun fallbackPermissionLabel(permission: String): String {
    val tail = permission.substringAfterLast('.').removePrefix("READ_")
    val words = tail.split('_').filter { it.isNotBlank() }
    return words.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
        .ifBlank { permission }
}

/** Human-readable name for one of [SyncKeys]' `HC_*` channels (§4.2). */
fun syncChannelLabel(key: String): String = when (key) {
    SyncKeys.HC_EXERCISE -> "Workouts"
    SyncKeys.HC_DAILY -> "Daily activity"
    SyncKeys.HC_SLEEP -> "Sleep"
    SyncKeys.HC_BODY -> "Body measurements"
    else -> key
}

/**
 * The per-session [detail] permissions (P12: power) that [after] holds and [before] did not.
 * Empty when [before] is empty — the granted set has not been loaded yet, so nothing can be
 * called "new" (an ordinary first load must not start a re-read).
 */
fun newlyGrantedDetailPermissions(
    before: Set<String>,
    after: Set<String>,
    detail: Set<String>,
): Set<String> {
    if (before.isEmpty()) return emptySet()
    return (after - before).intersect(detail)
}
