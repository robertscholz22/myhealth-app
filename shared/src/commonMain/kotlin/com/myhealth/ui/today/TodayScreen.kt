package com.myhealth.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.calendar.LinkProposal
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.MacroTotals
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.ui.activities.formatDuration
import com.myhealth.ui.calendar.confidenceLabel
import com.myhealth.ui.calendar.targetProgressRows
import com.myhealth.ui.nutrition.NO_TARGET_MESSAGE
import com.myhealth.ui.nutrition.energyFraction
import com.myhealth.ui.nutrition.remainingLabel
import com.myhealth.ui.nutrition.roundHalfUp
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.ErrorBanner
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.StatTile
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.fmtDecimal
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.TimeZone

@Composable
fun TodayScreen(
    onOpenActivity: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenNutrition: () -> Unit,
    onOpenLoad: () -> Unit,
    onOpenTraining: () -> Unit,
    onReviewSuggestions: () -> Unit,
    onOpenCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        TodayViewModel(
            graph.activityRepo,
            graph.bodyRepo,
            graph.profileRepo,
            graph.healthRepo,
            graph.mealRepo,
            graph.nutritionRepo,
            graph.syncStateRepo,
            graph.syncScheduler,
            graph.calendarRepo,
            graph.loadRepo,
            graph.planRepo,
            graph.suggestionRepo,
            graph.cycleRepo,
            graph.strengthRepo,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    TodayContent(
        state = state,
        onSyncNow = vm::syncNow,
        onOpenActivity = onOpenActivity,
        onOpenDay = onOpenDay,
        onOpenNutrition = onOpenNutrition,
        onOpenLoad = onOpenLoad,
        onAcceptSuggestion = vm::acceptSuggestion,
        onDismissSuggestion = vm::dismissSuggestion,
        onMarkPlannedDone = vm::markPlannedDone,
        onOpenTraining = onOpenTraining,
        onReviewSuggestions = onReviewSuggestions,
        onOpenCycle = onOpenCycle,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayContent(
    state: TodayUiState,
    onSyncNow: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenNutrition: () -> Unit,
    onOpenLoad: () -> Unit,
    onAcceptSuggestion: (LinkProposal) -> Unit,
    onDismissSuggestion: (LinkProposal) -> Unit,
    onMarkPlannedDone: (Long) -> Unit,
    onOpenTraining: () -> Unit,
    onReviewSuggestions: () -> Unit,
    onOpenCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // P8.6 — pull down anywhere on Today to run the same sync as the banner's "Sync now".
    PullToRefreshBox(
        isRefreshing = state.isSyncing,
        onRefresh = onSyncNow,
        modifier = modifier.fillMaxSize(),
    ) {
        TodayList(
            state = state,
            onSyncNow = onSyncNow,
            onOpenActivity = onOpenActivity,
            onOpenDay = onOpenDay,
            onOpenNutrition = onOpenNutrition,
            onOpenLoad = onOpenLoad,
            onAcceptSuggestion = onAcceptSuggestion,
            onDismissSuggestion = onDismissSuggestion,
            onMarkPlannedDone = onMarkPlannedDone,
            onOpenTraining = onOpenTraining,
            onReviewSuggestions = onReviewSuggestions,
            onOpenCycle = onOpenCycle,
        )
    }
}

@Composable
private fun TodayList(
    state: TodayUiState,
    onSyncNow: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenNutrition: () -> Unit,
    onOpenLoad: () -> Unit,
    onAcceptSuggestion: (LinkProposal) -> Unit,
    onDismissSuggestion: (LinkProposal) -> Unit,
    onMarkPlannedDone: (Long) -> Unit,
    onOpenTraining: () -> Unit,
    onReviewSuggestions: () -> Unit,
    onOpenCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SyncStatusBanner(state, onSyncNow) }
        if (state.linkSuggestions.isNotEmpty()) {
            item { SuggestedLinksCard(state.linkSuggestions, onAcceptSuggestion, onDismissSuggestion) }
        }
        item { NutritionCard(state.target, state.intake, onOpenNutrition) }
        item {
            TodayPlanCard(
                planned = state.plannedToday,
                suggested = state.suggestedToday,
                isStale = state.suggestionsStale,
                hrZoneModel = state.hrZoneModel,
                onMarkDone = onMarkPlannedDone,
                onReviewSuggestions = onReviewSuggestions,
                onOpenTraining = onOpenTraining,
            )
        }
        item { RecoveryCard(state.latestLoad, state.topRecoveryFlag, onOpenLoad) }
        item { LoadCard(state.latestLoad, state.weeklyTrimp, state.muscleLoad, onOpenLoad) }
        if (state.cycleTrackingEnabled) {
            item { TodayCycleCard(state.cycleStatus, onOpenCycle) }
        }
        item { TodayActivitiesSection(state.activities, onOpenActivity) { onOpenDay(state.day) } }
        item { BodyChip(state.weightChipText) }
        item { SleepTile(state.sleep) }
    }
}

/** "Suggested links" card (§4.2 Today, P3.7): shown when ≥ 1 undismissed proposal has
 * `confidence >= EventActivityLinker.PROPOSE_THRESHOLD` (already true of everything
 * `TodayViewModel` puts in [TodayUiState.linkSuggestions]). */
@Composable
private fun SuggestedLinksCard(
    suggestions: List<LinkProposal>,
    onAccept: (LinkProposal) -> Unit,
    onDismiss: (LinkProposal) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.today_suggested_links_title)) {
        suggestions.forEach { proposal ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(proposal.eventOccurrence.effectiveTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${proposal.activity.title ?: proposal.activity.sportType.displayName()} · " +
                            confidenceLabel(proposal.confidence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onDismiss(proposal) }) { Text(stringResource(Res.string.today_dismiss)) }
                Button(onClick = { onAccept(proposal) }) { Text(stringResource(Res.string.today_accept)) }
            }
        }
    }
}

/**
 * Today's nutrition card (§4.2 Today, P4.12): the energy bar with the remaining kcal, one bar per
 * macro, and a tap through to the diary. Without a snapshot yet it says so instead of showing zeros
 * as if they were targets.
 */
@Composable
private fun NutritionCard(
    target: NutritionTarget?,
    intake: MacroTotals,
    onOpenNutrition: () -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.today_nutrition_title),
        modifier = Modifier.clickable(onClick = onOpenNutrition),
        action = { TextButton(onClick = onOpenNutrition) { Text(stringResource(Res.string.today_diary)) } },
    ) {
        if (target == null) {
            Text(NO_TARGET_MESSAGE, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${roundHalfUp(intake.kcal)} / ${target.kcal} kcal",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = remainingLabel(target, intake).orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { energyFraction(target, intake) },
            modifier = Modifier.fillMaxWidth(),
        )
        targetProgressRows(target, intake).drop(1).forEach { row ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                    Text(row.valueLabel, style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { row.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncStatusBanner(state: TodayUiState, onSyncNow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.lastSyncError != null) {
            ErrorBanner(message = stringResource(Res.string.today_sync_failed, state.lastSyncError), onRetry = onSyncNow)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = lastSyncedLabel(state.lastSyncSuccessAtMillis, TimeZone.currentSystemDefault()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSyncNow, enabled = !state.isSyncing) {
                    Text(stringResource(Res.string.today_sync_now))
                }
            }
        }
    }
}

@Composable
private fun TodayActivitiesSection(
    activities: List<ActivitySummary>,
    onOpenActivity: (Long) -> Unit,
    onOpenDay: () -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.today_activities_title),
        action = { TextButton(onClick = onOpenDay) { Text(stringResource(Res.string.today_day_detail)) } },
    ) {
        if (activities.isEmpty()) {
            EmptyState(stringResource(Res.string.today_empty_activities_title), stringResource(Res.string.today_empty_activities_message))
        } else {
            activities.forEach { activity ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenActivity(activity.id) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SportIcon(activity.sportGroup)
                    Column {
                        Text(
                            text = activity.title?.takeIf { it.isNotBlank() } ?: activity.sportType.displayName(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(formatDuration(activity.durationSec), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyChip(weightChipText: String?) {
    SectionCard(title = stringResource(Res.string.today_body_title)) {
        Text(weightChipText ?: stringResource(Res.string.today_no_weight), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SleepTile(sleep: SleepRecord?) {
    SectionCard(title = stringResource(Res.string.today_sleep_title)) {
        if (sleep == null) {
            Text(stringResource(Res.string.today_no_sleep), style = MaterialTheme.typography.bodyMedium)
        } else {
            val hours = sleep.totalSleepMin / 60.0
            StatTile(label = stringResource(Res.string.today_last_night_label), value = fmtDecimal(hours, 1), unit = "h")
        }
    }
}
