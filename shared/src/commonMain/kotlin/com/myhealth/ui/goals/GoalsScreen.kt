package com.myhealth.ui.goals

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.goal.GoalProgress
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.GoalType
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** Per-row callbacks, grouped so [GoalsContent] keeps a short signature. */
data class GoalListActions(
    val onOpen: (Long) -> Unit = {},
    val onMakePrimary: (Long) -> Unit = {},
    val onAchieved: (Long) -> Unit = {},
    val onAbandoned: (Long) -> Unit = {},
    val onReactivate: (Long) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
)

/** Goals list with progress per goal (PLAN §4.2 "Goals", P6.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onBack: () -> Unit,
    onEditGoal: (Long) -> Unit,
    onNewGoal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        GoalsViewModel(
            goalRepo = graph.goalRepo,
            runningBestRepo = graph.runningBestRepo,
            bodyRepo = graph.bodyRepo,
            activityRepo = graph.activityRepo,
            rideBestRepo = graph.rideBestRepo,
            profileRepo = graph.profileRepo,
            clock = graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.goals_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewGoal) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.goals_new_content_description))
            }
        },
    ) { innerPadding ->
        GoalsContent(
            state = state,
            actions = GoalListActions(
                onOpen = onEditGoal,
                onMakePrimary = vm::makePrimary,
                onAchieved = vm::markAchieved,
                onAbandoned = vm::markAbandoned,
                onReactivate = vm::reactivate,
                onDelete = vm::delete,
            ),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
internal fun GoalsContent(
    state: GoalsUiState,
    actions: GoalListActions,
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) {
        EmptyState(
            title = stringResource(Res.string.goals_empty_title),
            message = stringResource(Res.string.goals_empty_message),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.showCycleCapHint) {
            item {
                Text(
                    text = stringResource(Res.string.goals_cycle_cap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(state.active, key = { it.goal.id }) { row -> GoalCard(row, actions) }
        if (state.archived.isNotEmpty()) {
            item { Text(stringResource(Res.string.goals_archived_header), style = MaterialTheme.typography.titleMedium) }
            items(state.archived, key = { it.goal.id }) { row -> GoalCard(row, actions) }
        }
    }
}

@Composable
private fun GoalCard(row: GoalRow, actions: GoalListActions) {
    val goal = row.goal
    SectionCard(
        title = goal.title,
        modifier = Modifier.clickable { actions.onOpen(goal.id) },
        action = {
            if (row.isPrimary) {
                AssistChip(onClick = {}, label = { Text(stringResource(Res.string.goals_primary_chip)) })
            } else if (goal.status != GoalStatus.ACTIVE) {
                AssistChip(onClick = {}, label = { Text(goalStatusLabel(goal.status)) })
            }
        },
    ) {
        Text(goalHeadline(goal), style = MaterialTheme.typography.bodyMedium)
        // P19: no recent effort → no bar and no "behind" red; the text asks for a time trial.
        if (!row.progress.isManual && !row.progress.isUnknown) {
            LinearProgressIndicator(
                progress = { row.progress.percent.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = row.progress.statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.progress.onTrack || row.progress.isUnknown) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        GoalActionRow(row, actions)
    }
}

@Composable
private fun GoalActionRow(row: GoalRow, actions: GoalListActions) {
    val goal = row.goal
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (goal.status == GoalStatus.ACTIVE) {
            if (!row.isPrimary) {
                TextButton(onClick = { actions.onMakePrimary(goal.id) }) {
                    Text(stringResource(Res.string.goals_action_make_primary))
                }
            }
            TextButton(onClick = { actions.onAchieved(goal.id) }) { Text(stringResource(Res.string.goals_action_achieved)) }
            TextButton(onClick = { actions.onAbandoned(goal.id) }) { Text(stringResource(Res.string.goals_action_abandon)) }
        } else {
            TextButton(onClick = { actions.onReactivate(goal.id) }) {
                Text(stringResource(Res.string.goals_action_reactivate))
            }
            TextButton(onClick = { actions.onDelete(goal.id) }) { Text(stringResource(Res.string.action_delete)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GoalsContentPreview() {
    val race = com.myhealth.domain.model.Goal(
        id = 1L,
        type = GoalType.RACE_TIME,
        title = "Sub-20 5k",
        targetDay = LocalDate(2026, 11, 15).toEpochDays(),
        targetDistanceMeters = 5000.0,
        targetTimeSec = 1200,
        targetWeightKg = null,
        targetValue = null,
        priority = 1,
        status = GoalStatus.ACTIVE,
        linkedEventId = null,
        notes = null,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
    )
    MyHealthTheme {
        Column {
            GoalsContent(
                state = GoalsUiState(
                    isLoading = false,
                    active = listOf(
                        GoalRow(
                            goal = race,
                            progress = GoalProgress.Progress(
                                percent = 0.94,
                                statusText = "Current best 21:14 · predicted 20:40 — behind.",
                                onTrack = false,
                            ),
                        ),
                    ),
                ),
                actions = GoalListActions(),
            )
        }
    }
}
