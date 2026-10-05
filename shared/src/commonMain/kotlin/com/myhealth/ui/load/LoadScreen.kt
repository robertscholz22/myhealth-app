package com.myhealth.ui.load

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.load.AcwrZone
import com.myhealth.domain.engine.strength.MuscleLoadEngine
import com.myhealth.domain.engine.strength.MuscleLoadInput
import com.myhealth.domain.engine.strength.MuscleLoadState
import com.myhealth.domain.engine.strength.MuscleSession
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SportGroup
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.StatTile
import com.myhealth.ui.common.body.BodyFigure
import com.myhealth.ui.common.body.BodyFigureLegend
import com.myhealth.ui.common.body.BodyFigureLegendKind
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.charts.BarChartCard
import com.myhealth.ui.common.charts.ChartBand
import com.myhealth.ui.common.charts.ChartSeries
import com.myhealth.ui.common.charts.LineChartCard
import com.myhealth.ui.strength.label
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.theme.PositiveGreen
import com.myhealth.ui.theme.WarningAmber

@Composable
fun LoadScreen(modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        LoadViewModel(
            graph.loadRepo,
            graph.healthRepo,
            graph.profileRepo,
            graph.activityRepo,
            graph.planRepo,
            graph.strengthRepo,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LoadContent(state = state, onRangeSelect = vm::setRange, modifier = modifier)
}

@Composable
private fun LoadContent(
    state: LoadUiState,
    onRangeSelect: (LoadRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { RangeSelector(state.range, onRangeSelect) }
        if (!state.hasData) {
            item {
                EmptyState(
                    title = stringResource(Res.string.load_empty_title),
                    message = stringResource(Res.string.load_empty_message),
                )
            }
        } else {
            item { LoadTilesCard(state.latest) }
            item { AtlCtlChartCard(state.series) }
            item { AcwrChartCard(state.series) }
            item { DailyTrimpCard(state.series) }
            item { RecoveryTrendCard(state.series) }
        }
        item { RecoveryCard(state.recovery) }
        item { MuscleLoadCard(state.muscleLoad) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(selected: LoadRange, onSelect: (LoadRange) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        LoadRange.entries.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = LoadRange.entries.size),
            ) { Text(stringResource(range.labelRes)) }
        }
    }
}

@Composable
private fun LoadTilesCard(latest: DailyLoad?) {
    SectionCard(title = stringResource(Res.string.load_tiles_title)) {
        if (latest == null) {
            Text(stringResource(Res.string.load_tiles_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(label = stringResource(Res.string.load_stat_atl), value = fmtDecimal(latest.atl, 0))
            StatTile(label = stringResource(Res.string.load_stat_ctl), value = fmtDecimal(latest.ctl, 0))
            AcwrTile(latest.acwr)
            StatTile(label = stringResource(Res.string.load_stat_tsb), value = fmtDecimal(latest.tsb, 0))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(
                label = stringResource(Res.string.load_stat_monotony),
                value = latest.monotony?.let { fmtDecimal(it, 2) } ?: "—",
            )
            StatTile(
                label = stringResource(Res.string.load_stat_strain),
                value = latest.strain?.let { fmtDecimal(it, 0) } ?: "—",
            )
        }
        if (latest.flags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                latest.flags.forEach { flag ->
                    Text("• ${flagExplanation(flag)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AcwrTile(acwr: Double?) {
    val zone = acwrZoneOf(acwr)
    Column {
        Text(
            text = stringResource(Res.string.load_stat_acwr),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = acwr?.let { fmtDecimal(it, 2) } ?: "—",
            style = MaterialTheme.typography.titleLarge,
            color = zone?.color() ?: MaterialTheme.colorScheme.onSurface,
        )
        if (zone != null) {
            Text(zone.label(), style = MaterialTheme.typography.bodySmall, color = zone.color())
        }
    }
}

/** Acute (7-day) and chronic (28-day) load, the two EWMAs of §3.2.2. */
@Composable
private fun AtlCtlChartCard(series: List<DailyLoad>) {
    LineChartCard(
        title = stringResource(Res.string.load_chart_atl_ctl_title),
        series = listOf(
            ChartSeries(name = stringResource(Res.string.load_chart_atl_series), points = atlPoints(series)),
            ChartSeries(name = stringResource(Res.string.load_chart_ctl_series), points = ctlPoints(series)),
        ),
        xLabels = loadAxisLabels(series),
        yFormatter = { fmtDecimal(it, 0) },
        emptyMessage = stringResource(Res.string.load_chart_atl_ctl_empty),
    )
}

/** ACWR with the §3.2.3 risk zones shaded behind the line. */
@Composable
private fun AcwrChartCard(series: List<DailyLoad>) {
    val bands = listOf(
        ChartBand(
            label = "Optimal",
            from = ACWR_OPTIMAL_MIN,
            to = ACWR_OPTIMAL_MAX,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        ),
        ChartBand(
            label = "Caution",
            from = ACWR_OPTIMAL_MAX,
            to = ACWR_CAUTION_MAX,
            color = WarningAmber.copy(alpha = 0.20f),
        ),
        ChartBand(
            label = "High risk",
            from = ACWR_CAUTION_MAX,
            to = Double.MAX_VALUE,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
        ),
    )
    LineChartCard(
        title = stringResource(Res.string.load_chart_acwr_title),
        series = listOf(ChartSeries(name = stringResource(Res.string.load_chart_acwr_series), points = acwrPoints(series))),
        xLabels = loadAxisLabels(series),
        yFormatter = { fmtDecimal(it, 1) },
        bands = bands,
        emptyMessage = stringResource(Res.string.load_chart_acwr_empty),
    )
}

@Composable
private fun DailyTrimpCard(series: List<DailyLoad>) {
    val bars = trimpBars(series)
    BarChartCard(
        title = stringResource(Res.string.load_chart_trimp_title),
        values = bars,
        xLabels = loadAxisLabels(series),
        yFormatter = { fmtDecimal(it, 0) },
        highlightIndex = bars.lastIndex.takeIf { it >= 0 },
        emptyMessage = stringResource(Res.string.load_chart_trimp_empty),
    )
}

@Composable
private fun RecoveryTrendCard(series: List<DailyLoad>) {
    LineChartCard(
        title = stringResource(Res.string.load_chart_recovery_title),
        series = listOf(ChartSeries(name = stringResource(Res.string.load_chart_recovery_series), points = recoveryPoints(series))),
        xLabels = loadAxisLabels(series),
        yFormatter = { fmtDecimal(it, 0) },
        emptyMessage = stringResource(Res.string.load_chart_recovery_empty),
    )
}

@Composable
private fun RecoveryCard(recovery: RecoveryState?) {
    SectionCard(title = stringResource(Res.string.load_recovery_title)) {
        if (recovery?.score == null) {
            Text(stringResource(Res.string.load_recovery_no_data), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.load_recovery_score_label, recovery.score!!), style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(recoveryBandLabel(recovery.band), style = MaterialTheme.typography.titleMedium)
                Text(confidencePercentLabel(recovery.confidence), style = MaterialTheme.typography.bodySmall)
            }
        }
        recovery.components.forEach { component ->
            Text(componentLabel(component), style = MaterialTheme.typography.bodyMedium)
        }
        recovery.flags.forEach { flag ->
            Text("• ${flagExplanation(flag)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The "Muscle load" card (§3.12.2/§3.12.4/§4.2 Load & recovery, P14.8): [BodyFigure] as a
 * fresh/loaded/fatigued heat map, its legend, the three most-loaded groups with their AU and band,
 * and a one-line hint — or the empty state when nothing has loaded a muscle in the last 14 days.
 */
@Composable
private fun MuscleLoadCard(muscleLoad: MuscleLoadState?) {
    SectionCard(title = stringResource(Res.string.load_muscle_title)) {
        if (muscleLoad == null || !hasMuscleLoadActivity(muscleLoad)) {
            Text(stringResource(Res.string.load_muscle_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        BodyFigure(highlight = muscleLoadHeatMap(muscleLoad), modifier = Modifier.fillMaxWidth())
        BodyFigureLegend(kind = BodyFigureLegendKind.LOAD_BAND)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            topMuscleLoadRows(muscleLoad).forEach { row ->
                val bandLabel = stringResource(muscleLoadBandLabelRes(row.band))
                Text(
                    stringResource(Res.string.load_muscle_row, row.group.label(), row.au, bandLabel),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        muscleLoadHint(muscleLoad)?.let { hint ->
            Text(stringResource(hint.labelRes()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AcwrZone.label(): String = when (this) {
    AcwrZone.DETRAINING -> stringResource(Res.string.load_acwr_zone_detraining)
    AcwrZone.OPTIMAL -> stringResource(Res.string.load_acwr_zone_optimal)
    AcwrZone.CAUTION -> stringResource(Res.string.load_acwr_zone_caution)
    AcwrZone.HIGH_RISK -> stringResource(Res.string.load_acwr_zone_high_risk)
}

@Composable
internal fun AcwrZone.color(): Color = when (this) {
    AcwrZone.DETRAINING -> MaterialTheme.colorScheme.secondary
    AcwrZone.OPTIMAL -> PositiveGreen
    AcwrZone.CAUTION -> WarningAmber
    AcwrZone.HIGH_RISK -> MaterialTheme.colorScheme.error
}

@Preview(showBackground = true)
@Composable
private fun LoadContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        LoadContent(
            state = LoadUiState(isLoading = false, muscleLoad = previewMuscleLoad()),
            onRangeSelect = {},
        )
    }
}

/** A hard-legs, fresh-arms sample so the preview shows the heat map and the "upper day" hint. */
private fun previewMuscleLoad(): MuscleLoadState = MuscleLoadEngine.compute(
    MuscleLoadInput(
        today = 0L,
        ctl = 45.0,
        sessions = listOf(MuscleSession(day = -1L, sportGroup = SportGroup.RUN, trimp = 180.0)),
    ),
)
