package com.myhealth.ui.activities

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.Lap
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SourceBadgeRow
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.usText
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.zones.zoneNameRes
import com.myhealth.ui.zones.zoneRowLabel
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(id: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        ActivityDetailViewModel(
            id,
            graph.activityRepo,
            graph.profileRepo,
            graph.calendarRepo,
            graph.rideBestRepo,
            graph.syncScheduler,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val fallback = stringResource(Res.string.activity_detail_title_fallback)
                    Text(state.activity?.title ?: state.activity?.sportType?.displayName() ?: fallback)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (state.activity != null) {
                        IconButton(onClick = vm::requestDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.activity_detail_delete_cd))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        ActivityDetailBody(
            state = state,
            onSaveTitle = vm::saveTitle,
            onSaveNote = vm::saveNote,
            onOpenEventPicker = vm::openEventPicker,
            onUnlinkEvent = vm::unlinkEvent,
            onSaveRpe = vm::saveRpe,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }

    if (state.showDeleteConfirm) {
        DeleteConfirmDialog(onConfirm = vm::confirmDelete, onDismiss = vm::cancelDelete)
    }

    if (state.showEventPicker) {
        EventPickerSheet(events = state.dayEvents, onSelect = vm::linkEvent, onDismiss = vm::closeEventPicker)
    }
}

@Composable
internal fun ActivityDetailBody(
    state: ActivityDetailUiState,
    onSaveTitle: (String) -> Unit,
    onSaveNote: (String) -> Unit,
    onOpenEventPicker: () -> Unit,
    onUnlinkEvent: () -> Unit,
    onSaveRpe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = state.activity
    if (activity == null) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
            if (!state.isLoading) Text(stringResource(Res.string.activity_detail_not_found)) else CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeaderStatsCard(activity) }
        item { SourceBadgeRow(activity.mergedSources) }
        item { TitleEditCard(activity.title, onSaveTitle) }
        item { NoteEditCard(activity.note, onSaveNote) }
        item { LinkedEventCard(state.linkedEvent, onOpenEventPicker, onUnlinkEvent) }
        if (hasPowerFields(activity)) {
            item { PowerCard(activity, state.ftp) }
        }
        item { HrChartCard(activity) }
        item { PaceOrSpeedChartCard(activity) }
        if (activity.streams?.powerW != null) {
            item { PowerChartCard(activity) }
        }
        if (altitudePoints(activity.streams).any { it.y != null }) {
            item { AltitudeChartCard(activity) }
        }
        if (state.hasHrZones) {
            item { HrSummaryCard(state) }
        }
        if (activity.laps.isNotEmpty()) {
            item { LapsCard(activity.laps) }
        }
        item { RpeCard(activity.rpe, activity.loadMethod, onSaveRpe) }
    }
}

@Composable
private fun HeaderStatsCard(activity: ActivitySession) {
    SectionCard(title = stringResource(Res.string.activity_detail_overview_title)) {
        Text(formatStartAtFull(activity.startAtMillis), style = MaterialTheme.typography.bodyMedium)
        StatLine(stringResource(Res.string.activity_detail_duration_label), formatDuration(activity.durationSec))
        StatLine(stringResource(Res.string.activity_detail_elapsed_label), formatDuration(activity.elapsedSec))
        formatDistanceKm(activity.distanceMeters)?.let { StatLine(stringResource(Res.string.activity_detail_distance_label), it) }
        activity.avgHr?.let { StatLine(stringResource(Res.string.activity_detail_avg_hr_label), "$it bpm") }
        activity.maxHr?.let { StatLine(stringResource(Res.string.activity_detail_max_hr_label), "$it bpm") }
        if (activity.sportGroup == SportGroup.RUN) {
            formatPaceMinPerKm(activity.avgSpeedMps)?.let { StatLine(stringResource(Res.string.activity_detail_avg_pace_label), it) }
            formatPaceMinPerKm(activity.maxSpeedMps)?.let { StatLine(stringResource(Res.string.activity_detail_best_pace_label), it) }
        } else {
            val avgLabel = stringResource(Res.string.activity_detail_avg_speed_label)
            val maxLabel = stringResource(Res.string.activity_detail_max_speed_label)
            activity.avgSpeedMps?.let { StatLine(avgLabel, "${fmtDecimal(it * 3.6, 1)} km/h") }
            activity.maxSpeedMps?.let { StatLine(maxLabel, "${fmtDecimal(it * 3.6, 1)} km/h") }
        }
        // `avgCadenceSpm` is already revolutions per minute for a CYCLE ride (P12) — only the unit
        // label changes, the stored number does not.
        val cadenceUnit = if (activity.sportGroup == SportGroup.CYCLE) "rpm" else "spm"
        activity.avgCadenceSpm?.let {
            StatLine(stringResource(Res.string.activity_detail_cadence_label), "${fmtDecimal(it, 0)} $cadenceUnit")
        }
        val elevationLabel = stringResource(Res.string.activity_detail_elevation_gain_label)
        activity.elevationGainM?.let { StatLine(elevationLabel, "${fmtDecimal(it, 0)} m") }
        val activeCaloriesLabel = stringResource(Res.string.activity_detail_active_calories_label)
        activity.activeEnergyKcal?.let { StatLine(activeCaloriesLabel, "${fmtDecimal(it, 0)} kcal") }
        val totalCaloriesLabel = stringResource(Res.string.activity_detail_total_calories_label)
        activity.totalEnergyKcal?.let { StatLine(totalCaloriesLabel, "${fmtDecimal(it, 0)} kcal") }
        activity.trimp?.let { trimp ->
            val method = activity.loadMethod?.let { " (${it.label()})" } ?: ""
            StatLine(stringResource(Res.string.activity_detail_trimp_label), "${fmtDecimal(trimp, 1)}$method")
        }
    }
}

private fun hasPowerFields(activity: ActivitySession): Boolean =
    activity.avgPowerW != null || activity.maxPowerW != null || activity.normalizedPowerW != null

/**
 * Avg / NP / max power, plus intensity factor and TSS once an FTP estimate exists (PLAN "UI.",
 * P12.4). `IF`/`TSS` mirror `TrimpCalculator.powerTss`'s formula (§3.8) but are shown here
 * independently of whether the ride's own TRIMP actually used the `POWER_TSS` rung.
 */
@Composable
private fun PowerCard(activity: ActivitySession, ftp: FtpEstimate?) {
    SectionCard(title = stringResource(Res.string.activity_detail_power_title)) {
        activity.avgPowerW?.let { StatLine(stringResource(Res.string.activity_detail_avg_power_label), "$it W") }
        activity.normalizedPowerW?.let { StatLine(stringResource(Res.string.activity_detail_np_label), "$it W") }
        activity.maxPowerW?.let { StatLine(stringResource(Res.string.activity_detail_max_power_label), "$it W") }
        val np = activity.normalizedPowerW ?: activity.avgPowerW
        if (np != null && ftp != null && ftp.watts > 0) {
            val intensityFactor = np.toDouble() / ftp.watts
            StatLine(stringResource(Res.string.activity_detail_if_label), fmtDecimal(intensityFactor, 2))
            val tss = trainingStressScore(activity.durationSec, np, ftp.watts)
            tss?.let { StatLine(stringResource(Res.string.activity_detail_tss_label), fmtDecimal(it, 0)) }
        }
    }
}

/** `durationSec * NP * IF / (ftpWatts * 3600) * 100` — same formula as `TrimpCalculator.powerTss`. */
private fun trainingStressScore(durationSec: Int, np: Int, ftpWatts: Int): Double? {
    if (ftpWatts <= 0 || durationSec <= 0 || np <= 0) return null
    val intensityFactor = np.toDouble() / ftpWatts
    return durationSec * np * intensityFactor / (ftpWatts * 3600.0) * 100.0
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadMethod.label(): String = when (this) {
    LoadMethod.HR_SAMPLES -> stringResource(Res.string.activity_detail_load_method_hr_samples)
    LoadMethod.HR_AVERAGE -> stringResource(Res.string.activity_detail_load_method_avg_hr)
    LoadMethod.RPE_ESTIMATE -> stringResource(Res.string.activity_detail_load_method_rpe_estimate)
    LoadMethod.DURATION_ONLY -> stringResource(Res.string.activity_detail_load_method_duration_only)
    LoadMethod.POWER_TSS -> stringResource(Res.string.activity_detail_load_method_power_tss)
}

@Composable
private fun TitleEditCard(title: String?, onSave: (String) -> Unit) {
    var text by remember(title) { mutableStateOf(title.orEmpty()) }
    SectionCard(title = stringResource(Res.string.activity_detail_title_card_title)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(onClick = { onSave(text) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(Res.string.action_save)) }
    }
}

@Composable
private fun NoteEditCard(note: String?, onSave: (String) -> Unit) {
    var text by remember(note) { mutableStateOf(note.orEmpty()) }
    SectionCard(title = stringResource(Res.string.activity_detail_notes_title)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Button(onClick = { onSave(text) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(Res.string.action_save)) }
    }
}

@Composable
private fun HrSummaryCard(state: ActivityDetailUiState) {
    SectionCard(title = stringResource(Res.string.activity_detail_hr_summary_title)) {
        state.minHr?.let { StatLine(stringResource(Res.string.activity_detail_hr_min_label), "$it bpm") }
        state.avgHrFromStream?.let { StatLine(stringResource(Res.string.activity_detail_hr_avg_label), "$it bpm") }
        state.maxHrFromStream?.let { StatLine(stringResource(Res.string.activity_detail_hr_max_label), "$it bpm") }
        Text(
            stringResource(Res.string.activity_detail_hr_time_in_zone_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        val model = state.hrZoneModel
        model?.zones?.forEach { zone ->
            val isTarget = zone.index == state.targetZoneIndex
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = zoneRowLabel(zone, stringResource(zoneNameRes(zone.index))) +
                        if (isTarget) " " + stringResource(Res.string.activity_detail_target_zone_marker) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text("${fmtDecimal(state.hrZoneMinutes.getOrElse(zone.index - 1) { 0.0 }, 1)} min")
            }
        }
    }
}

@Composable
private fun LapsCard(laps: List<Lap>) {
    SectionCard(title = stringResource(Res.string.activity_detail_laps_title)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.activity_detail_laps_header_index), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.activity_detail_laps_header_time), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.activity_detail_laps_header_distance), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.activity_detail_laps_header_avg_hr), style = MaterialTheme.typography.labelMedium)
        }
        laps.forEach { lap ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${lap.lapIndex + 1}", style = MaterialTheme.typography.bodyMedium)
                Text(formatDuration(lap.durationSec), style = MaterialTheme.typography.bodyMedium)
                Text(formatDistanceKm(lap.distanceMeters) ?: "—", style = MaterialTheme.typography.bodyMedium)
                Text(lap.avgHr?.let { "$it bpm" } ?: "—", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * RPE entry (§4.2 Activity detail, P5.9): a 1–10 segmented row that saves on tap, plus the TRIMP
 * method label so the athlete can see whether an RPE entry actually changed how the load was
 * computed (`RPE_ESTIMATE` only wins the method ladder when there is no HR data at all, §3.2.2).
 */
@Composable
private fun RpeCard(rpe: Int?, loadMethod: LoadMethod?, onSaveRpe: (Int) -> Unit) {
    SectionCard(title = stringResource(Res.string.activity_detail_rpe_title)) {
        FlowRowRpeSelector(selected = rpe, onSelect = onSaveRpe)
        loadMethod?.let { StatLine(stringResource(Res.string.activity_detail_trimp_method_label), it.label()) }
    }
}

@Composable
private fun FlowRowRpeSelector(selected: Int?, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..10).forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(value.toString()) },
            )
        }
    }
}

@Composable
private fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.activity_detail_delete_dialog_title)) },
        text = { Text(stringResource(Res.string.activity_detail_delete_dialog_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

private fun formatStartAtFull(startAtMillis: Long): String =
    Instant.fromEpochMilliseconds(startAtMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        .usText("EEEE, MMM d yyyy · HH:mm")
