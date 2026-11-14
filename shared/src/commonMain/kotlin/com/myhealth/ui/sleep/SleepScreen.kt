package com.myhealth.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberDayVm
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.util.epochDayDate
import com.myhealth.resources.*
import com.myhealth.ui.body.SLEEP_BAR_NIGHTS
import com.myhealth.ui.body.nightAxisLabels
import com.myhealth.ui.body.sleepHoursBars
import com.myhealth.ui.calendar.formatSleepDuration
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.LoadingBox
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.charts.BarChartCard
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.common.stringResource
import com.myhealth.ui.common.usText
import com.myhealth.ui.theme.MyHealthTheme

/** The Sleep screen (0.10.3), opened from the Today "Sleep" tile and from More. */
@Composable
fun SleepScreen(modifier: Modifier = Modifier) {
    val vm = rememberDayVm { graph -> SleepViewModel(graph.profileRepo, graph.healthRepo, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()
    SleepContent(state, modifier)
}

@Composable
private fun SleepContent(state: SleepUiState, modifier: Modifier = Modifier) {
    if (state.isLoading) {
        LoadingBox(modifier.fillMaxSize())
        return
    }
    if (state.nights.isEmpty()) {
        EmptyState(
            title = stringResource(Res.string.sleep_empty_title),
            message = stringResource(Res.string.sleep_empty_message, SLEEP_BAR_NIGHTS),
            icon = Icons.Filled.Bedtime,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LatestNightCard(state.latest, state.today) }
        item { AverageCard(state.averageHours, state.targetHours, state.nights.size) }
        item { ChartCard(state) }
        item {
            SectionCard(title = stringResource(Res.string.sleep_nights_title)) {
                state.nightsNewestFirst.forEachIndexed { index, night ->
                    if (index > 0) HorizontalDivider()
                    NightRow(night)
                }
            }
        }
    }
}

@Composable
private fun LatestNightCard(record: SleepRecord?, today: Long) {
    if (record == null) return
    SectionCard(title = nightTitle(record.night, today)) {
        Text(formatSleepDuration(record.totalSleepMin), style = MaterialTheme.typography.headlineSmall)
        Text(bedtimeLabel(record), style = MaterialTheme.typography.bodyMedium)
        StagesLine(record)
        record.sleepScore?.let {
            Text(stringResource(Res.string.daydetail_sleep_score, it), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AverageCard(averageHours: Double?, targetHours: Double, nights: Int) {
    SectionCard(title = stringResource(Res.string.sleep_average_title, SLEEP_BAR_NIGHTS)) {
        Text(
            text = averageHours?.let { stringResource(Res.string.sleep_hours_format, fmtDecimal(it, 1)) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(Res.string.sleep_average_detail, fmtDecimal(targetHours, 1), nights),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCard(state: SleepUiState) {
    val hours = sleepHoursBars(state.nights, state.fromNight, state.today)
    BarChartCard(
        title = stringResource(Res.string.body_chart_sleep_title, SLEEP_BAR_NIGHTS),
        values = if (hours.all { it == 0.0 }) emptyList() else hours,
        xLabels = nightAxisLabels(state.fromNight, state.today),
        yFormatter = { fmtDecimal(it, 1) },
        highlightIndex = hours.lastIndex.takeIf { it >= 0 },
        emptyMessage = stringResource(Res.string.body_chart_sleep_empty, SLEEP_BAR_NIGHTS),
    )
}

@Composable
private fun NightRow(record: SleepRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(record.night.epochDayDate().usText(NIGHT_ROW_DATE), style = MaterialTheme.typography.bodyLarge)
            Text(
                bedtimeLabel(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StagesLine(record)
        }
        Text(formatSleepDuration(record.totalSleepMin), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StagesLine(record: SleepRecord) {
    val stages = listOfNotNull(
        record.deepMin?.let { stringResource(Res.string.daydetail_sleep_stage_deep, it) },
        record.remMin?.let { stringResource(Res.string.daydetail_sleep_stage_rem, it) },
        record.lightMin?.let { stringResource(Res.string.daydetail_sleep_stage_light, it) },
        record.awakeMin?.let { stringResource(Res.string.daydetail_sleep_stage_awake, it) },
    )
    if (stages.isNotEmpty()) {
        Text(
            text = stages.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Last night", or the date of an older newest night ("Tue 10 Nov"). */
@Composable
fun nightTitle(night: Long, today: Long): String =
    if (isLastNight(night, today)) {
        stringResource(Res.string.today_last_night_label)
    } else {
        night.epochDayDate().usText(NIGHT_ROW_DATE)
    }

/** "Fri 13 Nov": the day the user woke up. */
private const val NIGHT_ROW_DATE = "EEE d MMM"

@Preview
@Composable
private fun SleepContentPreview() {
    val today = 20_500L
    MyHealthTheme(dynamicColor = false) {
        SleepContent(
            SleepUiState(
                isLoading = false,
                today = today,
                nights = (0L until 5L).map { i ->
                    val end = (today - i) * 86_400_000L + 6 * 3_600_000L
                    SleepRecord(
                        id = i, startAtMillis = end - 450 * 60_000L, endAtMillis = end, night = today - i,
                        totalSleepMin = 420 + (i * 7).toInt(), lightMin = 230, deepMin = 80, remMin = 95,
                        awakeMin = 25, stages = null, source = ActivitySource.HEALTH_CONNECT, externalId = null,
                        sleepScore = 78,
                    )
                },
            ),
        )
    }
}
