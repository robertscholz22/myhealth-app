package com.myhealth.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.SportType
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.DurationField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.TimePickerField
import com.myhealth.ui.common.UiMessage
import com.myhealth.ui.common.displayName
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/**
 * Create/edit screen for a [com.myhealth.domain.model.CalendarEvent] (PLAN §4.2 Event edit,
 * P3.6). `id = -1` creates a new event on [epochDay]; otherwise the event is loaded and
 * [epochDay] names the occurrence the delete flow's "this occurrence only" applies to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(id: Long, epochDay: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph -> EventEditViewModel(id, epochDay, graph.calendarRepo, graph.syncScheduler, graph.clock) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isNew) {
                            stringResource(Res.string.event_title_new)
                        } else {
                            stringResource(Res.string.event_title_edit)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = vm::requestDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.event_action_delete_desc))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        EventEditBody(
            state = state,
            onDraftChange = vm::updateDraft,
            onSave = vm::save,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    if (state.pendingDelete) {
        DeleteEventDialog(
            isRecurring = state.isRecurring,
            onDeleteOccurrence = vm::deleteOccurrence,
            onDeleteSeries = vm::deleteSeries,
            onCancel = vm::cancelDelete,
        )
    }
}

@Composable
private fun EventEditBody(
    state: EventEditUiState,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.loadError != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { Text(state.loadError.resolve()) }
        return
    }

    val draft = state.draft
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EventTypeCard(draft, state.errors, onDraftChange) }
            item { WhenCard(draft, state.errors, onDraftChange) }
            if (draft.type.usesSportType() || draft.type.usesTargetDistance()) {
                item { SportDetailsCard(draft, state.errors, onDraftChange) }
            }
            item { RecurrencePicker(draft, state.errors, onDraftChange) }
            item { NotesCard(draft, onDraftChange) }
            state.saveError?.let { message ->
                item { Text(message.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onSave, enabled = !state.isSaving) { Text(stringResource(Res.string.action_save)) }
        }
    }
}

@Composable
private fun EventTypeCard(
    draft: EventDraft,
    errors: Map<EventField, UiMessage>,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.event_section_event)) {
        DropdownField(
            label = stringResource(Res.string.event_label_type),
            options = EventType.entries,
            selected = draft.type,
            optionLabel = { it.displayName() },
            onSelect = { type ->
                onDraftChange { it.copy(type = type, sportType = it.sportType ?: defaultSportTypeFor(type)) }
            },
        )
        OutlinedTextField(
            value = draft.title,
            onValueChange = { title -> onDraftChange { it.copy(title = title) } },
            label = { Text(stringResource(Res.string.event_label_title)) },
            singleLine = true,
            isError = errors.containsKey(EventField.TITLE),
            supportingText = errors[EventField.TITLE]?.let { { Text(it.resolve()) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.event_label_key_event), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = draft.isKeyEvent, onCheckedChange = { v -> onDraftChange { it.copy(isKeyEvent = v) } })
        }
    }
}

@Composable
private fun WhenCard(
    draft: EventDraft,
    errors: Map<EventField, UiMessage>,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.event_section_when)) {
        DatePickerField(
            label = stringResource(Res.string.event_label_date),
            value = draft.date,
            onValueChange = { d -> onDraftChange { it.copy(date = d) } },
            isError = errors.containsKey(EventField.DATE),
            supportingText = errors[EventField.DATE]?.resolve(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.event_label_all_day), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = !draft.hasTime, onCheckedChange = { allDay -> onDraftChange { it.copy(hasTime = !allDay) } })
        }
        if (draft.hasTime) {
            TimePickerField(
                label = stringResource(Res.string.event_label_start_time),
                value = draft.startMinuteOfDay,
                onValueChange = { m -> onDraftChange { it.copy(startMinuteOfDay = m) } },
            )
        }
        DurationField(
            value = draft.durationMin,
            onValueChange = { m -> onDraftChange { it.copy(durationMin = m) } },
            isError = errors.containsKey(EventField.DURATION),
            supportingText = errors[EventField.DURATION]?.resolve(),
        )
        OutlinedTextField(
            value = draft.location,
            onValueChange = { loc -> onDraftChange { it.copy(location = loc) } },
            label = { Text(stringResource(Res.string.event_label_location)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SportDetailsCard(
    draft: EventDraft,
    errors: Map<EventField, UiMessage>,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.event_label_sport)) {
        if (draft.type.usesSportType()) {
            DropdownField(
                label = stringResource(Res.string.event_label_sport),
                options = SportType.entries,
                selected = draft.sportType ?: defaultSportTypeFor(draft.type) ?: SportType.OTHER,
                optionLabel = { it.displayName() },
                onSelect = { sport -> onDraftChange { it.copy(sportType = sport) } },
            )
        }
        if (draft.type.usesTargetDistance()) {
            NumberField(
                label = stringResource(Res.string.event_label_target_distance),
                value = draft.targetDistanceKm,
                onValueChange = { v -> onDraftChange { it.copy(targetDistanceKm = v) } },
                suffix = stringResource(Res.string.event_unit_km),
                decimals = 2,
                isError = errors.containsKey(EventField.DISTANCE),
                supportingText = errors[EventField.DISTANCE]?.resolve(),
            )
        }
    }
}

@Composable
private fun NotesCard(draft: EventDraft, onDraftChange: ((EventDraft) -> EventDraft) -> Unit) {
    SectionCard(title = stringResource(Res.string.event_section_notes)) {
        OutlinedTextField(
            value = draft.notes,
            onValueChange = { n -> onDraftChange { it.copy(notes = n) } },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeleteEventDialog(
    isRecurring: Boolean,
    onDeleteOccurrence: () -> Unit,
    onDeleteSeries: () -> Unit,
    onCancel: () -> Unit,
) {
    if (isRecurring) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(Res.string.event_dialog_delete_recurring_title)) },
            text = { Text(stringResource(Res.string.event_dialog_delete_recurring_text)) },
            confirmButton = {
                Row {
                    TextButton(onClick = onDeleteOccurrence) { Text(stringResource(Res.string.event_action_this_occurrence)) }
                    TextButton(onClick = onDeleteSeries) { Text(stringResource(Res.string.event_action_whole_series)) }
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(Res.string.event_dialog_delete_title)) },
            text = { Text(stringResource(Res.string.event_dialog_delete_text)) },
            confirmButton = { TextButton(onClick = onDeleteSeries) { Text(stringResource(Res.string.action_delete)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 800, name = "New event")
@Composable
private fun EventEditBodyNewPreview() {
    MyHealthTheme(dynamicColor = false) {
        EventEditBody(
            state = EventEditUiState(isLoading = false, isNew = true, draft = newEventDraft(LocalDate(2026, 9, 14))),
            onDraftChange = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 800, name = "Edit recurring race")
@Composable
private fun EventEditBodyEditPreview() {
    val draft = EventDraft(
        id = 5,
        type = EventType.RACE,
        title = "City half marathon",
        date = LocalDate(2026, 10, 4),
        hasTime = true,
        startMinuteOfDay = 9 * 60,
        durationMin = 120,
        sportType = SportType.RUN_OUTDOOR,
        targetDistanceKm = 21.1,
        isKeyEvent = true,
    )
    MyHealthTheme(dynamicColor = false) {
        EventEditBody(
            state = EventEditUiState(isLoading = false, isNew = false, draft = draft),
            onDraftChange = {},
            onSave = {},
        )
    }
}
