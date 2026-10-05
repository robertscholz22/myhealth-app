package com.myhealth.ui.running

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.running.CanonicalDistances
import com.myhealth.domain.engine.running.RacePrediction
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.util.epochDayDate
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.charts.LineChartCard
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

@Composable
fun RunningPrsScreen(onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> RunningPrsViewModel(graph.runningBestRepo, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()

    RunningPrsContent(
        state = state,
        onOpenActivity = onOpenActivity,
        onAddClick = vm::openAddDialog,
        onDismissAdd = vm::dismissAddDialog,
        onSaveManualPr = vm::addManualPr,
        modifier = modifier,
    )
}

@Composable
private fun RunningPrsContent(
    state: RunningPrsUiState,
    onOpenActivity: (Long) -> Unit,
    onAddClick: () -> Unit,
    onDismissAdd: () -> Unit,
    onSaveManualPr: (Double, Int, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.running_add_manual_pr))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(vdotLabel(state.vdot), style = MaterialTheme.typography.titleMedium) }
            if (state.bests.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.running_empty_title),
                        message = stringResource(Res.string.running_empty_message),
                    )
                }
            } else {
                item { PrTableCard(state.bests, onOpenActivity) }
            }
            item { PrProgressionCard(state.progression) }
            if (state.predictions.isNotEmpty()) {
                item { PredictionsCard(state.predictions) }
            }
        }
    }

    if (state.showAddDialog) {
        AddManualPrDialog(onDismiss = onDismissAdd, onSave = onSaveManualPr)
    }
}

/** One line per canonical distance with at least two efforts: finishing time against date. */
@Composable
private fun PrProgressionCard(progression: List<com.myhealth.ui.common.charts.ChartSeries>) {
    LineChartCard(
        title = stringResource(Res.string.running_chart_pr_progression_title),
        series = progression,
        xLabels = prAxisLabels(progression),
        yFormatter = { formatRaceTime(roundHalfUpToInt(it)) },
        emptyMessage = stringResource(Res.string.running_chart_pr_progression_empty),
        alwaysShowLegend = true,
    )
}

@Composable
private fun PrTableCard(bests: List<RunningBest>, onOpenActivity: (Long) -> Unit) {
    SectionCard(title = stringResource(Res.string.running_pr_table_title)) {
        bests.forEach { best ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = best.activityId != null) {
                        best.activityId?.let(onOpenActivity)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(distanceLabel(best.distanceMeters), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = best.day.epochDayDate().toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    val estimatedSuffix = if (best.isEstimated) stringResource(Res.string.running_pr_estimated_suffix) else ""
                    Text(formatRaceTime(best.timeSec) + estimatedSuffix, style = MaterialTheme.typography.bodyLarge)
                    Text(formatPaceSecPerKm(best.paceSecPerKm), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PredictionsCard(predictions: List<RacePrediction>) {
    SectionCard(title = stringResource(Res.string.running_predictions_title)) {
        predictions.forEach { prediction ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(distanceLabel(prediction.distanceMeters), style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatRaceTime(roundHalfUpToInt(prediction.timeSec)) +
                        " · " + formatPaceSecPerKm(roundHalfUpToInt(prediction.paceSecPerKm)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AddManualPrDialog(onDismiss: () -> Unit, onSave: (Double, Int, Long) -> Unit) {
    var distance by remember { mutableStateOf(CanonicalDistances.FIVE_KM) }
    var minutes by remember { mutableStateOf<Double?>(null) }
    var seconds by remember { mutableStateOf<Double?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(null) }

    val timeSec = ((minutes ?: 0.0) * 60 + (seconds ?: 0.0)).toInt()
    val canSave = timeSec > 0 && date != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.running_add_manual_pr)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = stringResource(Res.string.running_pr_distance_field),
                    options = CanonicalDistances.ALL,
                    selected = distance,
                    optionLabel = ::distanceLabel,
                    onSelect = { distance = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = stringResource(Res.string.running_pr_minutes_field),
                        value = minutes,
                        onValueChange = { minutes = it },
                        decimals = 0,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = stringResource(Res.string.running_pr_seconds_field),
                        value = seconds,
                        onValueChange = { seconds = it },
                        decimals = 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                DatePickerField(
                    label = stringResource(Res.string.running_pr_date_field),
                    value = date,
                    onValueChange = { date = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { date?.let { onSave(distance, timeSec, it.toEpochDays()) } },
                enabled = canSave,
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun RunningPrsContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        RunningPrsContent(
            state = RunningPrsUiState(isLoading = false),
            onOpenActivity = {},
            onAddClick = {},
            onDismissAdd = {},
            onSaveManualPr = { _, _, _ -> },
        )
    }
}
