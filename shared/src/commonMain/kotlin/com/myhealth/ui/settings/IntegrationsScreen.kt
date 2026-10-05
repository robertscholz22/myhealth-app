package com.myhealth.ui.settings

import com.myhealth.di.HealthPlatform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.myhealth.resources.*
import com.myhealth.ui.common.LocalPlatformUi
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.HcStatus
import com.myhealth.di.rememberVm
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.usText
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime


@Composable
fun IntegrationsScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { g -> IntegrationsViewModel(g.hcIntegration, g.syncStateRepo, g.syncScheduler, g.clock) }
    val state by vm.state.collectAsStateWithLifecycle()
    val platform = LocalPlatformUi.current
    val requestPermissions = platform.rememberHealthPermissionRequest { result ->
        vm.onPermissionsResult(result)
        vm.refreshGranted()
    }

    IntegrationsContent(
        state = state,
        onGrantPermissions = { requestPermissions(vm.allPermissions) },
        onOpenPlayStore = platform::openHealthAppStore,
        onOpenHcSettings = platform::openHealthSettings,
        onSyncNow = vm::syncNow,
        onBackfillStartDayChange = vm::setBackfillStartDay,
        onStartBackfill = vm::startBackfill,
        modifier = modifier,
    )
}

@Composable
private fun IntegrationsContent(
    state: IntegrationsUiState,
    onGrantPermissions: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onOpenHcSettings: () -> Unit,
    onSyncNow: () -> Unit,
    onBackfillStartDayChange: (Long) -> Unit,
    onStartBackfill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val isApple = state.platform == HealthPlatform.APPLE_HEALTH
        item { if (isApple) AppleStatusSection(state.status) else StatusSection(state.status, onOpenPlayStore) }
        if (state.status == HcStatus.AVAILABLE) {
            item {
                if (isApple) {
                    AppleAccessSection(
                        connected = state.permissions.any { it.granted },
                        onConnect = onGrantPermissions,
                    )
                } else {
                    PermissionsSection(
                        permissions = state.permissions,
                        onGrantPermissions = onGrantPermissions,
                        onOpenHcSettings = onOpenHcSettings,
                    )
                }
            }
            item { SyncSection(state.isSyncing, state.syncChannels, onSyncNow) }
            item {
                BackfillSection(
                    hint = stringResource(
                        if (isApple) Res.string.integrations_apple_backfill_hint else Res.string.integrations_backfill_requires_history,
                    ),
                    startDay = state.backfillStartDay,
                    completeDay = state.backfillCompleteDay,
                    isRunning = state.isBackfillRunning,
                    onStartDayChange = onBackfillStartDayChange,
                    onStartBackfill = onStartBackfill,
                )
            }
        }
    }
}

@Composable
private fun StatusSection(status: HcStatus, onOpenPlayStore: () -> Unit) {
    SectionCard(title = stringResource(Res.string.integrations_health_connect_title)) {
        when (status) {
            HcStatus.AVAILABLE -> Text(
                stringResource(Res.string.integrations_status_available),
                style = MaterialTheme.typography.bodyMedium,
            )
            HcStatus.UPDATE_REQUIRED -> {
                Text(
                    stringResource(Res.string.integrations_status_update_required),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenPlayStore) { Text(stringResource(Res.string.integrations_action_update_play_store)) }
            }
            HcStatus.UNAVAILABLE -> {
                Text(
                    stringResource(Res.string.integrations_status_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenPlayStore) { Text(stringResource(Res.string.integrations_action_install_play_store)) }
            }
        }
    }
}

@Composable
private fun AppleStatusSection(status: HcStatus) {
    SectionCard(title = stringResource(Res.string.integrations_apple_health_title)) {
        Text(
            stringResource(
                if (status == HcStatus.AVAILABLE) Res.string.integrations_status_available else Res.string.integrations_apple_status_unavailable,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Apple Health hides which read types were allowed, so there are no per-type rows: one connect
 * button (the system sheet) and where to review the choices (P22.1).
 */
@Composable
private fun AppleAccessSection(connected: Boolean, onConnect: () -> Unit) {
    SectionCard(title = stringResource(Res.string.integrations_apple_access_title)) {
        Text(stringResource(Res.string.integrations_apple_access_explainer), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                stringResource(
                    if (connected) Res.string.integrations_apple_access_answered else Res.string.integrations_apple_access_not_yet,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onConnect) { Text(stringResource(Res.string.integrations_apple_action_connect)) }
        Text(stringResource(Res.string.integrations_apple_access_review), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionsSection(
    permissions: List<PermissionRow>,
    onGrantPermissions: () -> Unit,
    onOpenHcSettings: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.integrations_permissions_title)) {
        permissions.forEach { row -> PermissionLine(row) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onGrantPermissions) { Text(stringResource(Res.string.integrations_action_grant_permissions)) }
            OutlinedButton(onClick = onOpenHcSettings) { Text(stringResource(Res.string.integrations_action_open_hc_settings)) }
        }
    }
}

@Composable
private fun PermissionLine(row: PermissionRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row.granted) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(Res.string.integrations_cd_granted),
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = stringResource(Res.string.integrations_cd_not_granted),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(row.label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SyncSection(isSyncing: Boolean, channels: List<SyncChannelRow>, onSyncNow: () -> Unit) {
    SectionCard(title = stringResource(Res.string.integrations_sync_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSyncNow, enabled = !isSyncing) { Text(stringResource(Res.string.integrations_action_sync_now)) }
            if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        channels.forEach { channel -> SyncChannelLine(channel) }
    }
}

@Composable
private fun SyncChannelLine(channel: SyncChannelRow) {
    val lastSync = channel.lastSuccessAtMillis?.let { formatInstant(it) }
        ?: stringResource(Res.string.integrations_sync_never)
    Text(
        stringResource(Res.string.integrations_sync_status_format, channel.label, lastSync),
        style = MaterialTheme.typography.bodyMedium,
    )
    channel.lastError?.let { error ->
        Text(
            stringResource(Res.string.integrations_sync_last_error_format, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BackfillSection(
    hint: String,
    startDay: Long,
    completeDay: Long?,
    isRunning: Boolean,
    onStartDayChange: (Long) -> Unit,
    onStartBackfill: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.integrations_backfill_title)) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        DatePickerField(
            label = stringResource(Res.string.integrations_backfill_from_label),
            value = LocalDate.fromEpochDays(startDay),
            onValueChange = { onStartDayChange(it.toEpochDays()) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onStartBackfill, enabled = !isRunning) { Text(stringResource(Res.string.integrations_action_start_backfill)) }
            if (isRunning) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        val progressText = completeDay?.let {
            stringResource(Res.string.integrations_backfill_progress_format, LocalDate.fromEpochDays(it).toString())
        } ?: stringResource(Res.string.integrations_backfill_none_yet)
        Text(progressText, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatInstant(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
        .usText("yyyy-MM-dd HH:mm")



@Preview(showBackground = true, name = "Available")
@Composable
private fun IntegrationsContentAvailablePreview() {
    MyHealthTheme(dynamicColor = false) {
        IntegrationsContent(
            state = IntegrationsUiState(
                status = HcStatus.AVAILABLE,
                permissions = listOf(
                    PermissionRow("android.permission.health.READ_EXERCISE", "Workouts", granted = true),
                    PermissionRow("android.permission.health.READ_SLEEP", "Sleep", granted = false),
                ),
                syncChannels = listOf(
                    SyncChannelRow("hc.exercise", "Workouts", lastSuccessAtMillis = 0L, lastError = null),
                    SyncChannelRow("hc.body", "Body measurements", lastSuccessAtMillis = null, lastError = "storage: disk full"),
                ),
                backfillStartDay = LocalDate(2025, 9, 12).toEpochDays(),
                backfillCompleteDay = LocalDate(2026, 1, 1).toEpochDays(),
            ),
            onGrantPermissions = {},
            onOpenPlayStore = {},
            onOpenHcSettings = {},
            onSyncNow = {},
            onBackfillStartDayChange = {},
            onStartBackfill = {},
        )
    }
}

@Preview(showBackground = true, name = "Update required")
@Composable
private fun IntegrationsContentUpdateRequiredPreview() {
    MyHealthTheme(dynamicColor = false) {
        IntegrationsContent(
            state = IntegrationsUiState(status = HcStatus.UPDATE_REQUIRED),
            onGrantPermissions = {},
            onOpenPlayStore = {},
            onOpenHcSettings = {},
            onSyncNow = {},
            onBackfillStartDayChange = {},
            onStartBackfill = {},
        )
    }
}

@Preview(showBackground = true, name = "Not installed")
@Composable
private fun IntegrationsContentUnavailablePreview() {
    MyHealthTheme(dynamicColor = false) {
        IntegrationsContent(
            state = IntegrationsUiState(status = HcStatus.UNAVAILABLE),
            onGrantPermissions = {},
            onOpenPlayStore = {},
            onOpenHcSettings = {},
            onSyncNow = {},
            onBackfillStartDayChange = {},
            onStartBackfill = {},
        )
    }
}

@Preview(showBackground = true, name = "Apple Health")
@Composable
private fun IntegrationsContentApplePreview() {
    MyHealthTheme(dynamicColor = false) {
        IntegrationsContent(
            state = IntegrationsUiState(
                status = HcStatus.AVAILABLE,
                platform = HealthPlatform.APPLE_HEALTH,
                permissions = listOf(PermissionRow("HKWorkoutTypeIdentifier", "Workouts", granted = true)),
                backfillStartDay = LocalDate(2025, 9, 12).toEpochDays(),
            ),
            onGrantPermissions = {},
            onOpenPlayStore = {},
            onOpenHcSettings = {},
            onSyncNow = {},
            onBackfillStartDayChange = {},
            onStartBackfill = {},
        )
    }
}
