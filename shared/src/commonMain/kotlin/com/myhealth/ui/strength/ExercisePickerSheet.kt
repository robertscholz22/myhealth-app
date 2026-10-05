package com.myhealth.ui.strength

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.strength.ExerciseAnimations
import com.myhealth.domain.engine.strength.ExerciseCatalog
import com.myhealth.domain.engine.strength.ExerciseKind
import com.myhealth.domain.model.Equipment
import com.myhealth.domain.model.Exercise
import com.myhealth.ui.common.body.StaticBodyFigure
import com.myhealth.ui.common.body.highlightFor

/**
 * Bottom sheet to add an exercise to the workout being edited (PLAN §4.2 "Workout edit", P14.7,
 * P17.2): a search field over [ExerciseCatalog] (name and muscle names both match), the same P17
 * kind chip row as the Exercises screen, the same "only my equipment" default filter (P16.2, with a
 * "show all" toggle since there is no room here for the full equipment chip row), and a tappable
 * list. Self-contained like `LinkActivitySheet` — its query, kind and toggle are local, ephemeral
 * form state, not part of [WorkoutEditUiState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    onPick: (Exercise) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** `profile.availableEquipmentJson`, decoded (P16.2). `null` means everything — the toggle is
     * not shown, since there is nothing to filter down to. */
    myEquipment: Set<Equipment>? = null,
    /** The kind chip's starting position (P17.2): `null` (All) normally, `MOBILITY` when the
     * workout being edited is itself a mobility kind. */
    initialKind: ExerciseKind? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(initialKind) }
    var showAll by remember { mutableStateOf(false) }
    val results = remember(query, kind, showAll, myEquipment) {
        val bySearch = ExerciseCatalog.search(query, kind)
        if (!showAll && myEquipment != null) bySearch.filter { it.equipment in myEquipment } else bySearch
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(Res.string.exercise_picker_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(Res.string.exercises_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            ExerciseKindFilterRow(kind, onSelect = { kind = it })
            if (myEquipment != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.exercise_picker_show_all_label))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { it.id }) { exercise ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(exercise) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StaticBodyFigure(
                        clip = ExerciseAnimations.clipOrStanding(exercise.id),
                        highlight = highlightFor(exercise),
                        sizeDp = 48.dp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = exerciseRowSubtitle(exercise),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
