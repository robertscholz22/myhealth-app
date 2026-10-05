package com.myhealth.ui.training

import androidx.compose.foundation.layout.Arrangement
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
import com.myhealth.ui.common.mathRound
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.ui.common.DatePickerField
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.DurationField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.TimePickerField
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme

/** Manual planned-session form (PLAN §4.2 "Planned session edit", P6.8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannedSessionEditScreen(
    id: Long,
    epochDay: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        PlannedSessionEditViewModel(id, epochDay, graph.planRepo, graph.profileRepo, graph.strengthRepo, graph.clock, graph.healthRepo, graph.activityRepo)
    }
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
                            stringResource(Res.string.session_new_title)
                        } else {
                            stringResource(Res.string.session_edit_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = vm::requestDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(Res.string.session_delete_desc),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        PlannedSessionEditContent(
            state = state,
            onChange = vm::update,
            onSport = vm::setSport,
            onSessionType = vm::setSessionType,
            onWorkout = vm::setWorkout,
            onSave = vm::save,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    if (state.pendingDelete) {
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text(stringResource(Res.string.session_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.session_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDelete) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun PlannedSessionEditContent(
    state: PlannedSessionEditUiState,
    onChange: ((PlannedSessionDraft) -> PlannedSessionDraft) -> Unit,
    onSport: (SportType) -> Unit,
    onSessionType: (SessionType) -> Unit,
    onWorkout: (Long?) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = stringResource(Res.string.session_section_title)) {
                DropdownField(
                    label = stringResource(Res.string.session_sport_label),
                    options = PLANNABLE_SPORT_TYPES,
                    selected = draft.sportType,
                    optionLabel = { it.planLabel() },
                    onSelect = onSport,
                )
                DropdownField(
                    label = stringResource(Res.string.session_type_label),
                    options = state.sessionTypes,
                    selected = draft.sessionType,
                    optionLabel = { it.label() },
                    onSelect = onSessionType,
                )
                DropdownField(
                    label = stringResource(Res.string.session_intensity_label),
                    options = Intensity.entries,
                    selected = draft.intensity,
                    optionLabel = { it.label() },
                    onSelect = { intensity -> onChange { it.copy(intensity = intensity) } },
                )
                if (draft.sessionType.isStrength() || draft.sessionType.isMobility()) {
                    // P17.2: a MOBILITY session only offers the mobility-kind routines, and a
                    // STRENGTH_* session only the non-mobility workouts — the two never mix.
                    val forMobility = draft.sessionType.isMobility()
                    WorkoutPickerField(
                        workouts = state.workouts.filter { it.kind.isMobility == forMobility },
                        selectedId = draft.workoutId,
                        isMobility = forMobility,
                        onSelect = onWorkout,
                    )
                }
            }
        }
        item {
            SectionCard(title = stringResource(Res.string.session_when_title)) {
                DatePickerField(
                    label = stringResource(Res.string.session_day_label),
                    value = draft.day,
                    onValueChange = { day -> onChange { it.copy(day = day) } },
                )
                TimePickerField(
                    label = stringResource(Res.string.session_start_time_label),
                    value = draft.startMinuteOfDay,
                    onValueChange = { minute -> onChange { it.copy(startMinuteOfDay = minute) } },
                )
            }
        }
        item { TargetsCard(state = state, onChange = onChange) }
        item {
            SectionCard(title = stringResource(Res.string.session_notes_title)) {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { text -> onChange { it.copy(description = text) } },
                    label = { Text(stringResource(Res.string.session_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.session_lock_label))
                    Switch(
                        checked = draft.locked,
                        onCheckedChange = { locked -> onChange { it.copy(locked = locked) } },
                    )
                }
            }
        }
        item {
            state.saveError?.let {
                Text(text = it.resolve(), color = MaterialTheme.colorScheme.error)
            }
            state.loadError?.let {
                Text(text = it.resolve(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onSave,
                enabled = !state.isSaving && state.loadError == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

/** The workout picker a `STRENGTH_*` or `MOBILITY` session type offers (§4.2 "Planned session
 * edit", P14.7, P17.2): "None" plus every existing [com.myhealth.domain.model.StrengthWorkout] of
 * the matching kind, by name. [isMobility] only changes the field's own label — [workouts] is
 * already filtered by the caller. */
@Composable
private fun WorkoutPickerField(
    workouts: List<StrengthWorkout>,
    selectedId: Long?,
    isMobility: Boolean,
    onSelect: (Long?) -> Unit,
) {
    val noneLabel = stringResource(Res.string.session_workout_none)
    val options: List<Long?> = listOf(null) + workouts.map { it.id }
    DropdownField(
        label = stringResource(if (isMobility) Res.string.session_routine_label else Res.string.session_workout_label),
        options = options,
        selected = selectedId,
        optionLabel = { id -> workouts.firstOrNull { it.id == id }?.name ?: noneLabel },
        onSelect = onSelect,
    )
}

@Composable
private fun TargetsCard(
    state: PlannedSessionEditUiState,
    onChange: ((PlannedSessionDraft) -> PlannedSessionDraft) -> Unit,
) {
    val draft = state.draft
    SectionCard(title = stringResource(Res.string.session_targets_title)) {
        DurationField(
            value = draft.durationMin,
            onValueChange = { minutes -> onChange { it.copy(durationMin = minutes) } },
            isError = state.errors.containsKey(PlannedSessionField.DURATION),
            supportingText = state.errors[PlannedSessionField.DURATION],
        )
        NumberField(
            label = stringResource(Res.string.session_distance_label),
            value = draft.distanceKm,
            onValueChange = { km -> onChange { it.copy(distanceKm = km) } },
            suffix = "km",
            decimals = 2,
            isError = state.errors.containsKey(PlannedSessionField.DISTANCE),
            supportingText = state.errors[PlannedSessionField.DISTANCE],
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NumberField(
                label = stringResource(Res.string.session_pace_min_label),
                value = draft.paceMinutes?.toDouble(),
                onValueChange = { value -> onChange { it.copy(paceMinutes = value?.toInt()) } },
                modifier = Modifier.weight(1f),
                decimals = 0,
                isError = state.errors.containsKey(PlannedSessionField.PACE),
            )
            NumberField(
                label = stringResource(Res.string.session_pace_sec_label),
                value = draft.paceSeconds?.toDouble(),
                onValueChange = { value -> onChange { it.copy(paceSeconds = value?.toInt()) } },
                modifier = Modifier.weight(1f),
                decimals = 0,
                isError = state.errors.containsKey(PlannedSessionField.PACE),
            )
        }
        state.errors[PlannedSessionField.PACE]?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        draft.estimatedTrimp?.let { trimp ->
            Text(
                text = stringResource(Res.string.session_estimated_load, mathRound(trimp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TargetZoneChip(draft.sessionType, state.hrZoneModel)
        WorkoutStructureSection(state.structureJson)
    }
}

@Preview(showBackground = true)
@Composable
private fun PlannedSessionEditContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        PlannedSessionEditContent(
            state = PlannedSessionEditUiState(
                isLoading = false,
                isNew = true,
                draft = PlannedSessionDraft(
                    day = LocalDate(2026, 9, 15),
                    sessionType = SessionType.TEMPO_RUN,
                    intensity = Intensity.HIGH,
                    durationMin = 50,
                    distanceKm = 10.0,
                    paceMinutes = 4,
                    paceSeconds = 45,
                ),
            ),
            onChange = {},
            onSport = {},
            onSessionType = {},
            onWorkout = {},
            onSave = {},
        )
    }
}
