package com.myhealth.ui.strength

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.rememberVm
import com.myhealth.domain.engine.strength.ExerciseKind
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.common.EmptyState
import com.myhealth.ui.common.SCREEN_PADDING
import com.myhealth.ui.common.body.BodyFigure
import com.myhealth.ui.theme.MyHealthTheme

/** Searchable exercise catalog with a P17 kind chip, an equipment/muscle filter and a tappable
 * body figure (PLAN §4.2 "Exercises", P14.7, P17.2, More entry). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(onBack: () -> Unit, onOpenExercise: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = rememberVm { graph ->
        ExercisesViewModel(
            graph.strengthRepo,
            graph.profileRepo,
            graph.strengthWorkoutSeeder::seed,
            graph.currentBodyWeightKg,
            graph.clock,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.exercises_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        ExercisesContent(
            state = state,
            onQueryChange = vm::setQuery,
            onKindSelect = vm::setKind,
            onEquipmentSelect = vm::setEquipment,
            onMuscleSelect = vm::setMuscle,
            onOnlyMyEquipmentChange = vm::setOnlyMyEquipment,
            onClearFilters = vm::clearFilters,
            onOpenExercise = onOpenExercise,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
internal fun ExercisesContent(
    state: ExercisesUiState,
    onQueryChange: (String) -> Unit,
    onKindSelect: (ExerciseKind?) -> Unit,
    onEquipmentSelect: (Equipment?) -> Unit,
    onMuscleSelect: (MuscleGroup?) -> Unit,
    onOnlyMyEquipmentChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier.padding(SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(Res.string.exercises_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ExerciseKindFilterRow(state.kind, onKindSelect)
            EquipmentFilterRow(state.equipment, onEquipmentSelect)
            if (state.showOnlyMyEquipmentSwitch) {
                OnlyMyEquipmentRow(checked = state.onlyMyEquipment, onCheckedChange = onOnlyMyEquipmentChange)
            }
            BodyFigure(
                highlight = state.muscle?.let { mapOf(it to 1.0f) } ?: emptyMap(),
                onFrontTap = onMuscleSelect,
                onBackTap = onMuscleSelect,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            if (state.hasActiveFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onClearFilters),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        text = stringResource(Res.string.exercises_clear_filters),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (state.items.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.exercises_empty_title),
                message = stringResource(Res.string.exercises_empty_message),
                modifier = Modifier.padding(SCREEN_PADDING),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.items, key = { it.id }) { exercise ->
                    ExerciseRow(exercise, onClick = { onOpenExercise(exercise.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "Only my equipment" (PLAN §P16 "My equipment", P16.2): shown only when [ExercisesUiState.showOnlyMyEquipmentSwitch]
 * says the profile actually narrowed something. */
@Composable
private fun OnlyMyEquipmentRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.exercises_only_my_equipment_label))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** All / Strength / Mobility (P17.2): `null` is "All". Shared with [ExercisePickerSheet]'s own
 * compact filter row. */
@Composable
internal fun ExerciseKindFilterRow(selected: ExerciseKind?, onSelect: (ExerciseKind?) -> Unit) {
    val kinds: List<ExerciseKind?> = listOf(null, ExerciseKind.STRENGTH, ExerciseKind.MOBILITY)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(kinds) { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(kind.kindLabel()) },
            )
        }
    }
}

@Composable
internal fun ExerciseKind?.kindLabel(): String = when (this) {
    null -> stringResource(Res.string.exercises_kind_all)
    ExerciseKind.STRENGTH -> stringResource(Res.string.exercises_kind_strength)
    ExerciseKind.MOBILITY -> stringResource(Res.string.exercises_kind_mobility)
}

@Composable
private fun EquipmentFilterRow(selected: Equipment?, onSelect: (Equipment?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Equipment.entries) { equipment ->
            FilterChip(
                selected = equipment == selected,
                onClick = { onSelect(if (equipment == selected) null else equipment) },
                label = { Text(equipment.label()) },
            )
        }
    }
}

@Composable
private fun ExerciseRow(exercise: Exercise, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(exercise.name) },
        supportingContent = {
            Text(
                text = exerciseRowSubtitle(exercise),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Foam roller · Glutes, Adductors" (strength) / "Mobility · Glutes, Adductors" (P17.2's mobility
 * drills, whose implement — bodyweight or a foam roller — says less than the fact that it is a
 * mobility exercise) — the exercise list row's, and [ExercisePickerSheet]'s, secondary text. */
@Composable
internal fun exerciseRowSubtitle(exercise: Exercise): String {
    val muscles = exercise.primary.joinToString(", ") { it.label() }
    return if (exercise.isMobility) {
        stringResource(Res.string.exercises_row_subtitle_mobility_format, muscles)
    } else {
        "${exercise.equipment.label()} · $muscles"
    }
}

@Preview(showBackground = true)
@Composable
private fun ExercisesContentPreview() {
    MyHealthTheme(dynamicColor = false) {
        ExercisesContent(
            state = ExercisesUiState(),
            onQueryChange = {},
            onKindSelect = {},
            onEquipmentSelect = {},
            onMuscleSelect = {},
            onOnlyMyEquipmentChange = {},
            onClearFilters = {},
            onOpenExercise = {},
        )
    }
}
