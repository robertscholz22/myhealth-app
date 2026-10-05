package com.myhealth.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVmWithSavedState
import com.myhealth.domain.model.CalendarDay
import com.myhealth.domain.model.EventOccurrence
import com.myhealth.domain.model.LinkMethod
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/** Callbacks [DayDetailScreen] hands up to the NavHost (§4.1: day detail is a navigation hub).
 * [onEditEvent] also carries the occurrence day, so "delete this occurrence only" in the editor
 * (P3.6) knows which day it was opened from. */
data class DayDetailNavActions(
    val onBack: () -> Unit,
    val onEditEvent: (eventId: Long, occurrenceDay: Long) -> Unit,
    val onEditSession: (Long) -> Unit,
    val onOpenActivity: (Long) -> Unit,
    val onOpenNutrition: (Long) -> Unit,
)

@Composable
fun DayDetailScreen(
    epochDay: Long,
    nav: DayDetailNavActions,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVmWithSavedState { graph, handle ->
        DayDetailViewModel(epochDay, graph.calendarRepo, graph.planRepo, graph.syncScheduler, graph.cycleRepo, handle)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    DayDetailContent(
        state = state,
        nav = nav,
        onPreviousDay = vm::showPreviousDay,
        onNextDay = vm::showNextDay,
        onDeleteEvent = vm::requestDeleteEvent,
        onOpenLinkSheet = vm::openLinkSheet,
        onSetSessionStatus = vm::setSessionStatus,
        onMessageShown = vm::consumeMessage,
        modifier = modifier,
    )

    if (state.pendingDeleteEventId != null) {
        AlertDialog(
            onDismissRequest = vm::cancelDeleteEvent,
            title = { Text(stringResource(Res.string.event_dialog_delete_title)) },
            text = { Text(stringResource(Res.string.daydetail_dialog_delete_event_text)) },
            confirmButton = {
                TextButton(onClick = vm::confirmDeleteEvent) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDeleteEvent) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }

    state.linkSheetOccurrence?.let { occurrence ->
        LinkActivitySheet(
            occurrence = occurrence,
            proposals = state.linkProposals,
            dayActivities = state.data.activities,
            onSelectSuggested = { activityId -> vm.linkActivity(occurrence.eventId, activityId, LinkMethod.AUTO_ACCEPTED) },
            onSelectManual = { activityId -> vm.linkActivity(occurrence.eventId, activityId, LinkMethod.MANUAL) },
            onUnlink = if (occurrence.linkedActivityId != null) {
                { vm.unlinkActivity(occurrence.eventId) }
            } else {
                null
            },
            onDismiss = vm::closeLinkSheet,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailContent(
    state: DayDetailUiState,
    nav: DayDetailNavActions,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDeleteEvent: (Long) -> Unit,
    onOpenLinkSheet: (EventOccurrence) -> Unit,
    onSetSessionStatus: (Long, PlannedStatus) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message.resolveText())
            onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.title) },
                navigationIcon = {
                    IconButton(onClick = nav.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onPreviousDay) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(Res.string.daydetail_previous_day_desc))
                    }
                    IconButton(onClick = onNextDay) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(Res.string.daydetail_next_day_desc))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("events") {
                EventsSection(
                    events = state.data.events,
                    activities = state.data.activities,
                    onEdit = { occurrence -> nav.onEditEvent(occurrence.eventId, occurrence.occurrenceDay) },
                    onDelete = onDeleteEvent,
                    onLink = onOpenLinkSheet,
                )
            }
            item("planned") {
                PlannedSection(
                    sessions = state.data.planned,
                    onSetStatus = onSetSessionStatus,
                    onEdit = nav.onEditSession,
                )
            }
            item("activities") {
                ActivitiesSection(activities = state.data.activities, onOpenActivity = nav.onOpenActivity)
            }
            item("meals") {
                MealsSection(
                    meals = state.data.meals,
                    intake = state.data.intake,
                    onOpenNutrition = { nav.onOpenNutrition(state.day) },
                )
            }
            item("sleep") { SleepSection(state.data.sleep) }
            item("targets") {
                TargetsSection(
                    target = state.data.target,
                    intake = state.data.intake,
                    onOpenNutrition = { nav.onOpenNutrition(state.day) },
                )
            }
            item("load") { LoadSection(state.data.load) }
            if (state.cycleTrackingEnabled) {
                item("cycle") { CycleSection(state.cycleStatus) }
            }
        }
    }
}

private val PREVIEW_NAV = DayDetailNavActions(
    onBack = {},
    onEditEvent = { _, _ -> },
    onEditSession = {},
    onOpenActivity = {},
    onOpenNutrition = {},
)

@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Populated day")
@Composable
private fun DayDetailContentPreview() {
    val day = LocalDate(2026, 9, 14).toEpochDays()
    MyHealthTheme(dynamicColor = false) {
        DayDetailContent(
            state = DayDetailUiState(day = day, isLoading = false, data = previewCalendarDay(day)),
            nav = PREVIEW_NAV,
            onPreviousDay = {},
            onNextDay = {},
            onDeleteEvent = {},
            onOpenLinkSheet = {},
            onSetSessionStatus = { _, _ -> },
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Empty day")
@Composable
private fun DayDetailContentEmptyPreview() {
    val day = LocalDate(2026, 9, 15).toEpochDays()
    MyHealthTheme(dynamicColor = false) {
        DayDetailContent(
            state = DayDetailUiState(day = day, isLoading = false, data = CalendarDay.empty(day)),
            nav = PREVIEW_NAV,
            onPreviousDay = {},
            onNextDay = {},
            onDeleteEvent = {},
            onOpenLinkSheet = {},
            onSetSessionStatus = { _, _ -> },
            onMessageShown = {},
        )
    }
}
