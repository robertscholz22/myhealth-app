package com.myhealth.ui.zones

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.load.HrBounds
import com.myhealth.domain.engine.load.HrZone
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.engine.load.PolarisationSplit
import com.myhealth.domain.engine.running.DanielsPace
import com.myhealth.domain.model.HrZoneScheme
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme
import com.myhealth.ui.training.formatPaceSecPerKm

/**
 * "Zones & paces" (PLAN §4.2, P14.6, More entry): the five HR zones, the measured/modelled pace
 * band per zone, the Daniels paces from the current VDOT, the 28-day polarisation split and a
 * per-session-type target table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZonesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        ZonesViewModel(
            graph.profileRepo,
            graph.activityRepo,
            graph.runningBestRepo,
            graph.healthRepo,
            graph.settings,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.zones_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        ZonesContent(state = state, modifier = Modifier.fillMaxSize().padding(innerPadding))
    }
}

@Composable
internal fun ZonesContent(state: ZonesUiState, modifier: Modifier = Modifier) {
    if (state.isEmpty) {
        EmptyState(
            title = stringResource(Res.string.zones_empty_title),
            message = stringResource(Res.string.zones_empty_message),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val model = state.model
        if (model != null) {
            item { SectionCard(title = stringResource(Res.string.zones_section_zones_title)) { ZoneTable(model) } }
            item {
                SectionCard(title = stringResource(Res.string.zones_section_paces_title)) {
                    state.bands.forEach { band -> PaceBandRow(band) }
                }
            }
        }
        item { DanielsPacesCard(state.vdot, state.danielsPaces) }
        state.polarisation?.let { split ->
            if (split.totalMinutes > 0.0) item { PolarisationCard(split) }
        }
        if (model != null) {
            item { SessionTargetsCard(state.sessionRows) }
        }
    }
}

@Composable
private fun DanielsPacesCard(vdot: Double?, paces: Map<DanielsPace, Int>) {
    SectionCard(title = stringResource(Res.string.zones_section_daniels_title)) {
        if (vdot == null) {
            Text(stringResource(Res.string.zones_confidence_not_enough_data), style = MaterialTheme.typography.bodyMedium)
        } else {
            DanielsPace.entries.forEach { pace ->
                val secPerKm = paces[pace] ?: return@forEach
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(pace.label(), style = MaterialTheme.typography.bodyLarge)
                    Text(formatPaceSecPerKm(secPerKm), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun DanielsPace.label(): String = when (this) {
    DanielsPace.EASY -> stringResource(Res.string.zones_daniels_easy)
    DanielsPace.MARATHON -> stringResource(Res.string.zones_daniels_marathon)
    DanielsPace.THRESHOLD -> stringResource(Res.string.zones_daniels_threshold)
    DanielsPace.INTERVAL -> stringResource(Res.string.zones_daniels_interval)
    DanielsPace.REPETITION -> stringResource(Res.string.zones_daniels_repetition)
}

@Composable
private fun PolarisationCard(split: PolarisationSplit) {
    SectionCard(title = stringResource(Res.string.zones_section_polarisation_title)) {
        PolarisationBar(split)
        Text(
            text = stringResource(
                Res.string.zones_polarisation_summary_format,
                easyPercent(split),
                moderatePercent(split),
                hardPercent(split),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (showsPolarisationHint(split)) {
            Text(
                text = stringResource(Res.string.zones_polarisation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PolarisationBar(split: PolarisationSplit, modifier: Modifier = Modifier) {
    val easy = easyPercent(split).coerceAtLeast(0)
    val moderate = moderatePercent(split).coerceAtLeast(0)
    val hard = hardPercent(split).coerceAtLeast(0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        if (easy > 0) Box(Modifier.weight(easy.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        if (moderate > 0) {
            Box(Modifier.weight(moderate.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
        }
        if (hard > 0) Box(Modifier.weight(hard.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.error))
    }
}

@Composable
private fun SessionTargetsCard(rows: List<SessionZoneRow>) {
    SectionCard(title = stringResource(Res.string.zones_section_sessions_title)) {
        val naText = stringResource(Res.string.zones_table_na)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.sessionType.displayName(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = listOfNotNull(row.zoneLabel, row.paceLabel).ifEmpty { listOf(naText) }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ZonesContentPreview() {
    val model = HrZoneModel(
        scheme = HrZoneScheme.HRR_KARVONEN,
        zones = listOf(
            HrZone(1, 50, 133, "hr_zone_1_name"),
            HrZone(2, 134, 147, "hr_zone_2_name"),
            HrZone(3, 148, 161, "hr_zone_3_name"),
            HrZone(4, 162, 175, "hr_zone_4_name"),
            HrZone(5, 176, null, "hr_zone_5_name"),
        ),
        bounds = HrBounds(hrMax = 190, hrRest = 50),
    )
    MyHealthTheme(dynamicColor = false) {
        ZonesContent(
            state = ZonesUiState(
                isLoading = false,
                model = model,
                vdot = 50.0,
                polarisation = PolarisationSplit(easyShare = 0.75, hardShare = 0.15, totalMinutes = 400.0),
            ),
        )
    }
}
