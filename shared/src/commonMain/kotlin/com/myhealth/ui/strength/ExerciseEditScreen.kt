package com.myhealth.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.resources.*
import com.myhealth.ui.common.DropdownField
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.SectionCard
import com.myhealth.ui.common.body.BodyFigure
import com.myhealth.ui.common.body.BodyFigureLegend
import com.myhealth.ui.common.body.BodyFigureLegendKind
import com.myhealth.ui.common.stringResource

/**
 * Create or edit one of the owner's own exercises (P26.2). [onSaved] gets the exercise id — the
 * video import screen and the workout editor use it to put the new exercise on the row that asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseEditScreen(
    exerciseId: String?,
    prefillName: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onUseExisting: (Exercise) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = rememberVm { graph ->
        ExerciseEditViewModel(graph.userExerciseRepo, graph.clock, exerciseId, prefillName)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.done.collect { result ->
            when (result) {
                is ExerciseEditViewModel.Result.Saved -> onSaved(result.id)
                ExerciseEditViewModel.Result.Deleted -> onDeleted()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.draft.isNew) Res.string.exercise_edit_title_new else Res.string.exercise_edit_title_edit,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = vm::save, enabled = !state.loading && !state.saving, modifier = Modifier.testTag("exercise_edit_save")) {
                        Text(stringResource(Res.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.loading) {
            ExerciseEditContent(
                state = state,
                onChange = vm::update,
                onUseExisting = onUseExisting,
                onArchive = vm::setArchived,
                onDelete = { confirmDelete = true },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.exercise_edit_delete_title)) },
            text = { Text(stringResource(Res.string.exercise_edit_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExerciseEditContent(
    state: ExerciseEditUiState,
    onChange: ((ExerciseEditDraft) -> ExerciseEditDraft) -> Unit,
    onUseExisting: (Exercise) -> Unit,
    onArchive: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> onChange { it.copy(name = name) } },
                    label = { Text(stringResource(Res.string.exercise_edit_name_label)) },
                    isError = state.showErrors && draft.nameMissing,
                    supportingText = if (state.showErrors && draft.nameMissing) {
                        { Text(stringResource(Res.string.exercise_edit_error_name)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("exercise_edit_name"),
                )
                state.duplicate?.let { existing ->
                    Text(
                        text = stringResource(Res.string.exercise_edit_duplicate_format, existing.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onUseExisting(existing) }) {
                        Text(stringResource(Res.string.exercise_edit_use_existing_format, existing.name))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !draft.mobility,
                        onClick = { onChange { it.withMobility(false) } },
                        label = { Text(stringResource(Res.string.exercises_kind_strength)) },
                    )
                    FilterChip(
                        selected = draft.mobility,
                        onClick = { onChange { it.withMobility(true) } },
                        label = { Text(stringResource(Res.string.exercises_kind_mobility)) },
                    )
                }
            }
        }
        item {
            SectionCard(title = stringResource(Res.string.exercise_edit_section_muscles)) {
                Text(
                    text = stringResource(Res.string.exercise_edit_muscles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BodyFigure(
                    highlight = draft.highlight,
                    onFrontTap = { muscle -> onChange { it.cycleMuscle(muscle) } },
                    onBackTap = { muscle -> onChange { it.cycleMuscle(muscle) } },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
                BodyFigureLegend(kind = BodyFigureLegendKind.PRIMARY_SECONDARY)
                Text(stringResource(Res.string.exercise_edit_primary_label), style = MaterialTheme.typography.labelLarge)
                MuscleChips(selected = draft.primary, tag = "primary", onToggle = { m -> onChange { it.togglePrimary(m) } })
                if (state.showErrors && draft.primaryMissing) {
                    Text(
                        text = stringResource(Res.string.exercise_edit_error_primary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(Res.string.exercise_edit_secondary_label), style = MaterialTheme.typography.labelLarge)
                MuscleChips(selected = draft.secondary, tag = "secondary", onToggle = { m -> onChange { it.toggleSecondary(m) } })
            }
        }
        item {
            SectionCard(title = stringResource(Res.string.exercise_detail_section_details)) {
                DropdownField(
                    label = stringResource(Res.string.exercise_detail_equipment_label),
                    options = Equipment.entries,
                    selected = draft.equipment,
                    optionLabel = { it.label() },
                    onSelect = { equipment -> onChange { it.copy(equipment = equipment) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!draft.mobility) {
                    val noPattern = stringResource(Res.string.exercise_edit_pattern_none)
                    DropdownField(
                        label = stringResource(Res.string.exercise_detail_pattern_label),
                        options = ExerciseEditDraft.STRENGTH_PATTERNS,
                        selected = draft.pattern,
                        optionLabel = { if (it == MovementPattern.OTHER) noPattern else it.label() },
                        onSelect = { pattern -> onChange { it.copy(pattern = pattern) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchRow(
                        label = stringResource(Res.string.exercise_edit_timed_label),
                        checked = draft.isTimed,
                        onCheckedChange = { timed -> onChange { it.copy(isTimed = timed) } },
                    )
                }
                SwitchRow(
                    label = stringResource(Res.string.exercise_detail_unilateral_flag),
                    checked = draft.unilateral,
                    onCheckedChange = { one -> onChange { it.copy(unilateral = one) } },
                )
                OutlinedTextField(
                    value = draft.notes,
                    onValueChange = { notes -> onChange { it.copy(notes = notes) } },
                    label = { Text(stringResource(Res.string.exercise_edit_notes_label)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!draft.isNew) {
            item {
                SectionCard(title = stringResource(Res.string.exercise_edit_section_manage)) {
                    Text(
                        text = stringResource(Res.string.exercise_edit_load_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.usageCount > 0) {
                        Text(
                            text = stringResource(Res.string.exercise_edit_used_format, state.usageCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = { onArchive(!draft.archived) }) {
                            Text(
                                stringResource(
                                    if (draft.archived) Res.string.exercise_edit_unarchive else Res.string.exercise_edit_archive,
                                ),
                            )
                        }
                    } else {
                        OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("exercise_edit_delete")) {
                            Text(stringResource(Res.string.exercise_edit_delete))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleChips(selected: Set<MuscleGroup>, tag: String, onToggle: (MuscleGroup) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MuscleGroup.entries.forEach { muscle ->
            FilterChip(
                selected = muscle in selected,
                onClick = { onToggle(muscle) },
                label = { Text(muscle.label()) },
                modifier = Modifier.testTag("muscle_${tag}_${muscle.name}"),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** "Not specified" for [MovementPattern.OTHER], the usual label otherwise. */
@Composable
internal fun patternLabel(pattern: MovementPattern): String =
    if (pattern == MovementPattern.OTHER) stringResource(Res.string.exercise_edit_pattern_none) else pattern.label()
