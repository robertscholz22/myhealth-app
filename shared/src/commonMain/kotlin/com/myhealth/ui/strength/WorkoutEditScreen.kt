package com.myhealth.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.strength.ExerciseAnimations
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.NumberField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.body.BodyFigure
import com.myhealth.ui.common.body.StaticBodyFigure
import com.myhealth.ui.common.body.highlightFor
import com.myhealth.ui.common.resolve
import com.myhealth.ui.theme.MyHealthTheme

/** Name, kind, ordered exercise rows, a live figure + estimated minutes (PLAN §4.2 "Workout edit",
 * P14.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditScreen(id: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        WorkoutEditViewModel(id, graph.strengthRepo, graph.profileRepo, graph.currentBodyWeightKg, graph.clock)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) Res.string.workout_edit_new_title else Res.string.workout_edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        WorkoutEditContent(
            state = state,
            onChange = vm::update,
            onAddExercise = { showPicker = true },
            onRemoveExercise = vm::removeExercise,
            onMoveUp = vm::moveUp,
            onMoveDown = vm::moveDown,
            onUpdateRow = vm::updateRow,
            onSave = vm::save,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    if (showPicker) {
        ExercisePickerSheet(
            myEquipment = state.myEquipment,
            initialKind = if (state.draft.kind.isMobility) {
                com.myhealth.domain.engine.strength.ExerciseKind.MOBILITY
            } else {
                null
            },
            onPick = { exercise -> vm.addExercise(exercise.id); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
internal fun WorkoutEditContent(
    state: WorkoutEditUiState,
    onChange: ((WorkoutEditDraft) -> WorkoutEditDraft) -> Unit,
    onAddExercise: () -> Unit,
    onRemoveExercise: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onUpdateRow: (Int, (WorkoutExerciseDraft) -> WorkoutExerciseDraft) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = stringResource(Res.string.workout_edit_section_details)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> onChange { it.copy(name = name) } },
                    label = { Text(stringResource(Res.string.workout_edit_name_label)) },
                    isError = state.validation.nameError,
                    supportingText = if (state.validation.nameError) {
                        { Text(stringResource(Res.string.workout_edit_error_name_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownField(
                    label = stringResource(Res.string.workout_edit_kind_label),
                    options = StrengthWorkoutKind.entries,
                    selected = draft.kind,
                    optionLabel = { it.label() },
                    onSelect = { kind -> onChange { it.copy(kind = kind) } },
                )
            }
        }
        item {
            SectionCard(title = stringResource(Res.string.workout_edit_section_figure)) {
                BodyFigure(highlight = state.highlight, modifier = Modifier.fillMaxWidth().height(160.dp))
                Text(
                    text = stringResource(Res.string.workout_edit_estimated_minutes_format, draft.estimatedMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Text(stringResource(Res.string.workout_edit_section_exercises), style = MaterialTheme.typography.titleMedium)
        }
        draft.exercises.forEachIndexed { index, row ->
            item(key = "row-$index") {
                ExerciseRowCard(
                    row = row,
                    error = state.validation.rowErrors[index],
                    canMoveUp = index > 0,
                    canMoveDown = index < draft.exercises.lastIndex,
                    onRemove = { onRemoveExercise(index) },
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                    onChange = { transform -> onUpdateRow(index, transform) },
                )
            }
        }
        item {
            OutlinedButton(onClick = onAddExercise, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(Res.string.workout_edit_add_exercise))
            }
        }
        item {
            state.saveError?.let { Text(text = it.resolve(), color = MaterialTheme.colorScheme.error) }
            state.loadError?.let { Text(text = it.resolve(), color = MaterialTheme.colorScheme.error) }
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

@Composable
private fun ExerciseRowCard(
    row: WorkoutExerciseDraft,
    error: com.myhealth.ui.common.UiMessage?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChange: ((WorkoutExerciseDraft) -> WorkoutExerciseDraft) -> Unit,
) {
    val exercise = ExerciseCatalog.byId(row.exerciseId)
    SectionCard(
        title = exercise?.name ?: row.exerciseId,
        action = {
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(Res.string.workout_edit_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(Res.string.workout_edit_move_down))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.workout_edit_remove_exercise))
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StaticBodyFigure(
                clip = ExerciseAnimations.clipOrStanding(row.exerciseId),
                highlight = exercise?.let(::highlightFor) ?: emptyMap(),
                sizeDp = 48.dp,
            )
            if (row.reps != null || row.seconds != null) {
                Text(
                    text = prescriptionLabel(row.sets, row.asPrescription()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = stringResource(Res.string.workout_edit_sets_label),
                value = row.sets.toDouble(),
                onValueChange = { value -> onChange { r -> r.copy(sets = (value ?: 1.0).toInt()) } },
                decimals = 0,
                modifier = Modifier.weight(1f),
            )
            if (row.seconds != null) {
                NumberField(
                    label = stringResource(Res.string.workout_edit_seconds_label),
                    value = row.seconds.toDouble(),
                    onValueChange = { value -> onChange { r -> r.copy(seconds = value?.toInt()) } },
                    decimals = 0,
                    modifier = Modifier.weight(1f),
                )
            } else {
                NumberField(
                    label = stringResource(Res.string.workout_edit_reps_label),
                    value = row.reps?.toDouble(),
                    onValueChange = { value -> onChange { r -> r.copy(reps = value?.toInt()) } },
                    decimals = 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = stringResource(Res.string.workout_edit_load_label),
                value = row.loadKg,
                onValueChange = { value -> onChange { r -> r.copy(loadKg = value) } },
                suffix = "kg",
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = stringResource(Res.string.workout_edit_rest_label),
                value = row.restSec?.toDouble(),
                onValueChange = { value -> onChange { r -> r.copy(restSec = value?.toInt()) } },
                decimals = 0,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.workout_edit_bodyweight_label))
            Switch(
                checked = row.isBodyweight,
                onCheckedChange = { checked -> onChange { r -> r.copy(isBodyweight = checked) } },
            )
        }
        OutlinedTextField(
            value = row.note,
            onValueChange = { note -> onChange { r -> r.copy(note = note) } },
            label = { Text(stringResource(Res.string.workout_edit_note_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(text = it.resolve(), color = MaterialTheme.colorScheme.error) }
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkoutEditContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        WorkoutEditContent(
            state = WorkoutEditUiState(
                isLoading = false,
                draft = WorkoutEditDraft(
                    name = "Upper A",
                    kind = StrengthWorkoutKind.UPPER,
                    exercises = listOf(WorkoutExerciseDraft("BARBELL_BENCH_PRESS", reps = 10)),
                ),
            ),
            onChange = {},
            onAddExercise = {},
            onRemoveExercise = {},
            onMoveUp = {},
            onMoveDown = {},
            onUpdateRow = { _, _ -> },
            onSave = {},
        )
    }
}
