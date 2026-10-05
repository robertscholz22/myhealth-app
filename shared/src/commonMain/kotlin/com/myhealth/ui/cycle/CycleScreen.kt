package com.myhealth.ui.cycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.domain.util.utcDateOfMillis
import com.myhealth.domain.util.utcMidnightMillis
import com.myhealth.resources.*
import com.myhealth.ui.common.usText
import org.jetbrains.compose.resources.StringResource
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.CycleForecast
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Cycle screen (PLAN §5 P11.3), reached from More → "Cycle" — only shown there while
 * [com.myhealth.domain.repository.CycleRepository.isTrackingEnabled] is true. */
@Composable
fun CycleScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> CycleViewModel(graph.cycleRepo, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }

    CycleContent(
        state = state,
        onBack = onBack,
        onLogStartClick = vm::openLogStartDialog,
        onPeriodEndedClick = vm::openPeriodEndedDialog,
        onDeleteClick = vm::requestDelete,
        snackbarHostState = snackbar,
        modifier = modifier,
    )

    if (state.showLogStartDialog) {
        CycleDatePickerDialog(
            titleRes = Res.string.cycle_dialog_log_period_start_title,
            initialDay = state.today,
            onDismiss = vm::dismissLogStartDialog,
            onConfirm = vm::logPeriodStart,
        )
    }
    if (state.showPeriodEndedDialog) {
        CycleDatePickerDialog(
            titleRes = Res.string.cycle_dialog_period_ended_title,
            initialDay = state.today,
            onDismiss = vm::dismissPeriodEndedDialog,
            onConfirm = vm::logPeriodEnded,
        )
    }
    state.pendingDeleteId?.let {
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text(stringResource(Res.string.cycle_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.cycle_delete_confirm_message)) },
            confirmButton = { TextButton(onClick = vm::confirmDelete) { Text(stringResource(Res.string.action_delete)) } },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CycleContent(
    state: CycleUiState,
    onBack: () -> Unit,
    onLogStartClick: () -> Unit,
    onPeriodEndedClick: () -> Unit,
    onDeleteClick: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.cycle_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.isLoading && !state.hasEntries) {
            EmptyState(
                title = stringResource(Res.string.cycle_empty_title),
                message = stringResource(Res.string.cycle_empty_message),
                icon = Icons.Filled.Favorite,
                actionLabel = stringResource(Res.string.cycle_action_log_period_start),
                onAction = onLogStartClick,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusCard(
                    status = state.status,
                    confidenceMessage = state.confidenceMessage,
                )
            }
            item {
                ActionsRow(
                    canMarkEnded = state.latestEntry?.periodEndDay == null && state.latestEntry != null,
                    onLogStartClick = onLogStartClick,
                    onPeriodEndedClick = onPeriodEndedClick,
                )
            }
            item { ForecastSection(state.forecast) }
            item { HistorySection(state.historyRows, onDeleteClick) }
        }
    }
}

@Composable
private fun StatusCard(status: CycleStatus?, confidenceMessage: UiMessage?) {
    SectionCard(title = stringResource(Res.string.cycle_status_title)) {
        if (status == null) {
            Text(stringResource(Res.string.cycle_empty_message), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(phaseLabelRes(status.phase))) })
            if (status.isLateLuteal) {
                AssistChip(onClick = {}, enabled = false, label = { Text(stringResource(Res.string.cycle_late_luteal_chip)) })
            }
        }
        Text(
            text = dayOfCycleLabel(status.dayOfCycle, status.cycleLengthDays),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(countdownLabel(status.day, status.nextPeriodStart), style = MaterialTheme.typography.bodyMedium)
        Text(ovulationLabel(status.ovulationDay), style = MaterialTheme.typography.bodyMedium)
        Text(fertileWindowLabel(status.fertileWindow), style = MaterialTheme.typography.bodyMedium)
        confidenceMessage?.let {
            Text(
                text = it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionsRow(canMarkEnded: Boolean, onLogStartClick: () -> Unit, onPeriodEndedClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onLogStartClick, modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.cycle_action_log_period_start))
        }
        OutlinedButton(onClick = onPeriodEndedClick, enabled = canMarkEnded, modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.cycle_action_period_ended))
        }
    }
}

private const val ROW_DATE_FORMAT = "d MMM yyyy"

@Composable
private fun ForecastSection(forecast: CycleForecast) {
    SectionCard(title = stringResource(Res.string.cycle_forecast_title)) {
        if (forecast.isEmpty) {
            Text(stringResource(Res.string.cycle_forecast_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        forecast.cycles.forEach { cycle ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${cycle.periodStart.epochDayDate().usText(ROW_DATE_FORMAT)} – " +
                        cycle.periodEnd.epochDayDate().usText(ROW_DATE_FORMAT),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.cycle_forecast_ovulation_format, cycle.ovulationDay.epochDayDate().usText(ROW_DATE_FORMAT)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistorySection(rows: List<CycleHistoryRow>, onDelete: (Long) -> Unit) {
    SectionCard(title = stringResource(Res.string.cycle_history_title)) {
        if (rows.isEmpty()) {
            Text(stringResource(Res.string.cycle_history_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        rows.forEach { row -> HistoryRow(row, onDelete) }
    }
}

@Composable
private fun HistoryRow(row: CycleHistoryRow, onDelete: (Long) -> Unit) {
    val entry = row.entry
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(entry.periodStartDay.epochDayDate().usText(ROW_DATE_FORMAT), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(
                    entry.periodLengthDays?.let { stringResource(Res.string.cycle_history_period_days_format, it) }
                        ?: stringResource(Res.string.cycle_history_ongoing),
                    row.cycleLengthDays?.let { stringResource(Res.string.cycle_history_cycle_length_format, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onDelete(entry.id) }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.action_delete))
        }
    }
}

/** Opens directly on [initialDay] (default "today", per PLAN §5 P11.3) — no extra tap to open a
 * text field first, since both actions this backs are a single date pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CycleDatePickerDialog(
    titleRes: StringResource,
    initialDay: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.fromEpochDays(initialDay).utcMidnightMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onConfirm(utcDateOfMillis(millis).toEpochDays())
                }
            }) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    ) {
        Column {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            DatePicker(state = pickerState)
        }
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun CycleContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        CycleContent(
            state = previewCycleUiState(),
            onBack = {},
            onLogStartClick = {},
            onPeriodEndedClick = {},
            onDeleteClick = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Empty")
@Composable
private fun CycleContentEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        CycleContent(
            state = CycleUiState(isLoading = false, trackingEnabled = true, today = LocalDate(2026, 9, 14).toEpochDays()),
            onBack = {},
            onLogStartClick = {},
            onPeriodEndedClick = {},
            onDeleteClick = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}
