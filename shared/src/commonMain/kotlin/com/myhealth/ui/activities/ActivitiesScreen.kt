package com.myhealth.ui.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.fmtInt
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.LoadingBox
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SourceBadgeRow
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.usText
import com.myhealth.ui.theme.MyHealthTheme
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ActivitiesScreen(onOpenDetail: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> ActivitiesViewModel(graph.activityRepo, graph.syncScheduler) }
    val state by vm.state.collectAsStateWithLifecycle()

    ActivitiesContent(
        state = state,
        onFilterSelect = vm::setFilter,
        onSyncNow = vm::syncNow,
        onOpenDetail = onOpenDetail,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitiesContent(
    state: ActivitiesUiState,
    onFilterSelect: (SportGroup?) -> Unit,
    onSyncNow: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FilterChipsRow(selected = state.filter, onSelect = onFilterSelect)
        // P8.6: a failed sync is the one error this screen can surface, and it is retryable.
        state.syncError?.let { reason ->
            ErrorBanner(
                message = stringResource(Res.string.activities_sync_failed, reason),
                onRetry = onSyncNow,
                modifier = Modifier.padding(horizontal = SCREEN_PADDING, vertical = 8.dp),
            )
        }
        // P8.6: pull down to run the same "Sync now" work the empty state offers.
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = onSyncNow,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading -> LoadingBox(modifier = Modifier.fillMaxSize())
                state.isEmpty -> EmptyState(
                    title = stringResource(Res.string.activities_empty_title),
                    message = stringResource(Res.string.activities_empty_message),
                    actionLabel = stringResource(Res.string.activities_empty_action),
                    onAction = onSyncNow,
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = SCREEN_PADDING),
                ) {
                    state.groups.forEach { group ->
                        item(key = "header-${group.label}") { MonthHeader(group.label) }
                        items(group.items, key = { it.id }) { activity ->
                            ActivityRow(activity = activity, onClick = { onOpenDetail(activity.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(selected: SportGroup?, onSelect: (SportGroup?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(Res.string.activities_filter_all)) },
            )
        }
        items(SportGroup.entries.toList()) { group ->
            FilterChip(
                selected = selected == group,
                onClick = { onSelect(group) },
                label = { Text(group.displayName()) },
            )
        }
    }
}

@Composable
private fun MonthHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActivityRow(activity: ActivitySummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SportIcon(activity.sportGroup)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activity.title?.takeIf { it.isNotBlank() } ?: activity.sportType.displayName(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatStartAt(activity.startAtMillis), style = MaterialTheme.typography.bodySmall)
            Text(text = activityRowStatsLine(activity), style = MaterialTheme.typography.bodySmall)
            if (activity.mergedSources.isNotEmpty()) {
                SourceBadgeRow(activity.mergedSources, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** "48 min · 8.32 km · 142 bpm · TRIMP 108" — only the parts that have data. */
private fun activityRowStatsLine(activity: ActivitySummary): String = buildList {
    add(formatDuration(activity.durationSec))
    formatDistanceKm(activity.distanceMeters)?.let { add(it) }
    activity.avgHr?.let { add("$it bpm") }
    activity.trimp?.let { add("TRIMP ${fmtInt(it.roundToDisplayInt())}") }
}.joinToString(" · ")

private fun Double.roundToDisplayInt(): Int = mathRound(this).toInt()

private fun formatStartAt(startAtMillis: Long): String =
    Instant.fromEpochMilliseconds(startAtMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        .usText("MMM d, HH:mm")

@Preview(showBackground = true, name = "Populated")
@Composable
private fun ActivitiesContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        ActivitiesContent(
            state = ActivitiesUiState(
                isLoading = false,
                groups = listOf(
                    MonthGroup(
                        label = "September 2026",
                        items = listOf(
                            previewActivity(1, SportType.RUN_OUTDOOR, SportGroup.RUN, "Morning run"),
                            previewActivity(2, SportType.STRENGTH, SportGroup.STRENGTH, null),
                        ),
                    ),
                ),
            ),
            onFilterSelect = {},
            onSyncNow = {},
            onOpenDetail = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun ActivitiesContentEmptyPreview() {
    MyHealthTheme(dynamicColor = false) {
        ActivitiesContent(
            state = ActivitiesUiState(isLoading = false, groups = emptyList()),
            onFilterSelect = {},
            onSyncNow = {},
            onOpenDetail = {},
        )
    }
}

private fun previewActivity(
    id: Long,
    sportType: SportType,
    sportGroup: SportGroup,
    title: String?,
): ActivitySummary = ActivitySummary(
    id = id,
    startAtMillis = 1_757_000_000_000L,
    endAtMillis = 1_757_003_600_000L,
    day = 19980,
    sportType = sportType,
    sportGroup = sportGroup,
    title = title,
    durationSec = 2880,
    elapsedSec = 3000,
    distanceMeters = 8320.0,
    activeEnergyKcal = 540.0,
    totalEnergyKcal = 640.0,
    avgHr = 142,
    maxHr = 168,
    avgSpeedMps = 2.89,
    maxSpeedMps = 4.1,
    avgCadenceSpm = 172.0,
    elevationGainM = 45.0,
    trimp = 108.1,
    loadMethod = LoadMethod.HR_SAMPLES,
    rpe = null,
    note = null,
    primarySource = ActivitySource.HEALTH_CONNECT,
    mergedSources = listOf(ActivitySource.HEALTH_CONNECT, ActivitySource.FIT_IMPORT),
    hasStreams = true,
)
