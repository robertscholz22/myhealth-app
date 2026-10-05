package com.myhealth.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.load.HrZoneModel
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.ui.calendar.displayName
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.RationaleList
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.SportIcon
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The generated week, one card per suggestion with its rationale open (PLAN §4.2 "Suggestion
 * review", P6.7). Every card starts accepted; the owner unticks what they do not want and
 * "Accept selected" writes the whole verdict at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionReviewScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        SuggestionReviewViewModel(graph.suggestionRepo, graph.settings, graph.profileRepo, graph.clock, graph.planRepo, graph.healthRepo, graph.activityRepo)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.resolveText())
            vm.consumeMessage()
        }
    }
    LaunchedEffect(state.done) {
        if (state.done) {
            vm.consumeDone()
            onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = vm::acceptAll, enabled = state.isReviewable) {
                        Text(stringResource(Res.string.review_select_all))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        SuggestionReviewContent(
            state = state,
            onToggle = vm::toggle,
            onAccept = vm::acceptSelected,
            onRegenerate = vm::regenerate,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
internal fun SuggestionReviewContent(
    state: SuggestionReviewUiState,
    onToggle: (Long) -> Unit,
    onAccept: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) {
        EmptyState(
            title = stringResource(Res.string.review_empty_title),
            message = stringResource(Res.string.review_empty_message),
            actionLabel = stringResource(Res.string.review_generate),
            onAction = onRegenerate,
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ReviewHeaderCard(state = state, onAccept = onAccept, onRegenerate = onRegenerate) }
        items(state.rows, key = { row -> row.session?.id ?: -row.day }) { row ->
            val session = row.session
            if (session == null) {
                RestDayCard(day = row.day)
            } else {
                SuggestionCard(
                    day = row.day,
                    session = session,
                    isAccepted = state.isAccepted(session.id),
                    onToggle = { onToggle(session.id) },
                    hrZoneModel = state.hrZoneModel,
                )
            }
        }
    }
}

@Composable
private fun ReviewHeaderCard(
    state: SuggestionReviewUiState,
    onAccept: () -> Unit,
    onRegenerate: () -> Unit,
) {
    SectionCard(
        title = stringResource(Res.string.review_proposal_title),
        action = {
            state.phase?.let { phase ->
                AssistChip(onClick = {}, enabled = false, label = { Text(phase.label()) })
            }
        },
    ) {
        Text(
            text = state.headerLine(
                targetLabel = stringResource(Res.string.review_target_label),
                suggestedLabel = stringResource(Res.string.review_suggested_label),
                restDaySingular = stringResource(Res.string.review_rest_day_singular),
                restDayPlural = stringResource(Res.string.review_rest_day_plural),
                replacesSingular = stringResource(Res.string.review_replaces_singular),
                replacesPlural = stringResource(Res.string.review_replaces_plural),
                fixedCoversFormat = stringResource(Res.string.review_fixed_load_covers_target),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                Res.string.review_selected_summary,
                state.selectedIds.size,
                state.sessions.size,
                mathRound(state.selectedLoad),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onAccept, enabled = state.isReviewable && !state.isWorking) {
                Text(stringResource(Res.string.review_accept_selected))
            }
            OutlinedButton(onClick = onRegenerate, enabled = !state.isWorking) {
                Text(stringResource(Res.string.training_action_regenerate))
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    day: Long,
    session: SuggestedSession,
    isAccepted: Boolean,
    onToggle: () -> Unit,
    hrZoneModel: HrZoneModel?,
) {
    SectionCard(
        title = dayHeaderLabel(day),
        action = { Checkbox(checked = isAccepted, onCheckedChange = { onToggle() }) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SportIcon(session.sportType.group, modifier = Modifier.size(20.dp))
            Text(
                text = session.sessionType.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            AssistChip(onClick = {}, enabled = false, label = { Text(session.intensity.label()) })
        }
        Text(
            text = listOfNotNull(
                session.targetDurationMin?.let { "$it min" },
                session.targetPaceSecPerKm?.let { formatPaceSecPerKm(it) },
                "${mathRound(session.estimatedTrimp)} AU",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TargetZoneChip(session.sessionType, hrZoneModel)
        WorkoutStructureSection(session.structureJson)
        RationaleList(session.rationale)
    }
}

@Composable
private fun RestDayCard(day: Long) {
    SectionCard(title = dayHeaderLabel(day)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.review_rest_day_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SuggestionReviewContentPreview() {
    val monday = 20_709L
    val batch = SuggestionBatch(
        id = 1L,
        generatedAtMillis = 0L,
        horizonStartDay = monday,
        horizonEndDay = monday + 7,
        phase = TrainingPhase.BUILD,
        weeklyLoadTarget = 620.0,
        inputsHash = "preview",
        status = SuggestionStatus.PROPOSED,
    )
    val sessions = listOf(
        previewSuggestion(1L, monday, SessionType.EASY_RUN, Intensity.LOW, 45, 54.0),
        previewSuggestion(2L, monday + 1, SessionType.TEMPO_RUN, Intensity.HIGH, 50, 105.0),
        previewSuggestion(3L, monday + 3, SessionType.STRENGTH_LOWER, Intensity.HIGH, 55, 115.0),
        previewSuggestion(4L, monday + 5, SessionType.LONG_RUN, Intensity.MODERATE, 80, 120.0),
        previewSuggestion(5L, monday + 6, SessionType.MOBILITY, Intensity.RECOVERY, 20, 12.0),
    )
    MyHealthTheme(dynamicColor = false) {
        Column {
            SuggestionReviewContent(
                state = SuggestionReviewUiState(
                    isLoading = false,
                    batch = batch,
                    rows = suggestionRows(batch, sessions),
                    accepted = mapOf(3L to false),
                ),
                onToggle = {},
                onAccept = {},
                onRegenerate = {},
            )
        }
    }
}

private fun previewSuggestion(
    id: Long,
    day: Long,
    sessionType: SessionType,
    intensity: Intensity,
    minutes: Int,
    trimp: Double,
): SuggestedSession = SuggestedSession(
    id = id,
    batchId = 1L,
    day = day,
    sportType = if (sessionType == SessionType.STRENGTH_LOWER) SportType.STRENGTH else SportType.RUN_OUTDOOR,
    sessionType = sessionType,
    intensity = intensity,
    targetDurationMin = minutes,
    targetDistanceMeters = null,
    estimatedTrimp = trimp,
    score = 0.8 - id * 0.05,
    rationale = listOf(
        RationaleEntry("PHASE_BUILD", "Build phase: this develops threshold for your 5k goal"),
        RationaleEntry("BUDGET", "Weekly load target 620 AU; 180 AU still unallocated"),
    ),
    status = SuggestionStatus.PROPOSED,
)
