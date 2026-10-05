package com.myhealth.ui.bike

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
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RideBestKind
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** "Bike & power" (PLAN "UI.", More entry, P12.4): the FTP card and the power/time PR tables. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikeScreen(onBack: () -> Unit, onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> BikeViewModel(graph.rideBestRepo, graph.profileRepo, graph.activityRepo, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.bike_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        BikeContent(state = state, onOpenActivity = onOpenActivity, modifier = Modifier.fillMaxSize().padding(innerPadding))
    }
}

@Composable
internal fun BikeContent(state: BikeUiState, onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { FtpCard(state.ftp, state.indoorTrainerAvailable, onOpenActivity) }
        if (state.powerBests.isEmpty() && state.timeBests.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(Res.string.bike_empty_title),
                    message = stringResource(Res.string.bike_empty_message),
                )
            }
        } else {
            if (state.powerBests.isNotEmpty()) {
                item { BestsTableCard(stringResource(Res.string.bike_power_bests_title), state.powerBests, onOpenActivity) }
            }
            if (state.timeBests.isNotEmpty()) {
                item { BestsTableCard(stringResource(Res.string.bike_time_bests_title), state.timeBests, onOpenActivity) }
            }
        }
    }
}

@Composable
private fun FtpCard(ftp: FtpEstimate?, indoorTrainerAvailable: Boolean, onOpenActivity: (Long) -> Unit) {
    SectionCard(title = stringResource(Res.string.bike_ftp_title)) {
        if (ftp == null) {
            Text(stringResource(Res.string.bike_ftp_hint_no_estimate), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("${ftp.watts} W", style = MaterialTheme.typography.headlineSmall)
            Text(ftp.source.label(), style = MaterialTheme.typography.bodyMedium)
            val basisText = ftp.basisDay?.let { day ->
                stringResource(Res.string.bike_ftp_basis_date_format, formatShortDate(day))
            }
            if (basisText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = ftp.basisActivityId != null) {
                            ftp.basisActivityId?.let(onOpenActivity)
                        },
                ) {
                    Text(
                        text = basisText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = if (indoorTrainerAvailable) {
                stringResource(Res.string.bike_indoor_trainer_status_on)
            } else {
                stringResource(Res.string.bike_indoor_trainer_status_off)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BestsTableCard(title: String, bests: List<RideBest>, onOpenActivity: (Long) -> Unit) {
    SectionCard(title = title) {
        bests.forEach { best ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = best.activityId != null) { best.activityId?.let(onOpenActivity) },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(rideBestKindLabel(best.kind), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = formatShortDate(best.day),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val estimatedSuffix = if (best.isEstimated) stringResource(Res.string.bike_estimated_suffix) else ""
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatRideBestValue(best) + estimatedSuffix, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BikeContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        BikeContent(
            state = BikeUiState(
                isLoading = false,
                ftp = FtpEstimate(
                    watts = 285,
                    source = FtpSource.STREAM_20MIN,
                    basisActivityId = 1L,
                    basisDay = LocalDate(2026, 9, 2).toEpochDays(),
                ),
                powerBests = listOf(
                    RideBest(
                        id = 1,
                        kind = RideBestKind.POWER_20MIN,
                        value = 300.0,
                        activityId = 1L,
                        day = LocalDate(2026, 9, 2).toEpochDays(),
                        isEstimated = false,
                        createdAtMillis = 0L,
                    ),
                ),
            ),
            onOpenActivity = {},
        )
    }
}
